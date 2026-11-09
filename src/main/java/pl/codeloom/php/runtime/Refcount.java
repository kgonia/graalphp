package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

/**
 * Objects' reference counts (wiki/destructors.md, P1), counting only: the store paths count every {@link PhpObject}
 * a slot holds (a variable, an element of a held array, a property, a reference, a parameter, a capture) and every
 * owning temporary (the result of `new` or a call, until it is stored, returned or dropped). The two events a
 * decrement can raise, the count reaching zero and the object becoming a possible root of a garbage cycle, go out
 * of line here. At zero, with -Dphp.rc.destructors=true, the destructor runs and the properties are released
 * ({@link #destroy}); without it nothing runs yet. The possible-root event only marks the object (no cycle collector).
 * <p>
 * Counts are plain ints: a context runs on one thread at a time (D18, answer 7). With {@code -Dphp.rc.stats=true}
 * the number of events is printed at exit.
 */
public final class Refcount {
    private Refcount() {
    }

    private static final boolean STATS = Boolean.getBoolean("php.rc.stats");
    private static long zeros;
    private static long roots;

    static {
        if (STATS) {
            Runtime.getRuntime().addShutdownHook(new Thread(() ->
                    System.err.println("refcount: " + zeros + " zeros, " + roots + " possible roots")));
        }
    }

    /**
     * The count audit (-Dphp.rc.audit=true, Census.audit): at the script's end, before the shutdown functions, each
     * object's count against its holders. when: "end of script" or "exit" (frames still running).
     */
    public static void audit(pl.codeloom.php.PhpContext context, String when) {
        if (Census.AUDIT) {
            Census.audit(context, when);
        }
    }

    /** Whether the translator emits an audit point at each statement's end (-Dphp.rc.audit.every=n). */
    public static final boolean AUDIT_POINTS = Census.AUDIT_POINTS;

    /** A frame's layout for the count audit: its first locals are PHP variables, then the hidden ones. */
    public interface Variables {
        int variableCount();

        /**
         * A suspended generator's frame is freed (php-src's zend_generator_close): its variables give up what they
         * hold, and so do its running foreach loops.
         */
        void releaseFrame(com.oracle.truffle.api.frame.VirtualFrame frame,
                          com.oracle.truffle.api.bytecode.BytecodeNode bytecode);
    }

    /**
     * A loop's state that holds a count (foreach over an object: php-src's FE_RESET copy or the iterator it made),
     * for the count audit.
     */
    public interface Holder {
        Object held();
    }

    /**
     * An owning temporary sent as an argument (audit points only): php-src moves it into the callee (SEND_VAL), so
     * the hidden local still keeping it here isn't a holder php-src has, until it is released ({@link #unsent}).
     */
    public static void sent(Object value) {
        if (value instanceof PhpObject || value instanceof PhpClosure) {
            Census.sent(value);
        }
    }

    public static void unsent(Object value) {
        if (value instanceof PhpObject || value instanceof PhpClosure) {
            Census.unsent(value);
        }
    }

    /** A statement ended: every n-th time, the count audit with the frames as roots. */
    public static void auditPoint(com.oracle.truffle.api.frame.MaterializedFrame frame,
                                  com.oracle.truffle.api.bytecode.BytecodeNode bytecode) {
        Census.auditPoint(frame, bytecode);
    }

    /**
     * A holder in a built-in's Java state takes the value (a handler, an autoloader, a shutdown function or its
     * argument): php-src keeps a zval copy there, which counts an array, an object or a closure.
     */
    public static void hold(Object value) {
        if (value instanceof PhpArray array) {
            array.addHolder();
        } else {
            inc(value);
        }
    }

    /** Such a holder lets the value go. */
    public static void unhold(Object value) {
        if (value instanceof PhpArray array) {
            array.removeHolder();
        } else {
            dec(value);
        }
    }

    /**
     * The result of PHP code runtime code called (a callback, a magic or ArrayAccess method): the count it owns is
     * given up when the built-in running now returns (BuiltinRootNode drains the context's pending releases above
     * its mark), or at the request's end, as php-src's internal code frees a retval once used. Returns value.
     */
    public static Object pending(Object value) {
        if (value instanceof PhpObject || value instanceof PhpClosure
                || value instanceof PhpArray array && array.isTemporary()) {
            pl.codeloom.php.PhpContext.get(null).pendingReleases().add(value);
        }
        return value;
    }

    /**
     * Runtime code is done with a result it called for (a callback's whose value it discards or only tests or
     * converts): its own count goes now, as php-src's zval_ptr_dtor of the retval right after use. Returns value.
     */
    public static Object done(Object value) {
        if (value instanceof PhpObject || value instanceof PhpClosure || value instanceof PhpArray) {
            takePending(value, true);
        }
        return value;
    }

    /**
     * A result PHP code reads as a value (offsetGet, __get, a get hook): when something else holds it too, its own
     * count goes now, so the read is the borrow an element's or a property's is; a value only the result holds (a
     * fresh object) stays pending. Returns value.
     */
    public static Object borrowed(Object value) {
        if (value instanceof PhpObject object && object.refcount() > 1
                || value instanceof PhpClosure closure && closure.refcount() > 1) {
            takePending(value, false);
        }
        return value;
    }

    /** Removes value's most recent pending release: given up (released), or moved to the caller (disowned). */
    @TruffleBoundary
    private static void takePending(Object value, boolean release) {
        java.util.ArrayList<Object> pending = pl.codeloom.php.PhpContext.get(null).pendingReleases();
        for (int i = pending.size() - 1; i >= 0 && i >= pending.size() - 16; i--) {
            if (pending.get(i) == value) {
                pending.remove(i);
                if (value instanceof PhpArray array) {
                    if (release) {
                        array.releaseTemporary();
                    }
                } else if (release) {
                    dec(value);
                } else if (value instanceof PhpObject object) {
                    object.disown();
                } else {
                    ((PhpClosure) value).disown();
                }
                return;
            }
        }
    }

    /** A temporary array runtime code makes and passes on (forward_static_call's arguments): pending too. */
    public static PhpArray pendingTemporary(PhpArray array) {
        pending(array);
        return array;
    }

    /** An argument's temporary (StoreTemp): its count moves into the call. Inline: two field writes. */
    public static void disown(Object value) {
        if (value instanceof PhpObject object) {
            object.disown();
        } else if (value instanceof PhpClosure closure) {
            closure.disown();
        }
    }

    /**
     * An argument's temporary at its statement's end (or a catch's, or the function's): the call took the count
     * StoreTemp gave up, so the local holds none. One no call took (an exception in a later argument, or before the
     * callee bound its parameters) is at zero and not yet destroyed: php-src frees it with the unfinished call.
     */
    public static void moved(Object value) {
        if (value instanceof PhpObject object && object.refcount() == 0 && !object.isDestructorCalled()
                || value instanceof PhpClosure closure && closure.refcount() == 0 && !closure.isFreed()) {
            untaken(value);
        } else if (!pl.codeloom.php.PhpContext.get(null).movedToBuiltins().isEmpty()) {
            // noted for a built-in that ran without BuiltinRootNode (a call site's intrinsic: array_push...):
            // the note goes with the statement
            forgetMoved(value);
        }
    }

    @TruffleBoundary
    private static void forgetMoved(Object value) {
        java.util.ArrayList<Object> moved = pl.codeloom.php.PhpContext.get(null).movedToBuiltins();
        int at = indexOfIdentical(moved, value);
        if (at >= 0) {
            moved.remove(at);
        }
    }

    @TruffleBoundary
    private static void untaken(Object value) {
        // sent to a built-in whose call never started: no longer waiting for it
        java.util.ArrayList<Object> moved = pl.codeloom.php.PhpContext.get(null).movedToBuiltins();
        int at = indexOfIdentical(moved, value);
        if (at >= 0) {
            moved.remove(at);
        }
        inc(value);
        dec(value);
    }

    /**
     * A value sent as an argument (Send*): one at zero sent to a built-in is an argument's temporary moved into the
     * call (StoreTemp; a variable's value has its variable's count), noted for the built-in's call to take
     * (takeMoved). A user function's parameter takes it itself: for a site's constant callee this folds away.
     */
    public static void toBuiltin(PhpFunction function, Object value) {
        if (function.getBuiltin() != null && (value instanceof PhpObject object && object.refcount() == 0
                || value instanceof PhpClosure closure && closure.refcount() == 0
                || value instanceof PhpArray array && array.isTemporary())) {
            movedToBuiltin(value);
        }
    }

    @TruffleBoundary
    private static void movedToBuiltin(Object value) {
        pl.codeloom.php.PhpContext.get(null).movedToBuiltins().add(value);
    }

    /**
     * A built-in's call takes the arguments moved into it (toBuiltin): counted while it runs and released after it
     * returns, in order (php-src's zend_vm_stack_free_args after the internal function), through the pending
     * releases from the call's mark (BuiltinRootNode). Pushed last to first: the releases go last in, first out.
     * Moved values another call's arguments still wait with (a later argument's call) stay noted.
     */
    @TruffleBoundary
    public static void takeMoved(Object[] arguments, java.util.ArrayList<Object> pending,
                                 java.util.ArrayList<Object> moved) {
        for (int i = arguments.length - 1; i >= PhpFunction.ARG_FIRST; i--) {
            Object argument = arguments[i];
            int at = indexOfIdentical(moved, argument);
            if (at < 0) {
                continue;
            }
            moved.remove(at);
            if (argument instanceof PhpObject object && object.refcount() == 0 && !object.isDestructorCalled()
                    || argument instanceof PhpClosure closure && closure.refcount() == 0 && !closure.isFreed()) {
                inc(argument);
                pending.add(argument);
            } else if (argument instanceof PhpArray array && array.isTemporary()) {
                // a TMP array: freed after the call unless something took it (releasePending)
                pending.add(argument);
            }
        }
    }

    private static int indexOfIdentical(java.util.ArrayList<Object> list, Object value) {
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i) == value) {
                return i;
            }
        }
        return -1;
    }

    /** An argument the call moved in (StoreTemp): at zero, not yet destroyed. */
    public static boolean isMoved(Object argument) {
        return argument instanceof PhpObject object && object.refcount() == 0 && !object.isDestructorCalled()
                || argument instanceof PhpClosure closure && closure.refcount() == 0 && !closure.isFreed();
    }

    /** TakeExtraArguments: the arguments from `from` on that the call moved in, counted, or null if none. */
    @TruffleBoundary
    public static Object[] takeExtras(Object[] arguments, int from) {
        java.util.ArrayList<Object> taken = null;
        for (int i = from; i < arguments.length; i++) {
            Object argument = arguments[i];
            if (isMoved(argument)) {
                inc(argument);
                if (taken == null) {
                    taken = new java.util.ArrayList<>();
                }
                taken.add(argument);
            }
        }
        return taken == null ? null : taken.toArray();
    }

    /** The extra arguments a frame took, released in order at its end. */
    @TruffleBoundary
    public static void releaseExtras(Object[] taken) {
        for (Object argument : taken) {
            dec(argument);
        }
    }

    @TruffleBoundary
    public static void releasePending(java.util.ArrayList<Object> pending, int mark) {
        releasePending(pending, mark, null);
    }

    /** As releasePending, but for a TMP array the call returned (array_values of a list...): the result has it. */
    @TruffleBoundary
    public static void releasePending(java.util.ArrayList<Object> pending, int mark, Object result) {
        while (pending.size() > mark) {
            Object value = pending.remove(pending.size() - 1);
            if (value instanceof PhpArray array) {
                if (array != result) {
                    array.releaseTemporary();
                }
            } else {
                dec(value);
            }
        }
    }

    /** A slot or an owning temporary takes the value. */
    public static void inc(Object value) {
        if (value instanceof PhpObject object) {
            object.addRef();
        } else if (value instanceof PhpClosure closure) {
            closure.addRef();
        }
    }

    /** A slot or an owning temporary gives the value up. */
    public static void dec(Object value) {
        if (value instanceof PhpObject object) {
            object.release();
        } else if (value instanceof PhpClosure closure) {
            closure.release();
        }
    }

    /**
     * A site's releases, profiled (from the B prototype's RefCounts.Releases): the zero and the possible-root events
     * are a deoptimization until the site first meets them, then a call behind a boundary, so a site whose objects
     * are never released to zero compiles no call there. A node keeps one in a final field.
     */
    public static final class Releases {
        private static final int ZERO_SEEN = 1;
        private static final int ROOT_SEEN = 1 << 1;
        @CompilationFinal private int seen;

        public static Releases create() {
            return new Releases();
        }

        public void release(PhpObject object) {
            int count = object.decrement();
            if (count == 0) {
                met(ZERO_SEEN);
                zero(object);
            } else if (object.unbuffered()) {
                met(ROOT_SEEN);
                possibleRoot(object);
            }
        }

        private void met(int bit) {
            if ((seen & bit) == 0) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                seen |= bit;
            }
        }
    }

    /** A decrement's event: the count reached zero, or else the object became a possible root. */
    static void released(PhpObject object, int count) {
        if (count == 0) {
            zero(object);
        } else {
            possibleRoot(object);
        }
    }

    /** The count reached zero: php-src's zend_objects_store_del (the destructor, then the properties' release). */
    @TruffleBoundary
    static void zero(PhpObject object) {
        if (STATS) {
            zeros++;
            if (TRACE) {
                System.err.println("refcount: zero " + object.getPhpClass().getName() + " #" + object.id());
            }
        }
        if (Census.enabled() && Census.reachableAtZero(object)) {
            // a slot that holds it wasn't counted: destroying it would be php-src's behaviour only by accident
            return;
        }
        if (DESTRUCTORS) {
            destroy(object);
        }
    }

    private static final boolean TRACE = Boolean.getBoolean("php.rc.trace");

    /**
     * Destructors at zero (-Dphp.rc.destructors=true, wiki/destructors.md prototype step 2), off by default until
     * the counts are verified on WordPress: what zend_objects_store_del does.
     */
    private static final boolean DESTRUCTORS = Boolean.getBoolean("php.rc.destructors");

    /**
     * zend_objects_store_del: the destructor runs once, with the object held for its duration (GC_ADDREF); an object
     * the destructor stored somewhere (resurrected) lives on, else its properties are released, which can take what
     * they held to zero in turn (zend_object_std_dtor). An exception the destructor throws goes on after the release.
     */
    private static void destroy(PhpObject object) {
        RuntimeException thrown = null;
        PhpGenerator generator = object.getInternal() instanceof PhpGenerator g ? g : null;
        if (!object.markDestructorCalled()
                && (generator != null || object.getPhpClass().findMethod("__destruct") != null)) {
            object.addRef();
            if (Census.AUDIT) {
                Census.destroying(object, true);
            }
            try {
                if (generator != null) {
                    // zend_generator_dtor_storage: a generator suspended in a try runs its finally blocks; its
                    // frame is freed (zend_generator_close)
                    generator.destroy();
                } else {
                    dec(object.callMethodOwned(null, "__destruct"));
                }
            } catch (RuntimeException e) {
                thrown = e;
            } finally {
                if (Census.AUDIT) {
                    Census.destroying(object, false);
                }
            }
            if (object.decrement() > 0) {
                // resurrected: freed when its count next reaches zero, without its destructor
                if (thrown != null) {
                    throw thrown;
                }
                return;
            }
        }
        if (!object.markFreed()) {
            freeProperties(object);
        }
        if (thrown != null) {
            throw thrown;
        }
    }

    /**
     * php-src's zend_object_std_dtor: each property gives up what it holds, the declared ones first in their order,
     * then the dynamic ones (the order of propertyTable, without making it: no names are needed).
     */
    private static void freeProperties(PhpObject object) {
        PhpObject owner = object.propertyOwner();
        PhpClass phpClass = owner.getPhpClass();
        com.oracle.truffle.api.object.Shape shape = owner.getShape();
        FreeLayout layout = phpClass.freeLayout;
        if (layout == null || layout.shape != shape) {
            layout = FreeLayout.of(phpClass, shape);
            phpClass.freeLayout = layout;
        }
        for (com.oracle.truffle.api.object.Location location : layout.locations) {
            freeProperty(location.get(owner, shape));
        }
    }

    /**
     * The property locations of a shape in freeProperties' order: the declared properties in their order, then the
     * others in the shape's (a class's objects mostly share one shape: no key lookups per object).
     */
    record FreeLayout(com.oracle.truffle.api.object.Shape shape, com.oracle.truffle.api.object.Location[] locations) {
        static FreeLayout of(PhpClass phpClass, com.oracle.truffle.api.object.Shape shape) {
            java.util.List<com.oracle.truffle.api.object.Location> locations = new java.util.ArrayList<>();
            java.util.Set<Object> declared = new java.util.HashSet<>();
            for (PhpClass.PropertyDecl decl : phpClass.getProperties()) {
                com.oracle.truffle.api.object.Property property = shape.getProperty(decl.name());
                if (property != null && declared.add(decl.name())) {
                    locations.add(property.getLocation());
                }
            }
            for (com.oracle.truffle.api.object.Property property : shape.getPropertyList()) {
                // hidden ones (internal state) stay out, as getKeyArray leaves them out
                if (!property.isHidden() && !declared.contains(property.getKey()) && !(property.getKey() instanceof String name
                        && phpClass.isDeclaredProperty(name))) {
                    locations.add(property.getLocation());
                }
            }
            return new FreeLayout(shape, locations.toArray(new com.oracle.truffle.api.object.Location[0]));
        }
    }

    private static void freeProperty(Object value) {
        if (value instanceof PhpObject held) {
            held.release();
        } else if (value instanceof PhpClosure closure) {
            closure.release();
        } else if (value instanceof PhpArray array) {
            array.removeHolder();
        } else if (value instanceof PhpRef ref) {
            ref.unbind();
        }
    }

    /**
     * A decrement left the count above zero: php-src's gc_possible_root, which buffers the object once. Inline (a
     * flag): an object passed to an out-of-line call escapes, which kept partial evaluation from removing the
     * allocation of every object released once. The cycle collector (step 6) finds the flagged objects some other
     * way than a buffer filled here.
     */
    static void possibleRoot(PhpObject object) {
        object.buffered();
        if (STATS) {
            rootSeen();
        }
    }

    /** PhpObject.release's possible root, for -Dphp.rc.stats. */
    static void rootStat() {
        if (STATS) {
            rootSeen();
        }
    }

    @TruffleBoundary
    private static void rootSeen() {
        roots++;
    }
}
