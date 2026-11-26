package pl.codeloom.php.runtime;

import java.util.HashMap;

enum PhpNull { NULL }

class PhpError extends RuntimeException {
    PhpError(String message) { super(message); }
    PhpError(String type, String message) { super(type + ": " + message); }
}

final class RefIteration {
    static void copied(PhpArray a, PhpArray b) { throw new AssertionError("Unexpected RefIteration.copied"); }
    static void rehashed(PhpArray a, Object[] keys, int size) { throw new AssertionError("Unexpected RefIteration.rehashed"); }
    static void advanced(PhpArray a, int count) { throw new AssertionError("Unexpected RefIteration.advanced"); }
}

/** Common calling convention for functions and closures, whose ownership differs. */
abstract class PhpCallTarget {
    abstract Object invoke(PhpRequestContext ctx, PhpArray args);

    Object invoke0(PhpRequestContext ctx) { return invoke(ctx, PhpRequestContext.EMPTY_ARGS); }
    Object invoke1(PhpRequestContext ctx, Object a0) { return invokePacked(ctx, PhpArray.list(a0)); }
    Object invoke2(PhpRequestContext ctx, Object a0, Object a1) { return invokePacked(ctx, PhpArray.list(a0, a1)); }

    private Object invokePacked(PhpRequestContext ctx, PhpArray args) {
        args.addHolder();
        Object result = null;
        Throwable primary = null;
        try {
            return result = invoke(ctx, args);
        } catch (RuntimeException | Error e) {
            primary = e;
            throw e;
        } finally {
            Values.releaseCallArgs(args, null, result, primary);
        }
    }
}

abstract class PhpCallable extends PhpCallTarget {
    @FunctionalInterface
    interface Body {
        Object invoke(PhpRequestContext ctx, PhpArray args);
    }

    static PhpCallable of(Body body) {
        return new PhpCallable() {
            @Override Object invoke(PhpRequestContext ctx, PhpArray args) {
                return body.invoke(ctx, args);
            }
        };
    }
}

abstract class PhpInstanceMethod {
    abstract Object invoke(PhpRequestContext ctx, PhpObject self, PhpArray args);

    Object invoke0(PhpRequestContext ctx, PhpObject self) { return invoke(ctx, self, PhpRequestContext.EMPTY_ARGS); }
    Object invoke1(PhpRequestContext ctx, PhpObject self, Object a0) { return invokePacked(ctx, self, PhpArray.list(a0)); }
    Object invoke2(PhpRequestContext ctx, PhpObject self, Object a0, Object a1) { return invokePacked(ctx, self, PhpArray.list(a0, a1)); }

    private Object invokePacked(PhpRequestContext ctx, PhpObject self, PhpArray args) {
        args.addHolder();
        Object result = null;
        Throwable primary = null;
        try {
            return result = invoke(ctx, self, args);
        } catch (RuntimeException | Error e) {
            primary = e;
            throw e;
        } finally {
            Values.releaseCallArgs(args, null, result, primary);
        }
    }

    @FunctionalInterface
    interface Body {
        Object invoke(PhpRequestContext ctx, PhpObject self, PhpArray args);
    }

    static PhpInstanceMethod of(Body body) {
        return new PhpInstanceMethod() {
            @Override Object invoke(PhpRequestContext ctx, PhpObject self, PhpArray args) {
                return body.invoke(ctx, self, args);
            }
        };
    }
}

/**
 * Open-world PHP class metadata:
 * - Case-insensitive method dispatch tables (instance & static)
 * - Case-sensitive declared property slot table (zend_class_entry.properties_info equivalent)
 */
final class PhpClass {
    final ByteStr name;
    final ByteStr lowerName;
    private final HashMap<ByteStr, PhpInstanceMethod> instanceMethods = new HashMap<>();
    private final HashMap<ByteStr, PhpCallable> staticMethods = new HashMap<>();
    private final HashMap<ByteStr, Integer> propertySlots = new HashMap<>(32);
    private PhpInstanceMethod destructor;
    private PhpInstanceMethod invoker;

    PhpClass(ByteStr name) {
        this.name = name;
        this.lowerName = name.toLowerAscii();
    }

    PhpClass addMethod(ByteStr methodName, PhpInstanceMethod impl) {
        ByteStr lower = methodName.toLowerAscii();
        instanceMethods.put(lower, impl);
        if (lower.equals(DESTRUCT_NAME)) destructor = impl;
        else if (lower.equals(INVOKE_NAME)) invoker = impl;
        return this;
    }

    PhpClass addStaticMethod(ByteStr methodName, PhpCallable impl) {
        staticMethods.put(methodName.toLowerAscii(), impl);
        return this;
    }

    PhpClass defineProperty(ByteStr propName, int slot) {
        propertySlots.put(propName, slot);
        return this;
    }

    int findPropertySlot(ByteStr propName) {
        Integer slot = propertySlots.get(propName);
        return slot == null ? -1 : slot;
    }

    PhpInstanceMethod findMethod(ByteStr methodName) {
        return instanceMethods.get(methodName.toLowerAscii());
    }

    PhpCallable findStaticMethod(ByteStr methodName) {
        return staticMethods.get(methodName.toLowerAscii());
    }

    boolean hasDestructor() {
        return destructor != null;
    }

    PhpInstanceMethod destructor() {
        return destructor;
    }

    PhpInstanceMethod invoker() {
        return invoker;
    }

    private static final ByteStr DESTRUCT_NAME = ByteStr.of("__destruct");
    private static final ByteStr INVOKE_NAME = ByteStr.of("__invoke");
}

@FunctionalInterface
interface ChildVisitor {
    void visit(Object child);
}

/**
 * Real D17 refcounted PHP object base class (NO no-op addRef/release).
 * Carries intrusive doubly-linked destructible pointers so freed objects are unregistered in O(1)
 * with zero retention past Refcount.zero, while preserving exact creation/handle order at shutdown.
 */
abstract class PhpObject {
    static final int COLOR_MASK = 0x3;
    static final int BLACK = 0;
    static final int PURPLE = 1;
    static final int GREY = 2;
    static final int WHITE = 3;
    static final int GC_BUFFERED = 1 << 2;
    static final int DESTRUCTOR_CALLED = 1 << 3;
    static final int FREED = 1 << 4;
    static final int HAS_DESTRUCTOR = 1 << 5;
    static final int IN_DTOR_LIST = 1 << 6;

    final PhpRequestContext ctx;
    final PhpClass phpClass;
    final long id;
    int rc = 1; // owning creation ref (matches php-src GC_SET_REFCOUNT(obj, 1))
    int gcInfo;
    int gcBufIndex = -1;
    PhpObject dtorPrev;
    PhpObject dtorNext;

    PhpObject(PhpRequestContext ctx, PhpClass phpClass) {
        this.ctx = ctx;
        this.phpClass = phpClass;
        this.id = ctx.nextObjectId();
        if (phpClass.hasDestructor()) {
            this.gcInfo = HAS_DESTRUCTOR;
            ctx.registerDestructible(this);
        }
    }

    final int refcount() {
        return rc;
    }

    final void addRef() {
        ++rc;
    }

    /** Transfers ownership without triggering zero/possibleRoot (used by PhpArray.pop()). */
    final void disown() {
        --rc;
    }

    /** Real D17 decrement: fires zero() at 0, buffers as possible GC root when > 0. */
    final void release() {
        int count = --rc;
        if (count == 0) {
            Refcount.zero(this);
        } else if (count > 0 && (gcInfo & GC_BUFFERED) == 0) {
            Refcount.possibleRoot(this);
        }
    }

    final boolean hasDestructor() {
        return (gcInfo & HAS_DESTRUCTOR) != 0;
    }

    final boolean isDestructorCalled() {
        return (gcInfo & DESTRUCTOR_CALLED) != 0;
    }

    final boolean isFreed() {
        return (gcInfo & FREED) != 0;
    }

    final boolean markDestructorCalled() {
        boolean already = (gcInfo & DESTRUCTOR_CALLED) != 0;
        gcInfo |= DESTRUCTOR_CALLED;
        return already;
    }

    final boolean markFreed() {
        boolean already = (gcInfo & FREED) != 0;
        gcInfo |= FREED;
        return already;
    }

    final int color() {
        return gcInfo & COLOR_MASK;
    }

    final void setColor(int color) {
        gcInfo = (gcInfo & ~COLOR_MASK) | color;
    }

    /** Open-world runtime property accessors (overridden by classes with declared slots). */
    Object readProp(ByteStr name) {
        return PhpNull.NULL;
    }

    void writeProp(ByteStr name, Object value) {
        throw new PhpError("Cannot write dynamic property " + name + " on " + phpClass.name);
    }

    /** Releases all held properties (called by Refcount.zero and cycle collection). */
    void freeProperties() {}

    /** Visits all held PHP values for Bacon-Rajan cycle collection. */
    void visitChildren(ChildVisitor visitor) {}
}

/** Real D17 refcounted PHP closure supporting by-ref and by-value captures. */
abstract class PhpClosure extends PhpCallTarget {
    final PhpRequestContext ctx;
    final long id;
    int rc = 1;
    final PhpRef captureRef;
    final Object boundThis;
    int gcIndex = -1;
    private boolean capturesReleased;

    PhpClosure(PhpRequestContext ctx, PhpRef captureRef) {
        this(ctx, captureRef, null);
    }

    PhpClosure(PhpRequestContext ctx, PhpRef captureRef, Object boundThis) {
        this.boundThis = boundThis;
        Values.retainValue(boundThis);
        this.ctx = ctx;
        this.id = ctx.nextObjectId();
        this.captureRef = captureRef;
        if (captureRef != null) {
            captureRef.bind();
        }
    }

    final int refcount() {
        return rc;
    }

    final void addRef() {
        ++rc;
    }

    final void disown() {
        --rc;
    }

    final void release() {
        if (--rc > 0) {
            ctx.bufferClosure(this);
        } else if (rc == 0) {
            ctx.unbufferClosure(this);
            releaseCaptures();
        }
    }

    final void releaseCaptures() {
        if (capturesReleased) return;
        capturesReleased = true;
        try {
            if (captureRef != null) captureRef.unbind();
        } finally {
            try {
                Values.releaseValue(boundThis);
            } finally {
                freeCaptures();
            }
        }
    }

    void visitCaptures(ChildVisitor visitor) {
        if (captureRef != null) visitor.visit(captureRef);
        if (boundThis != null) visitor.visit(boundThis);
    }

    void freeCaptures() {}

    abstract Object invoke(PhpRequestContext ctx, PhpArray args);
}

final class Values {
    static Object add(Object left, long right) {
        if (left instanceof Long l) return l + right;
        return addSlow(left, right);
    }

    static Object add(Object left, Object right) {
        if (left instanceof Long l && right instanceof Long r) return l + r;
        return addSlow(left, right);
    }

    private static Object addSlow(Object left, Object right) {
        Object ln = toNumber(left);
        Object rn = toNumber(right);
        if (ln instanceof Double || rn instanceof Double) {
            double ld = ln instanceof Double d ? d : ((Long) ln).doubleValue();
            double rd = rn instanceof Double d ? d : ((Long) rn).doubleValue();
            return ld + rd;
        }
        return (Long) ln + (Long) rn;
    }

    private static Object toNumber(Object value) {
        if (value instanceof Long || value instanceof Double) return value;
        if (value instanceof ByteStr s) {
            Object number = NumericString.parseNumber(s.rawBytes(), true);
            if (number != null) return number;
        }
        if (value instanceof Boolean b) return b ? 1L : 0L;
        if (value == PhpNull.NULL) return 0L;
        throw new PhpError("Unsupported operand for numeric arithmetic");
    }

    static long toLong(Object value) {
        return value instanceof Long l ? l : toLongSlow(value);
    }

    private static long toLongSlow(Object value) {
        if (value instanceof ByteStr s) {
            Object number = NumericString.parseNumber(s.rawBytes(), true);
            if (number instanceof Long l) return l;
            if (number instanceof Double d) return Double.isFinite(d) ? d.longValue() : 0L;
        }
        if (value instanceof Boolean b) return b ? 1L : 0L;
        if (value == PhpNull.NULL) return 0L;
        throw new PhpError("Unsupported operand for integer arithmetic");
    }

    static PhpArray owned(PhpArray a) {
        a.addHolder();
        return a;
    }

    static PhpArray writable(PhpArray a) {
        if (!a.isShared()) return a;
        PhpArray copy = owned(a.copy());
        a.removeHolder();
        return copy;
    }

    static PhpArray assoc(Object... pairs) {
        PhpArray a = new PhpArray();
        for (int i = 0; i < pairs.length; i += 2) {
            a.put(pairs[i], pairs[i + 1]);
        }
        return a;
    }

    static PhpArray literal(PhpArray a) {
        a.freeze();
        return a;
    }

    static boolean isset(Object value) {
        return value != null && value != PhpNull.NULL;
    }

    // A freshly returned array may still be a temporary until the first PHP slot takes it.
    static Object ownReturned(Object value) {
        if (value instanceof PhpArray array && array.holderCount() == 0) array.addHolder();
        return value;
    }

    static void releaseReturned(Object value) {
        if (value instanceof PhpArray array && array.holderCount() == 0) {
            array.releaseTemporary();
        } else {
            releaseValue(value);
        }
    }

    // If destroying the frame throws, the caller never receives the callback's return.
    static void releaseCallArgs(Object a0, Object a1, Object result, Throwable primary) {
        PhpArray temporary = result instanceof PhpArray a && a.holderCount() == 0 ? a : null;
        if (temporary != null) temporary.addHolder();
        Throwable failure = releaseWithThrown(a1, primary);
        failure = releaseWithThrown(a0, failure);
        if (failure != null) {
            discardResult(result, failure);
            throwCleanupFailure(failure);
        }
        if (temporary != null) temporary.removeReturnedHolder();
    }

    static Throwable releaseWithThrown(Object value, Throwable primary) {
        try {
            releaseValue(value);
        } catch (RuntimeException | Error e) {
            return recordCleanupFailure(primary, e);
        }
        return primary;
    }

    static void discardResult(Object result, Throwable primary) {
        try {
            releaseReturned(result);
        } catch (RuntimeException | Error e) {
            recordCleanupFailure(primary, e);
            throw e;
        }
    }

    static Throwable recordCleanupFailure(Throwable previous, Throwable failure) {
        if (previous != null && previous != failure) failure.addSuppressed(previous);
        return failure;
    }

    static void throwCleanupFailure(Throwable failure) {
        if (failure instanceof Error e) throw e;
        if (failure instanceof RuntimeException e) throw e;
    }

    static void releaseValue(Object value) {
        if (value instanceof PhpArray a) {
            a.removeHolder();
        } else if (value instanceof PhpObject o) {
            o.release();
        } else if (value instanceof PhpClosure c) {
            c.release();
        } else if (value instanceof PhpRef r) {
            r.unbind();
        }
    }

    static void retainValue(Object value) {
        if (value instanceof PhpArray a) {
            a.addHolder();
        } else if (value instanceof PhpObject o) {
            o.addRef();
        } else if (value instanceof PhpClosure c) {
            c.addRef();
        } else if (value instanceof PhpRef r) {
            r.bind();
        }
    }
}
