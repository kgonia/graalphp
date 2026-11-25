package pl.codeloom.php.runtime;

import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import java.util.*;
import java.util.function.BiConsumer;

/** Snapshot plans contain scalar values, frozen arrays and indexed edges. The capture-time
 * identity maps and the template context are discarded before publication. */
public final class BootstrapSnapshot {
    public interface State { void install(PhpContext context, Frame frame); }
    interface Plan<T> {
        T allocate(Frame frame);
        void fill(T shell, Frame frame);
    }
    private interface Edge { Object get(Frame frame); }
    private enum Kind { CLASS, OBJECT, CLOSURE, REF, ARRAY }
    private record Slot(Kind kind, int index) implements Edge {
        public Object get(Frame f) {
            return switch (kind) {
                case CLASS -> f.classes[index];
                case OBJECT -> f.objects[index];
                case CLOSURE -> f.closures[index];
                case REF -> f.refs[index];
                case ARRAY -> f.arrays[index];
            };
        }
    }

    public static final class Frame {
        final PhpClass[] classes;
        final PhpObject[] objects;
        final PhpClosure[] closures;
        final PhpRef[] refs;
        final PhpArray[] arrays;
        final PhpContext context;
        Frame(BootstrapSnapshot s, PhpContext ctx) {
            context = ctx;
            classes = new PhpClass[s.classes.size()];
            objects = new PhpObject[s.objects.size()];
            closures = new PhpClosure[s.closures.size()];
            refs = new PhpRef[s.refs.size()];
            arrays = new PhpArray[s.arrays.size()];
        }
        public Object value(Object v) { return v instanceof Edge e ? e.get(this) : v; }
        public Object[] values(Object[] encoded) {
            Object[] result = encoded.clone();
            for (int i = 0; i < result.length; i++) result[i] = value(result[i]);
            return result;
        }
    }

    /** Keys are immutable PHP symbol/property names. Edge resolution only indexes dense arrays. */
    public static final class Table<K,V> {
        private final ArrayList<K> keys;
        private final Object[] values;
        Table(Map<K,V> source, Builder b) {
            keys = new ArrayList<>(source.size());
            values = new Object[source.size()];
            source.forEach((k,v) -> {
                values[keys.size()] = b.value(v);
                keys.add(k);
            });
        }
        @SuppressWarnings("unchecked")
        public void fill(Map<K,V> target, Frame frame) {
            for (int i = 0; i < values.length; i++)
                target.put(keys.get(i), (V) frame.value(values[i]));
        }
        boolean hasEdges() {
            for (Object value : values) if (value instanceof Edge) return true;
            return false;
        }
    }

    public static final class Builder {
        final IdentityHashMap<Object,Slot> slots = new IdentityHashMap<>();
        final ArrayDeque<Object> queue = new ArrayDeque<>();
        final int[] counts = new int[Kind.values().length];
        final IdentityHashMap<PhpArray,Boolean> purity = new IdentityHashMap<>();
        int frozen;
        long frozenBytes, freezeNanos;

        public <K,V> Table<K,V> table(Map<K,V> source) { return new Table<>(source, this); }
        public Object[] values(Object[] source) {
            Object[] result = new Object[source.length];
            for (int i = 0; i < source.length; i++) result[i] = value(source[i]);
            return result;
        }
        public Object value(Object v) {
            if (scalar(v)) return v;
            Kind kind;
            if (v instanceof PhpClass) kind = Kind.CLASS;
            else if (v instanceof PhpObject) kind = Kind.OBJECT;
            else if (v instanceof PhpClosure) kind = Kind.CLOSURE;
            else if (v instanceof PhpRef) kind = Kind.REF;
            else if (v instanceof PhpArray a) {
                if (pure(a)) return a;
                kind = Kind.ARRAY;
            } else if (v instanceof PhpClass.ScopedThunk t) {
                Object owner = value(t.owner());
                ClassTemplate.Deferred recipe = t.recipe();
                return (Edge) f -> new PhpClass.ScopedThunk(recipe, (PhpClass) f.value(owner));
            } else if (v instanceof PhpClass.CheckedThunk t) {
                Object thunk = value(t.thunk());
                PhpType type = t.type();
                String what = t.what();
                return (Edge) f -> new PhpClass.CheckedThunk((PhpClass.Thunk) f.value(thunk), type, what);
            } else if (v instanceof PhpClass.ConstantType t) {
                Object owner = value(t.declaringClass());
                PhpType type = t.type();
                boolean priv = t.isPrivate();
                return (Edge) f -> new PhpClass.ConstantType(type, (PhpClass) f.value(owner), priv);
            } else if (v instanceof PhpClass.PropertyDecl p) {
                Object encoded = value(p.defaultValue());
                if (!(encoded instanceof Edge)) return p;
                String name = p.name(), owner = p.declaringClass();
                int visibility = p.visibility(), flags = p.modifiers();
                PhpType type = p.type();
                return (Edge) f -> new PhpClass.PropertyDecl(name, f.value(encoded), visibility, owner, flags, type);
            } else if (v instanceof Callables.Target t) {
                Object self = value(t.thisObject()), scope = value(t.staticClass()), closure = value(t.closure());
                PhpFunction function = t.function();
                TruffleString magic = t.magicName();
                return (Edge) f -> new Callables.Target(function, f.value(self), (PhpClass) f.value(scope),
                        f.value(closure), magic);
            } else {
                throw new IllegalStateException("Unsupported bootstrap value: " + v.getClass().getName());
            }
            Slot slot = slots.get(v);
            if (slot == null) {
                slot = new Slot(kind, counts[kind.ordinal()]++);
                slots.put(v, slot);
                queue.add(v);
            }
            return slot;
        }

        /** Propagate impurity backwards, also for strongly connected components. An edge to
         * an array classified in an earlier traversal already has a definitive answer. */
        private boolean pure(PhpArray root) {
            Boolean known = purity.get(root);
            if (known != null) return known;
            long start = System.nanoTime();
            IdentityHashMap<PhpArray,List<PhpArray>> parents = new IdentityHashMap<>();
            ArrayDeque<PhpArray> visit = new ArrayDeque<>(), dirty = new ArrayDeque<>();
            parents.put(root, new ArrayList<>());
            visit.add(root);
            while (!visit.isEmpty()) {
                PhpArray a = visit.remove();
                boolean contaminated = false;
                for (int p = a.firstPosition(); p >= 0; p = a.nextPosition(p)) {
                    Object raw = a.rawAt(p);
                    if (raw instanceof PhpArray child) {
                        Boolean childPure = purity.get(child);
                        if (childPure != null) {
                            contaminated |= !childPure;
                        } else {
                            List<PhpArray> predecessors = parents.get(child);
                            if (predecessors == null) {
                                predecessors = new ArrayList<>();
                                parents.put(child, predecessors);
                                visit.add(child);
                            }
                            predecessors.add(a);
                        }
                    } else if (!scalar(raw)) contaminated = true;
                }
                if (contaminated && purity.put(a, false) == null) dirty.add(a);
            }
            while (!dirty.isEmpty()) {
                for (PhpArray a : parents.get(dirty.remove())) {
                    if (purity.put(a, false) == null) dirty.add(a);
                }
            }
            for (PhpArray a : parents.keySet()) {
                if (purity.putIfAbsent(a, true) == null) {
                    a.freezeShallow();
                    frozen++;
                    frozenBytes += a.snapshotBytes();
                }
            }
            freezeNanos += System.nanoTime() - start;
            return purity.get(root);
        }
    }

    private static boolean scalar(Object v) {
        return v == null || v == PhpNull.NULL || v instanceof TruffleString || v instanceof String
                || v instanceof Long || v instanceof Integer || v instanceof Boolean || v instanceof Double;
    }
    private final List<Plan<PhpClass>> classes = new ArrayList<>();
    private final List<Plan<PhpObject>> objects = new ArrayList<>();
    private final List<Plan<PhpClosure>> closures = new ArrayList<>();
    private final List<Plan<PhpRef>> refs = new ArrayList<>();
    private final List<Plan<PhpArray>> arrays = new ArrayList<>();
    private final State state;
    public final int snapshotMaxObjectId;

    public BootstrapSnapshot(PhpContext ctx) {
        long start = System.nanoTime();
        Builder b = new Builder();
        BiConsumer<String,Object> discover = (name,v) -> b.value(v);
        ctx.censusRoots(discover);
        ctx.censusHolders(discover);
        state = ctx.snapshotState(b);
        snapshotMaxObjectId = ctx.lastAllocatedObjectId();
        while (!b.queue.isEmpty()) {
            Object v = b.queue.remove();
            if (v instanceof PhpClass c) classes.add(c.snapshot(b));
            else if (v instanceof PhpObject o) objects.add(o.snapshot(b));
            else if (v instanceof PhpClosure l) closures.add(l.snapshot(b));
            else if (v instanceof PhpRef r) refs.add(r.snapshot(b));
            else arrays.add(((PhpArray) v).snapshot(b));
        }
        System.err.printf(Locale.ROOT, "freeze build snapshot_max_obj_id=%d frozen_arrays=%d estimated_array_bytes=%d C=%d O=%d L=%d R=%d A=%d classify_freeze_ms=%.3f plan_ms=%.3f%n",
                snapshotMaxObjectId, b.frozen, b.frozenBytes, classes.size(), objects.size(), closures.size(), refs.size(), arrays.size(),
                b.freezeNanos / 1e6, (System.nanoTime() - start) / 1e6);
    }

    private static <T> void allocate(List<Plan<T>> plans, T[] shells, Frame frame) {
        for (int i = 0; i < shells.length; i++) shells[i] = plans.get(i).allocate(frame);
    }
    private static <T> void fill(List<Plan<T>> plans, T[] shells, Frame frame) {
        for (int i = 0; i < shells.length; i++) plans.get(i).fill(shells[i], frame);
    }
    public void restore(PhpContext ctx, long requestStartMicros, boolean frontend) {
        Frame f = new Frame(this, ctx);
        allocate(classes, f.classes, f);
        allocate(objects, f.objects, f);
        allocate(closures, f.closures, f);
        allocate(refs, f.refs, f);
        allocate(arrays, f.arrays, f);
        fill(classes, f.classes, f);
        fill(objects, f.objects, f);
        fill(closures, f.closures, f);
        fill(refs, f.refs, f);
        fill(arrays, f.arrays, f);
        state.install(ctx, f);
        ctx.rebindWordPress(requestStartMicros, frontend);
    }
}
