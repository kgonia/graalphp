package pl.codeloom.php.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;

/**
 * Real D17 refcounting engine + shadow-refcount Bacon-Rajan cycle collector (gc_collect_cycles)
 * + exact Zend shutdown_destructors() (zend_execute_API.c:210-267).
 */
public final class Refcount {
    private Refcount() {}

    public static void inc(Object value) {
        if (value instanceof PhpObject object) {
            object.addRef();
        } else if (value instanceof PhpClosure closure) {
            closure.addRef();
        }
    }

    public static void dec(Object value) {
        if (value instanceof PhpObject object) {
            object.release();
        } else if (value instanceof PhpClosure closure) {
            closure.release();
        }
    }

    public static void hold(Object value) {
        if (value instanceof PhpArray array) {
            array.addHolder();
        } else {
            inc(value);
        }
    }

    public static void unhold(Object value) {
        if (value instanceof PhpArray array) {
            array.removeHolder();
        } else {
            dec(value);
        }
    }

    /** zend_objects_store_del: runs __destruct once (supporting resurrection), unregisters, then frees properties. */
    static void zero(PhpObject object) {
        PhpRequestContext ctx = object.ctx;
        if ((object.gcInfo & PhpObject.GC_BUFFERED) != 0) {
            ctx.unbufferRoot(object);
        }
        Throwable thrown = null;
        if (object.hasDestructor() && !object.markDestructorCalled()) {
            object.rc = 1; // GC_ADDREF during destructor
            try {
                Object res = object.phpClass.destructor().invoke(ctx, object, PhpRequestContext.EMPTY_ARGS);
                Values.releaseReturned(res);
            } catch (RuntimeException | Error e) {
                thrown = e;
            }
            if (--object.rc > 0) {
                // Resurrected inside __destruct: lives on, will NOT run __destruct again.
                ctx.unregisterDestructible(object);
                if (thrown != null) {
                    if (thrown instanceof Error err) throw err;
                    throw (RuntimeException) thrown;
                }
                return;
            }
        }
        ctx.unregisterDestructible(object);
        if (!object.markFreed()) {
            object.freeProperties();
        }
        if (thrown != null) {
            if (thrown instanceof Error err) throw err;
            throw (RuntimeException) thrown;
        }
    }

    /** php-src gc_possible_root: buffer object once when rc > 0 after decrement. */
    static void possibleRoot(PhpObject object) {
        object.setColor(PhpObject.PURPLE);
        object.ctx.bufferRoot(object);
    }

    static void possibleValueRoot(Object value) {
        if (value instanceof PhpObject object) possibleRoot(object);
        else if (value instanceof PhpClosure closure) closure.ctx.bufferClosure(closure);
        else if (value instanceof PhpArray array && array.gcArrayIdx < 0) possibleArrayRoot(array);
    }

    static void possibleArrayRoot(PhpArray array) {
        PhpRequestContext ctx = array.gcOwner != null ? array.gcOwner : findOwnerContext(array);
        if (ctx != null) bufferArrayRoot(ctx, array);
    }

    static void bufferArrayRoot(PhpRequestContext ctx, PhpArray array) {
        if (array.gcArrayIdx >= 0) return;
        array.gcOwner = ctx;
        array.gcArrayIdx = ctx.gcArrayRoots.size();
        ctx.gcArrayRoots.add(array);
    }

    static void unbufferArrayRoot(PhpArray array) {
        ArrayList<PhpArray> roots = array.gcOwner.gcArrayRoots;
        int index = array.gcArrayIdx;
        PhpArray last = roots.removeLast();
        if (index < roots.size()) {
            roots.set(index, last);
            last.gcArrayIdx = index;
        }
        array.gcArrayIdx = -1;
    }

    private static PhpRequestContext findOwnerContext(PhpArray root) {
        var seen = new IdentityHashMap<Object, Boolean>();
        var pending = new ArrayList<Object>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Object value = pending.removeLast();
            if (value instanceof PhpObject object) return object.ctx;
            if (value instanceof PhpClosure closure) return closure.ctx;
            if (value instanceof PhpArray array && array.gcOwner != null) return array.gcOwner;
            if ((value instanceof PhpArray || value instanceof PhpRef) && seen.put(value, true) == null) {
                visitChildren(value, pending::add);
            }
        }
        return null;
    }

    // The discovery order is also the destructor order. Both graph passes count
    // incoming slots by identity. A shared reference owns its target exactly once.
    private static final class GcNode {
        final Object value;
        int trialRc;
        boolean reachable;

        GcNode(Object value) {
            this.value = value;
        }
    }

    private static int count(Object value) {
        if (value instanceof PhpObject object) return object.isFreed() ? 0 : object.rc;
        if (value instanceof PhpClosure closure) return closure.rc;
        if (value instanceof PhpArray array) return array.holderCount();
        if (value instanceof PhpRef ref) return ref.bindingCount();
        return 0;
    }

    private static void visitChildren(Object value, ChildVisitor visit) {
        if (value instanceof PhpObject object) {
            object.visitChildren(visit);
        } else if (value instanceof PhpClosure closure) {
            closure.visitCaptures(visit);
        } else if (value instanceof PhpRef ref) {
            visit.visit(ref.get());
        } else if (value instanceof PhpArray array) {
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                visit.visit(array.rawAt(p));
            }
        }
    }

    private static ArrayList<GcNode> discover(PhpRequestContext ctx, IdentityHashMap<Object, GcNode> nodes) {
        var order = new ArrayList<GcNode>();
        var stack = new ArrayList<Object>();
        stack.addAll(ctx.gcRoots);
        stack.addAll(ctx.gcArrayRoots);
        stack.addAll(ctx.gcClosureRoots);
        Collections.reverse(stack);
        while (!stack.isEmpty()) {
            Object value = stack.removeLast();
            if (count(value) <= 0 || nodes.containsKey(value)) continue;
            var node = new GcNode(value);
            nodes.put(value, node);
            if (value instanceof PhpArray array && array.gcOwner == null) array.gcOwner = ctx;
            order.add(node);
            int start = stack.size();
            visitChildren(value, stack::add);
            Collections.reverse(stack.subList(start, stack.size()));
        }
        return order;
    }

    private static void findReachable(ArrayList<GcNode> order, IdentityHashMap<Object, GcNode> nodes, int protection) {
        for (GcNode node : order) {
            node.trialRc = count(node.value) - protection;
            node.reachable = false;
        }
        for (GcNode node : order) {
            visitChildren(node.value, child -> {
                GcNode target = nodes.get(child);
                if (target != null) --target.trialRc;
            });
        }

        var live = new ArrayList<GcNode>();
        for (GcNode node : order) {
            if (node.trialRc > 0) {
                node.reachable = true;
                live.add(node);
            }
        }
        while (!live.isEmpty()) {
            visitChildren(live.removeLast().value, child -> {
                GcNode target = nodes.get(child);
                if (target != null && !target.reachable) {
                    target.reachable = true;
                    live.add(target);
                }
            });
        }
    }

    public static int collectCycles(PhpRequestContext ctx) {
        if (ctx.collecting || (ctx.gcRoots.isEmpty() && ctx.gcArrayRoots.isEmpty() && ctx.gcClosureRoots.isEmpty())) {
            return 0;
        }
        ctx.collecting = true;
        try {
            var nodes = new IdentityHashMap<Object, GcNode>();
            ArrayList<GcNode> white = discover(ctx, nodes);
            ctx.clearGcRoots();
            findReachable(white, nodes, 0);
            white.removeIf(node -> node.reachable);
            nodes.values().removeIf(node -> node.reachable);
            return collectWhite(ctx, white, nodes);
        } finally {
            ctx.collecting = false;
        }
    }

    private static int collectWhite(PhpRequestContext ctx, ArrayList<GcNode> white,
                                    IdentityHashMap<Object, GcNode> nodes) {
        for (GcNode node : white) Values.retainValue(node.value);
        try {
            for (GcNode node : white) {
                if (node.value instanceof PhpObject obj && obj.hasDestructor() && !obj.markDestructorCalled()) {
                    ctx.unregisterDestructible(obj);
                    Values.releaseReturned(obj.phpClass.destructor().invoke(ctx, obj, PhpRequestContext.EMPTY_ARGS));
                }
            }

            // A destructor can replace an internal edge with an external one at identical rc.
            findReachable(white, nodes, 1);
            int collected = 0;
            for (GcNode node : white) {
                if (node.reachable) continue;
                Object value = node.value;
                if (value instanceof PhpRef ref) {
                    ref.set(PhpNull.NULL);
                    continue; // PHP counts arrays and objects (including closures), not reference cells.
                }
                if (value instanceof PhpObject obj) {
                    obj.markFreed();
                    obj.freeProperties();
                } else if (value instanceof PhpClosure closure) {
                    closure.releaseCaptures();
                } else if (value instanceof PhpArray array) {
                    for (int p = array.lastPosition(); p >= 0; p = array.previousPosition(p)) {
                        array.remove(array.keyAt(p));
                    }
                }
                ++collected;
            }
            return collected;
        } finally {
            // Normal decrements also re-buffer surviving cycles for another attempt after a throw.
            releaseProtections(ctx, white);
        }
    }

    private static void releaseProtections(PhpRequestContext ctx, ArrayList<GcNode> white) {
        Throwable failure = null;
        for (GcNode node : white) {
            try {
                Values.releaseValue(node.value);
                if (node.value instanceof PhpArray array && array.holderCount() > 0) bufferArrayRoot(ctx, array);
            } catch (RuntimeException | Error e) {
                if (failure == null) failure = e;
                else if (e != failure) failure.addSuppressed(e);
            }
        }
        if (failure instanceof RuntimeException e) throw e;
        if (failure instanceof Error e) throw e;
    }

    /**
     * Exact php-src shutdown_destructors() (Zend/zend_execute_API.c:210-267):
     * Phase 1: repeat reverse-order pass over EG(symbol_table) removing any top-level IS_OBJECT
     *          whose refcount == 1 (calling its destructor via release()), until fixpoint.
     * Phase 2: walk remaining live destructible objects in handle/creation order (intrusive dtorHead list).
     */
    static void shutdownDestructors(PhpRequestContext ctx) {
        int prevSize;
        do {
            prevSize = ctx.globals.size();
            if (prevSize == 0) break;
            ByteStr[] keys = ctx.globals.keySet().toArray(new ByteStr[0]);
            for (int i = keys.length - 1; i >= 0; i--) {
                ByteStr k = keys[i];
                Object v = ctx.globals.get(k);
                if (v instanceof PhpObject obj && obj.rc == 1) {
                    ctx.globals.remove(k);
                    obj.release(); // rc 1 -> 0 -> runs __destruct & freeProperties!
                }
            }
        } while (ctx.globals.size() != prevSize);

        // Phase 2: remaining live destructible objects in creation/handle order
        while (ctx.dtorHead != null) {
            PhpObject cur = ctx.dtorHead;
            ctx.unregisterDestructible(cur);
            if (cur.rc > 0 && !cur.markDestructorCalled()) {
                cur.addRef();
                try {
                    Values.releaseReturned(cur.phpClass.destructor().invoke(ctx, cur, PhpRequestContext.EMPTY_ARGS));
                } finally {
                    cur.release();
                }
            }
        }
        ctx.clearGcRoots();
    }
}
