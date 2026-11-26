package pl.codeloom.php.runtime;

import java.util.Arrays;

import static pl.codeloom.php.runtime.Values.*;

/**
 * Open-world WordPress WP_Hook + plugin.php implementation:
 * - Explicit PhpRequestContext parameter 0 on every function and method
 * - Last-use transfer of positional arguments and live $wp_filter['all'] re-checks across user code
 * - Open-world callable dispatch
 * - Real D17 exact refcounting including callback return ownership & exception-safe release
 * - Real ByteStr keys & values everywhere
 */
final class HooksOpenWorld {
    static final ByteStr ALL_KEY = ByteStr.of("all");
    static final ByteStr FUNCTION_KEY = ByteStr.of("function");
    static final ByteStr ACCEPTED_ARGS_KEY = ByteStr.of("accepted_args");
    static final ByteStr SPL_PREFIX = ByteStr.of("spl_object_id:");
    static final ByteStr DOUBLE_COLON = ByteStr.of("::");
    static final ByteStr BENCH_TITLE = ByteStr.of("bench_title");
    static final ByteStr BENCH_SEEN = ByteStr.of("bench_seen");
    static final ByteStr BENCH_NESTED = ByteStr.of("bench_nested");
    static final ByteStr TRIM = ByteStr.of("trim");
    static final ByteStr BENCH_UPPER_FIRST = ByteStr.of("bench_upper_first");
    static final ByteStr BENCH_CALLBACKS_CLS = ByteStr.of("Bench_Callbacks");
    static final ByteStr SUFFIX_METHOD = ByteStr.of("suffix");
    static final ByteStr WRAP_METHOD = ByteStr.of("wrap");
    static final ByteStr SUFFIX_LIT = ByteStr.of("|m");
    static final ByteStr LT = ByteStr.of("<");
    static final ByteStr GT = ByteStr.of(">");
    static final ByteStr TWO_SPACES = ByteStr.of("  ");
    static final ByteStr ONE_SPACE = ByteStr.of(" ");
    static final ByteStr CONST_BENCH_TAG = ByteStr.of("BENCH_TAG");

    private static final PhpArray WRAP_CB = literal(PhpArray.list(BENCH_CALLBACKS_CLS, WRAP_METHOD));

    static final class BenchCallbacks extends PhpObject {
        BenchCallbacks(PhpRequestContext ctx, PhpClass cls) {
            super(ctx, cls);
        }

        ByteStr suffix(ByteStr v) {
            return v.concat(SUFFIX_LIT);
        }

        static ByteStr wrap(ByteStr v, long i) {
            return ByteStr.concat3(LT, ByteStr.ofLong(i), GT).concat(v);
        }
    }

    static void registerHooksDomain(PhpRequestContext ctx, boolean overridePluggable, ByteStr tag) {
        ctx.defineConstant(CONST_BENCH_TAG, tag);
        PhpClass benchCls = ctx.defineClass(BENCH_CALLBACKS_CLS);
        benchCls.addMethod(SUFFIX_METHOD, new PhpInstanceMethod() {
            @Override Object invoke(PhpRequestContext c, PhpObject self, PhpArray args) {
                return invoke1(c, self, args.get(0L));
            }
            @Override Object invoke1(PhpRequestContext c, PhpObject self, Object value) {
                return ((BenchCallbacks) self).suffix((ByteStr) value);
            }
            @Override Object invoke2(PhpRequestContext c, PhpObject self, Object value, Object extra) {
                return invoke1(c, self, value);
            }
        });
        benchCls.addStaticMethod(WRAP_METHOD, new PhpCallable() {
            @Override Object invoke(PhpRequestContext c, PhpArray args) {
                if (args.size() < 2) throw new PhpError("Too few arguments to Bench_Callbacks::wrap");
                return invoke2(c, args.get(0L), args.get(1L));
            }
            @Override Object invoke1(PhpRequestContext c, Object value) {
                throw new PhpError("Too few arguments to Bench_Callbacks::wrap");
            }
            @Override Object invoke2(PhpRequestContext c, Object value, Object i) {
                return BenchCallbacks.wrap((ByteStr) value, (long) i);
            }
        });

        ctx.registerFunction(BENCH_UPPER_FIRST, overridePluggable ? new UpperFirst() {
            @Override Object invoke1(PhpRequestContext c, Object value) {
                return ((ByteStr) c.getConstant(CONST_BENCH_TAG)).concat(((ByteStr) value).ucfirst());
            }
        } : new UpperFirst());
    }

    private static class UpperFirst extends PhpCallable {
        @Override Object invoke(PhpRequestContext c, PhpArray args) { return invoke1(c, args.get(0L)); }
        @Override Object invoke1(PhpRequestContext c, Object value) { return ((ByteStr) value).ucfirst(); }
        @Override Object invoke2(PhpRequestContext c, Object value, Object extra) { return invoke1(c, value); }
    }

    static ByteStr uniqueId(Object cb) {
        if (cb instanceof ByteStr name) return name;
        if (cb instanceof PhpClosure c) return SPL_PREFIX.concat(ByteStr.ofLong(c.id));
        if (cb instanceof PhpObject o) return SPL_PREFIX.concat(ByteStr.ofLong(o.id));
        if (cb instanceof PhpArray pair && pair.get(1L) instanceof ByteStr method) {
            Object target = pair.get(0L);
            if (target instanceof PhpObject o) return ByteStr.ofLong(o.id).concat(method);
            if (target instanceof ByteStr cls) return ByteStr.concat3(cls, DOUBLE_COLON, method);
        }
        return null;
    }

    static final class Hook extends PhpObject {
        PhpArray callbacks = owned(new PhpArray());
        PhpArray priorities = owned(new PhpArray());
        final PhpArray iterations = owned(new PhpArray());
        final PhpArray currentPriority = owned(new PhpArray());
        long nesting;
        boolean doingAction;

        Hook(PhpRequestContext ctx) {
            super(ctx, ctx.wpHookClass);
        }

        @Override
        void freeProperties() {
            callbacks.removeHolder();
            priorities.removeHolder();
            iterations.removeHolder();
            currentPriority.removeHolder();
        }

        @Override
        void visitChildren(ChildVisitor visitor) {
            visitor.visit(callbacks);
            visitor.visit(priorities);
            visitor.visit(iterations);
            visitor.visit(currentPriority);
        }

        void refreshPriorities(boolean sort) {
            Long[] keys = new Long[callbacks.size()];
            int i = 0;
            for (int p = callbacks.firstPosition(); p >= 0; p = callbacks.nextPosition(p)) {
                keys[i++] = (Long) callbacks.keyAt(p);
            }
            if (sort) {
                Arrays.sort(keys);
                PhpArray ordered = owned(new PhpArray());
                for (long key : keys) ordered.put(key, callbacks.get(key));
                callbacks.removeHolder();
                callbacks = ordered;
            }
            priorities.removeHolder();
            priorities = owned(new PhpArray());
            for (long key : keys) priorities.appendLong(key);
        }

        void add(Object cb, Long priority, long accepted) {
            long key = priority == null ? 0L : priority;
            ByteStr id = uniqueId(cb);
            if (id == null) return;
            boolean existed = callbacks.containsKey(key);
            callbacks = writable(callbacks);
            callbacks.arrayForWrite(key).put(ArrayKey.ofString(id),
                    assoc(FUNCTION_KEY, cb, ACCEPTED_ARGS_KEY, accepted));
            refreshPriorities(!existed && callbacks.size() > 1);
            if (nesting > 0) resort(key, existed);
        }

        void removeAll(Object priority) {
            if (callbacks.size() == 0) return;
            if (priority == Boolean.FALSE) {
                callbacks.removeHolder();
                callbacks = owned(new PhpArray());
                priorities.removeHolder();
                priorities = owned(new PhpArray());
            } else if (callbacks.containsKey(priority)) {
                callbacks = writable(callbacks);
                callbacks.remove(priority);
                refreshPriorities(false);
            }
            if (nesting > 0) resort(null, false);
        }

        boolean hasFilters() {
            for (int p = callbacks.firstPosition(); p >= 0; p = callbacks.nextPosition(p)) {
                if (((PhpArray) callbacks.valueAt(p)).size() != 0) return true;
            }
            return false;
        }

        static Object current(PhpArray a) {
            int p = a.pointer();
            return a.isValidPosition(p) ? a.valueAt(p) : Boolean.FALSE;
        }

        static Object next(PhpArray a) {
            if (a.isValidPosition(a.pointer())) a.setPointer(a.nextPosition(a.pointer()));
            return current(a);
        }

        void resort(Long added, boolean existed) {
            for (int p = iterations.firstPosition(); p >= 0; p = iterations.nextPosition(p)) {
                Object level = iterations.keyAt(p);
                if (priorities.size() == 0) {
                    iterations.put(level, priorities);
                    continue;
                }
                Object old = current((PhpArray) iterations.get(level));
                if (old == Boolean.FALSE) continue;
                iterations.put(level, priorities);
                PhpArray a = iterations.arrayForWrite(level);
                if ((long) old < (long) priorities.get(0L)) {
                    a.prepend(old);
                    continue;
                }
                while ((long) current(a) < (long) old) {
                    if (next(a) == Boolean.FALSE) break;
                }
                if (added != null && added.equals(currentPriority.get(level)) && !existed) {
                    int pos = current(a) == Boolean.FALSE ? a.lastPosition() : a.previousPosition(a.pointer());
                    a.setPointer(pos);
                    Object prev = current(a);
                    if (prev == Boolean.FALSE) a.setPointer(a.firstPosition());
                    else if (!added.equals(prev)) next(a);
                }
            }
        }

        private Object callEntry(PhpRequestContext ctx, PhpArray entry, PhpArray args, int numArgs) {
            long take = (long) entry.get(ACCEPTED_ARGS_KEY);
            if (take != 0 && take >= numArgs) take = Long.MAX_VALUE;
            return ownReturned(ctx.callSlice(entry.get(FUNCTION_KEY), args, take));
        }

        /** The local $value owns the last callback result, also during the next action callback. */
        Object apply(PhpRequestContext ctx, Object initialValue, PhpArray callerArgs) {
            if (callbacks.size() == 0) {
                retainValue(initialValue);
                return initialValue;
            }
            return applyOwnedArgs(ctx, initialValue, owned(callerArgs));
        }

        /** Consumes one holder, including when there are no callbacks or user code throws. */
        Object applyOwnedArgs(PhpRequestContext ctx, Object initialValue, PhpArray args) {
            Object result = null;
            Throwable primary = null;
            try {
                return result = applyFrame(ctx, initialValue, args);
            } catch (RuntimeException | Error e) {
                primary = e;
                throw e;
            } finally {
                releaseCallArgs(args, null, result, primary);
            }
        }

        /** The caller owns frame through its epilogue. Only a separated local copy needs a holder here. */
        private Object applyFrame(PhpRequestContext ctx, Object initialValue, PhpArray frame) {
            Object value = initialValue;
            retainValue(value);
            if (callbacks.size() == 0) return value;
            PhpArray args = frame;
            Long level = nesting++;
            Throwable failure = null;
            try {
                iterations.put(level, priorities);
                int numArgs = args.size();
                do {
                    Object priority = current((PhpArray) iterations.get(level));
                    if (priority == Boolean.FALSE) break;
                    currentPriority.put(level, priority);
                    PhpArray group = owned((PhpArray) callbacks.get(priority));
                    Throwable callbackFailure = null;
                    try {
                        for (int p = group.firstPosition(); p >= 0; p = group.nextPosition(p)) {
                            PhpArray entry = (PhpArray) group.valueAt(p);
                            if (!doingAction) {
                                if (args.isShared()) {
                                    PhpArray copy = owned(args.copy());
                                    if (args != frame) args.removeHolder();
                                    args = copy;
                                }
                                args.put(0L, value);
                            }
                            Object previous = value;
                            value = callEntry(ctx, entry, args, numArgs);
                            releaseReturned(previous);
                        }
                    } catch (RuntimeException | Error e) {
                        callbackFailure = e;
                    }
                    throwCleanupFailure(releaseWithThrown(group, callbackFailure));
                } while (next(iterations.arrayForWrite(level)) != Boolean.FALSE);
            } catch (RuntimeException | Error e) {
                failure = e;
            } finally {
                iterations.remove(level);
                currentPriority.remove(level);
                --nesting;
                if (args != frame) failure = releaseWithThrown(args, failure);
            }
            if (failure != null) {
                discardResult(value, failure);
                throwCleanupFailure(failure);
            }
            return value;
        }

        void action(PhpRequestContext ctx, PhpArray args) {
            actionOwnedArgs(ctx, owned(args));
        }

        void actionOwnedArgs(PhpRequestContext ctx, PhpArray args) {
            Throwable failure = null;
            try {
                actionFrame(ctx, args);
            } catch (RuntimeException | Error e) {
                failure = e;
            }
            throwCleanupFailure(releaseWithThrown(args, failure));
        }

        private void actionFrame(PhpRequestContext ctx, PhpArray args) {
            doingAction = true;
            try {
                releaseReturned(applyFrame(ctx, ByteStr.EMPTY, args));
            } finally {
                if (nesting == 0) doingAction = false;
            }
        }

        void doAllHook(PhpRequestContext ctx, PhpArray allArgs) {
            Long level = nesting++;
            iterations.put(level, priorities);
            try {
                do {
                    Object priority = current((PhpArray) iterations.get(level));
                    if (priority == Boolean.FALSE) break;
                    PhpArray group = owned((PhpArray) callbacks.get(priority));
                    Throwable failure = null;
                    try {
                        for (int p = group.firstPosition(); p >= 0; p = group.nextPosition(p)) {
                            PhpArray entry = (PhpArray) group.valueAt(p);
                            Object ret = ctx.call(entry.get(FUNCTION_KEY), allArgs);
                            releaseReturned(ret);
                        }
                    } catch (RuntimeException | Error e) {
                        failure = e;
                    }
                    throwCleanupFailure(releaseWithThrown(group, failure));
                } while (next(iterations.arrayForWrite(level)) != Boolean.FALSE);
            } finally {
                iterations.remove(level);
                --nesting;
            }
        }
    }

    static boolean addFilter(PhpRequestContext ctx, ByteStr name, Object cb, long priority, long accepted) {
        Hook hook = (Hook) ctx.wpFilter.get(name);
        if (hook == null) {
            hook = new Hook(ctx);
            ctx.wpFilter.put(name, hook);
            hook.release();
        }
        hook.add(cb, priority, accepted);
        return true;
    }

    static boolean removeAllFilters(PhpRequestContext ctx, ByteStr name) {
        Hook hook = (Hook) ctx.wpFilter.get(name);
        if (hook != null) {
            hook.removeAll(Boolean.FALSE);
            if (!hook.hasFilters()) {
                ctx.wpFilter.remove(name);
            }
        }
        return true;
    }

    private static void countCall(PhpArray counts, ByteStr name) {
        Object old = counts.get(name);
        counts.put(name, old == null ? 1L : (long) old + 1L);
    }

    private static void appendExtras(PhpArray args, int count, Object first, Object[] extra) {
        if (extra != null) {
            for (Object arg : extra) args.appendCopied(arg);
        } else if (count == 1) {
            args.appendCopied(first);
        }
    }

    private static void callAll(PhpRequestContext ctx, Hook allHook, ByteStr name, Object value,
                                boolean filter, int count, Object first, Object[] extra) {
        PhpArray allArgs = owned(PhpArray.ofCapacity(count + (filter ? 2 : 1)));
        Throwable failure = null;
        allHook.addRef();
        try {
            allArgs.append(name);
            if (filter) allArgs.append(value);
            appendExtras(allArgs, count, first, extra);
            allHook.doAllHook(ctx, allArgs);
        } catch (RuntimeException | Error e) {
            failure = e;
        }
        failure = releaseWithThrown(allHook, failure);
        throwCleanupFailure(releaseWithThrown(allArgs, failure));
    }

    static Object applyFilters(PhpRequestContext ctx, ByteStr name, Object value) {
        return dispatchHook(ctx, name, value, true, 0, null, null);
    }

    static Object applyFilters(PhpRequestContext ctx, ByteStr name, Object value, Object extra1) {
        return dispatchHook(ctx, name, value, true, 1, extra1, null);
    }

    static Object applyFilters(PhpRequestContext ctx, ByteStr name, Object value, Object... extra) {
        return dispatchHook(ctx, name, value, true, extra.length, null, extra);
    }

    static void doAction(PhpRequestContext ctx, ByteStr name) {
        dispatchHook(ctx, name, ByteStr.EMPTY, false, 0, null, null);
    }

    static void doAction(PhpRequestContext ctx, ByteStr name, Object extra1) {
        dispatchHook(ctx, name, ByteStr.EMPTY, false, 1, extra1, null);
    }

    static void doAction(PhpRequestContext ctx, ByteStr name, Object... extra) {
        dispatchHook(ctx, name, ByteStr.EMPTY, false, extra.length, null, extra);
    }

    private static PhpArray namedArgs(Object value, boolean filter, int count, Object first, Object[] extra) {
        PhpArray args = PhpArray.ofCapacity(count + (filter ? 1 : 0));
        if (filter || count == 0) args.appendCopied(value);
        appendExtras(args, count, first, extra);
        return owned(args);
    }

    /** Scalarized variadic parameters own their values until the named frame takes them or the call exits. */
    private static void retainExtras(int count, Object first, Object[] extra) {
        if (extra != null) {
            for (Object arg : extra) retainValue(arg);
        } else if (count == 1) retainValue(first);
    }

    private static Throwable releaseExtras(int count, Object first, Object[] extra, Throwable failure) {
        if (extra == null) {
            return count == 1 ? releaseWithThrown(first, failure) : failure;
        }
        for (Object arg : extra) {
            failure = releaseWithThrown(arg, failure);
        }
        return failure;
    }

    /** A filter returns an owning reference on both the named-hook and absent-hook paths. */
    private static Object dispatchHook(PhpRequestContext ctx, ByteStr name, Object value, boolean filter,
                                       int count, Object first, Object[] extra) {
        countCall(filter ? ctx.wpFilters : ctx.wpActions, name);
        retainValue(value);
        Object result = null;
        PhpArray args = null;
        Hook receiver = null;
        boolean pop = false;
        boolean heldExtras = false;
        Throwable failure = null;
        try {
            Hook allHook = (Hook) ctx.wpFilter.get(ALL_KEY);
            if (allHook != null) {
                if (extra != null) extra = extra.clone();
                retainExtras(count, first, extra);
                heldExtras = true;
                ctx.wpCurrentFilter.append(name);
                pop = true;
                callAll(ctx, allHook, name, value, filter, count, first, extra);
                pop = ctx.wpFilter.get(ALL_KEY) != null;
            }
            Hook hook = (Hook) ctx.wpFilter.get(name);
            if (hook == null) {
                if (!heldExtras) {
                    retainExtras(count, first, extra);
                    heldExtras = true;
                }
                result = value;
                value = null; // Transfer the input's owning reference to the caller.
            } else {
                if (!pop) ctx.wpCurrentFilter.append(name);
                pop = true;
                args = namedArgs(value, filter, count, first, extra);
                if (heldExtras) {
                    heldExtras = false;
                    throwCleanupFailure(releaseExtras(count, first, extra, null));
                }
                hook.addRef();
                receiver = hook;
                if (filter) result = hook.applyFrame(ctx, value, args);
                else hook.actionFrame(ctx, args);
            }
        } catch (RuntimeException | Error e) {
            failure = e;
        } finally {
            if (pop) ctx.wpCurrentFilter.pop();
            failure = releaseWithThrown(receiver, failure);
            failure = releaseWithThrown(value, failure);
            failure = releaseWithThrown(args, failure);
            if (heldExtras) failure = releaseExtras(count, first, extra, failure);
        }
        if (failure != null) {
            discardResult(result, failure);
            throwCleanupFailure(failure);
        }
        return result;
    }

    static PhpArray hooksKernel(PhpRequestContext ctx, PhpArray headings) {
        removeAllFilters(ctx, BENCH_TITLE);
        removeAllFilters(ctx, BENCH_SEEN);

        PhpClass benchCls = ctx.findClass(BENCH_CALLBACKS_CLS);
        BenchCallbacks object = new BenchCallbacks(ctx, benchCls);
        PhpRef seen = new PhpRef(0L);
        seen.bind();

        addFilter(ctx, BENCH_TITLE, TRIM, 10, 1);
        addFilter(ctx, BENCH_TITLE, BENCH_UPPER_FIRST, 5, 1);

        PhpArray methodCb = PhpArray.list(object, SUFFIX_METHOD);
        addFilter(ctx, BENCH_TITLE, methodCb, 20, 1);
        addFilter(ctx, BENCH_TITLE, WRAP_CB, 20, 2);

        PhpClosure collapseSpaces = new PhpClosure(ctx, null) {
            @Override
            Object invoke(PhpRequestContext c, PhpArray args) {
                return invoke1(c, args.get(0L));
            }
            @Override Object invoke1(PhpRequestContext c, Object value) {
                return ((ByteStr) value).replace(TWO_SPACES, ONE_SPACE);
            }
            @Override Object invoke2(PhpRequestContext c, Object value, Object extra) {
                return invoke1(c, value);
            }
        };
        addFilter(ctx, BENCH_TITLE, collapseSpaces, 30, 1);
        collapseSpaces.release();

        PhpClosure nestedHook = new PhpClosure(ctx, seen) {
            @Override
            Object invoke(PhpRequestContext c, PhpArray args) {
                if (args.size() < 2) throw new PhpError("Too few arguments to closure");
                return invoke2(c, args.get(0L), args.get(1L));
            }
            @Override Object invoke1(PhpRequestContext c, Object value) {
                throw new PhpError("Too few arguments to closure");
            }
            @Override Object invoke2(PhpRequestContext c, Object value, Object index) {
                ByteStr v = (ByteStr) value;
                long i = (long) index;
                doAction(c, BENCH_SEEN, i);
                return i % 7 == 0 ? applyFilters(c, BENCH_NESTED, v) : v;
            }
        };
        addFilter(ctx, BENCH_TITLE, nestedHook, 40, 2);
        nestedHook.release();

        PhpClosure seenAcc = new PhpClosure(ctx, seen) {
            @Override
            Object invoke(PhpRequestContext c, PhpArray args) {
                return invoke1(c, args.get(0L));
            }
            @Override Object invoke1(PhpRequestContext c, Object value) {
                captureRef.set((long) captureRef.get() + (long) value);
                return PhpNull.NULL;
            }
            @Override Object invoke2(PhpRequestContext c, Object value, Object extra) {
                return invoke1(c, value);
            }
        };
        addFilter(ctx, BENCH_SEEN, seenAcc, 10, 1);
        seenAcc.release();

        PhpArray out = new PhpArray();
        for (int round = 0; round < 20; round++) {
            for (int p = headings.firstPosition(); p >= 0; p = headings.nextPosition(p)) {
                out.append(applyFilters(ctx, BENCH_TITLE, headings.valueAt(p), headings.keyAt(p)));
            }
        }
        out.append(seen.get());

        object.release();
        seen.unbind();
        return out;
    }
}
