package pl.codeloom.php.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;

import static pl.codeloom.php.runtime.Values.*;

/**
 * Explicit per-request context passed as parameter 0 to every PHP function, method, and builtin.
 * Holds ALL per-request mutable state: open-world function/class/constant/global tables,
 * WordPress hook globals, object ID counter, GC root buffer, and O(1) intrusive destructible list.
 * Zero global mutable state, zero ThreadLocals, zero global SwitchPoints.
 */
public final class PhpRequestContext {
    static final PhpArray EMPTY_ARGS = literal(new PhpArray());

    private final HashMap<ByteStr, PhpCallable> functions = new HashMap<>(128);
    private final HashMap<ByteStr, PhpClass> classes = new HashMap<>(64);
    private final HashMap<ByteStr, Object> constants = new HashMap<>(32);
    final LinkedHashMap<ByteStr, Object> globals = new LinkedHashMap<>(32);

    final PhpArray wpFilter = owned(new PhpArray());
    final PhpArray wpFilters = owned(new PhpArray());
    final PhpArray wpActions = owned(new PhpArray());
    final PhpArray wpCurrentFilter = owned(new PhpArray());

    private long nextObjectId;
    final ArrayList<PhpObject> gcRoots = new ArrayList<>(64);
    final ArrayList<PhpArray> gcArrayRoots = new ArrayList<>(32);

    final ArrayList<PhpClosure> gcClosureRoots = new ArrayList<>();
    boolean collecting;

    // Intrusive doubly-linked list of live destructible objects (O(1) add/remove, exact handle order, zero retention of freed objects).
    PhpObject dtorHead;
    PhpObject dtorTail;
    int liveDestructibleCount;

    final PhpClass stdClassMeta;
    final PhpClass wpHookClass;
    final PhpClass wpPostClass;

    public PhpRequestContext() {
        this.stdClassMeta = defineClass(ByteStr.of("stdClass"));
        this.wpHookClass = defineClass(ByteStr.of("WP_Hook"));
        this.wpPostClass = WPPost.registerClass(this);
        registerCoreBuiltins();
    }

    private void registerCoreBuiltins() {
        registerFunction(ByteStr.of("trim"), new PhpCallable() {
            @Override Object invoke(PhpRequestContext ctx, PhpArray args) {
                return switch (args.size()) {
                    case 1 -> invoke1(ctx, args.get(0L));
                    case 2 -> invoke2(ctx, args.get(0L), args.get(1L));
                    default -> throw new PhpError("trim() expects 1 or 2 arguments, " + args.size() + " given");
                };
            }
            @Override Object invoke1(PhpRequestContext ctx, Object a0) {
                return ByteStr.toByteStr(a0).trim();
            }
            @Override Object invoke2(PhpRequestContext ctx, Object a0, Object a1) {
                return ByteStr.toByteStr(a0).trim(ByteStr.toByteStr(a1));
            }
        });
        registerFunction(ByteStr.of("__return_true"), PhpCallable.of((ctx, args) -> Boolean.TRUE));
    }

    long nextObjectId() {
        return ++nextObjectId;
    }

    public void registerFunction(ByteStr name, PhpCallable fn) {
        functions.put(name.toLowerAscii(), fn);
    }

    public PhpCallable findFunction(ByteStr name) {
        return functions.get(name.toLowerAscii());
    }

    public PhpClass defineClass(ByteStr name) {
        PhpClass cls = new PhpClass(name);
        classes.put(cls.lowerName, cls);
        return cls;
    }

    public PhpClass findClass(ByteStr name) {
        return classes.get(name.toLowerAscii());
    }

    public boolean defineConstant(ByteStr name, Object value) {
        if (constants.containsKey(name)) return false;
        retainValue(value);
        constants.put(name, value);
        return true;
    }

    public Object getConstant(ByteStr name) {
        return constants.get(name);
    }

    public boolean isDefined(ByteStr name) {
        return constants.containsKey(name);
    }

    public void setGlobal(ByteStr name, Object value) {
        Object old = globals.get(name);
        if (old == value && globals.containsKey(name)) return;
        retainValue(value);
        globals.put(name, value);
        releaseValue(old);
        // A pure array/reference cycle can have no object from which to infer its request.
        Object displaced = PhpRef.deref(old);
        if (displaced instanceof PhpArray array && array.holderCount() > 0) {
            Refcount.bufferArrayRoot(this, array);
        }
    }

    public Object getGlobal(ByteStr name) {
        Object v = globals.get(name);
        return v == null ? PhpNull.NULL : v;
    }

    /**
     * True open-world PHP callable dispatch (shared linkage from Probe A).
     * ZERO closed-world switch(name) shortcuts!
     */
    public Object call(Object callable, PhpArray args) {
        return dispatch(callable, -1, null, null, args);
    }

    public Object call0(Object callable) {
        return dispatch(callable, 0, null, null, null);
    }

    public Object call1(Object callable, Object a0) {
        return callFixed(callable, 1, a0, null);
    }

    public Object call2(Object callable, Object a0, Object a1) {
        return callFixed(callable, 2, a0, a1);
    }

    private Object callFixed(Object callable, int arity, Object a0, Object a1) {
        retainValue(a0);
        retainValue(a1);
        Object result = null;
        Throwable primary = null;
        try {
            if (a0 instanceof PhpRef || a1 instanceof PhpRef) {
                PhpArray args = arity == 1 ? PhpArray.list(a0) : PhpArray.list(a0, a1);
                return result = callPacked(callable, args);
            }
            return result = dispatch(callable, arity, a0, a1, null);
        } catch (RuntimeException | Error e) {
            primary = e;
            throw e;
        } finally {
            releaseCallArgs(a0, a1, result, primary);
        }
    }

    private Object callPacked(Object callable, PhpArray args) {
        args.addHolder();
        Object result = null;
        Throwable primary = null;
        try {
            return result = call(callable, args);
        } catch (RuntimeException | Error e) {
            primary = e;
            throw e;
        } finally {
            releaseCallArgs(args, null, result, primary);
        }
    }

    /** The caller holds args across dispatch. Only ordinary positional values can be unpacked. */
    public Object callSlice(Object callable, PhpArray args, long take) {
        int size = args.size();
        if (take == 0 || (size == 0 && take >= 0)) return call0(callable);
        long length = take < 0 ? Math.max(0L, size + take) : Math.min(take, size);
        if (take > 0 && length <= 2 && !args.mayHaveArgumentRefs()) {
            if (args.isPacked()) {
                Object a0 = args.getLongKey(0L);
                return length == 1 ? call1(callable, a0) : call2(callable, a0, args.getLongKey(1L));
            }
            int p0 = args.firstPosition();
            if (args.keyAt(p0) instanceof Long) {
                if (length == 1) return call1(callable, args.valueAt(p0));
                int p1 = args.nextPosition(p0);
                if (args.keyAt(p1) instanceof Long) return call2(callable, args.valueAt(p0), args.valueAt(p1));
            }
        }
        if (take >= size && args.isPacked() && !args.mayHaveArgumentRefs()) return callPacked(callable, args);

        PhpArray slice = owned(PhpArray.ofCapacity((int) length));
        Object result = null;
        Throwable primary = null;
        try {
            for (int p = args.firstPosition(); length > 0; p = args.nextPosition(p), --length) {
                Object key = args.keyAt(p);
                Object element = args.copiedAt(p);
                if (key instanceof Long) slice.appendCopied(element);
                else slice.putCopied(key, element);
            }
            return result = call(callable, slice);
        } catch (RuntimeException | Error e) {
            primary = e;
            throw e;
        } finally {
            releaseCallArgs(slice, null, result, primary);
        }
    }

    private Object dispatch(Object callable, int arity, Object a0, Object a1, PhpArray args) {
        if (callable instanceof PhpClosure closure) {
            return switch (arity) {
                case 0 -> closure.invoke0(this);
                case 1 -> closure.invoke1(this, a0);
                case 2 -> closure.invoke2(this, a0, a1);
                default -> closure.invoke(this, args);
            };
        }
        if (callable instanceof ByteStr name) {
            return invoke(resolveNamed(name), arity, a0, a1, args);
        }
        if (callable instanceof PhpArray pair) {
            Object target = pair.get(0L);
            Object methodObj = pair.get(1L);
            if (!(methodObj instanceof ByteStr methodName)) {
                throw new PhpError("Invalid callable method name");
            }
            if (target instanceof PhpObject obj) {
                PhpInstanceMethod m = obj.phpClass.findMethod(methodName);
                if (m == null) {
                    throw new PhpError("Call to undefined method " + obj.phpClass.name + "::" + methodName + "()");
                }
                return invoke(m, obj, arity, a0, a1, args);
            }
            if (target instanceof ByteStr clsName) {
                return invoke(resolveStatic(clsName, methodName), arity, a0, a1, args);
            }
            throw new PhpError("Invalid callable target");
        }
        if (callable instanceof PhpObject obj) {
            PhpInstanceMethod inv = obj.phpClass.invoker();
            if (inv != null) {
                return invoke(inv, obj, arity, a0, a1, args);
            }
            throw new PhpError("Object of type " + obj.phpClass.name + " is not callable");
        }
        throw new PhpError("Value is not callable: " + callable);
    }

    private Object invoke(PhpCallable fn, int arity, Object a0, Object a1, PhpArray args) {
        return switch (arity) {
            case 0 -> fn.invoke0(this);
            case 1 -> fn.invoke1(this, a0);
            case 2 -> fn.invoke2(this, a0, a1);
            default -> fn.invoke(this, args);
        };
    }

    private Object invoke(PhpInstanceMethod method, PhpObject self, int arity, Object a0, Object a1, PhpArray args) {
        return switch (arity) {
            case 0 -> method.invoke0(this, self);
            case 1 -> method.invoke1(this, self, a0);
            case 2 -> method.invoke2(this, self, a0, a1);
            default -> method.invoke(this, self, args);
        };
    }

    private PhpCallable resolveNamed(ByteStr name) {
        PhpCallable fn = findFunction(name);
        if (fn != null) return fn;
        int sep = name.indexOfDoubleColon();
        if (sep > 0) return resolveStatic(name.slice(0, sep), name.slice(sep + 2, name.length()));
        throw new PhpError("Call to undefined function " + name + "()");
    }

    private PhpCallable resolveStatic(ByteStr clsName, ByteStr methodName) {
        PhpClass cls = findClass(clsName);
        if (cls == null) {
            throw new PhpError("Class \"" + clsName + "\" not found");
        }
        PhpCallable m = cls.findStaticMethod(methodName);
        if (m == null) {
            throw new PhpError("Call to undefined static method " + clsName + "::" + methodName + "()");
        }
        return m;
    }

    void bufferRoot(PhpObject obj) {
        if ((obj.gcInfo & PhpObject.GC_BUFFERED) == 0) {
            obj.gcInfo |= PhpObject.GC_BUFFERED;
            obj.gcBufIndex = gcRoots.size();
            gcRoots.add(obj);
        }
    }

    void unbufferRoot(PhpObject obj) {
        int idx = obj.gcBufIndex;
        if (idx >= 0 && idx < gcRoots.size()) {
            int lastIdx = gcRoots.size() - 1;
            PhpObject last = gcRoots.remove(lastIdx);
            if (idx < lastIdx) {
                gcRoots.set(idx, last);
                if (last != null) last.gcBufIndex = idx;
            }
        }
        obj.gcBufIndex = -1;
        obj.gcInfo &= ~PhpObject.GC_BUFFERED;
        obj.setColor(PhpObject.BLACK);
    }

    void bufferClosure(PhpClosure closure) {
        if (closure.gcIndex < 0) {
            closure.gcIndex = gcClosureRoots.size();
            gcClosureRoots.add(closure);
        }
    }

    void unbufferClosure(PhpClosure closure) {
        int index = closure.gcIndex;
        if (index < 0) return;
        PhpClosure last = gcClosureRoots.removeLast();
        if (index < gcClosureRoots.size()) {
            gcClosureRoots.set(index, last);
            last.gcIndex = index;
        }
        closure.gcIndex = -1;
    }

    void clearGcRoots() {
        while (!gcRoots.isEmpty()) unbufferRoot(gcRoots.getLast());
        while (!gcArrayRoots.isEmpty()) Refcount.unbufferArrayRoot(gcArrayRoots.getLast());
        while (!gcClosureRoots.isEmpty()) unbufferClosure(gcClosureRoots.getLast());
    }

    void registerDestructible(PhpObject obj) {
        if ((obj.gcInfo & PhpObject.IN_DTOR_LIST) != 0) return;
        obj.gcInfo |= PhpObject.IN_DTOR_LIST;
        obj.dtorPrev = dtorTail;
        obj.dtorNext = null;
        if (dtorTail != null) {
            dtorTail.dtorNext = obj;
        } else {
            dtorHead = obj;
        }
        dtorTail = obj;
        liveDestructibleCount++;
    }

    void unregisterDestructible(PhpObject obj) {
        if ((obj.gcInfo & PhpObject.IN_DTOR_LIST) == 0) return;
        obj.gcInfo &= ~PhpObject.IN_DTOR_LIST;
        PhpObject prev = obj.dtorPrev;
        PhpObject next = obj.dtorNext;
        if (prev != null) prev.dtorNext = next;
        else dtorHead = next;
        if (next != null) next.dtorPrev = prev;
        else dtorTail = prev;
        obj.dtorPrev = null;
        obj.dtorNext = null;
        liveDestructibleCount--;
    }

    public int gcCollectCycles() {
        return Refcount.collectCycles(this);
    }

    /** Request shutdown: exact Zend shutdown_destructors, then release globals, constants, and hook tables. */
    public void shutdown() {
        Refcount.shutdownDestructors(this);
        for (Object v : globals.values()) {
            releaseValue(v);
        }
        globals.clear();
        for (Object v : constants.values()) {
            releaseValue(v);
        }
        constants.clear();
        wpFilter.removeHolder();
        wpFilters.removeHolder();
        wpActions.removeHolder();
        wpCurrentFilter.removeHolder();
    }
}
