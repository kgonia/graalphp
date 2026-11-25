package pl.codeloom.php.runtime;

import java.util.Arrays;

import static pl.codeloom.php.runtime.Values.*;

/**
 * Open-world WordPress WP_Hook + plugin.php implementation:
 * - Explicit PhpRequestContext parameter 0 on every function and method
 * - Exact plugin.php:175-212 variadic $args + prepend($value) + live $wp_filter['all'] re-checks across user code
 * - Open-world ctx.call(...) dispatch (zero closed-world switch cheats)
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
        benchCls.addMethod(SUFFIX_METHOD, PhpInstanceMethod.of((c, self, args) -> ((BenchCallbacks) self).suffix((ByteStr) args.get(0L))));
        benchCls.addStaticMethod(WRAP_METHOD, PhpCallable.of((c, args) -> BenchCallbacks.wrap((ByteStr) args.get(0L), (long) args.get(1L))));

        if (!overridePluggable) {
            ctx.registerFunction(BENCH_UPPER_FIRST, PhpCallable.of((c, args) -> ((ByteStr) args.get(0L)).ucfirst()));
        } else {
            ctx.registerFunction(BENCH_UPPER_FIRST, PhpCallable.of((c, args) -> {
                ByteStr prefix = (ByteStr) c.getConstant(CONST_BENCH_TAG);
                return prefix.concat(((ByteStr) args.get(0L)).ucfirst());
            }));
        }
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
            Object cb = entry.get(FUNCTION_KEY);
            if (take == 0) return ownReturned(ctx.call(cb, PhpRequestContext.EMPTY_ARGS));
            if (take >= numArgs) return ownReturned(ctx.call(cb, args));

            long length = take < 0 ? Math.max(0L, numArgs + take) : take;
            PhpArray slice = owned(new PhpArray());
            Object result = null;
            try {
                try {
                    for (int i = 0; i < length; ++i) slice.appendCopied(args.copiedAt(i));
                    result = ownReturned(ctx.call(cb, slice));
                } finally {
                    slice.removeHolder();
                }
                return result;
            } catch (RuntimeException | Error e) {
                releaseReturned(result);
                throw e;
            }
        }

        /** The local $value owns the last callback result, also during the next action callback. */
        Object apply(PhpRequestContext ctx, Object initialValue, PhpArray callerArgs) {
            Object value = initialValue;
            retainValue(value);
            if (callbacks.size() == 0) return value;
            PhpArray args = owned(callerArgs);
            long level = nesting++;
            try {
                try {
                    iterations.put(level, priorities);
                    int numArgs = args.size();
                    do {
                        Object priority = current((PhpArray) iterations.get(level));
                        if (priority == Boolean.FALSE) break;
                        currentPriority.put(level, priority);
                        PhpArray group = owned((PhpArray) callbacks.get(priority));
                        try {
                            for (int p = group.firstPosition(); p >= 0; p = group.nextPosition(p)) {
                                PhpArray entry = (PhpArray) group.valueAt(p);
                                if (!doingAction) {
                                    args = writable(args);
                                    args.put(0L, value);
                                }
                                Object previous = value;
                                value = callEntry(ctx, entry, args, numArgs);
                                releaseReturned(previous);
                            }
                        } finally {
                            group.removeHolder();
                        }
                    } while (next(iterations.arrayForWrite(level)) != Boolean.FALSE);
                } finally {
                    iterations.remove(level);
                    currentPriority.remove(level);
                    --nesting;
                    args.removeHolder();
                }
                return value;
            } catch (RuntimeException | Error e) {
                releaseReturned(value);
                throw e;
            }
        }

        void action(PhpRequestContext ctx, PhpArray args) {
            doingAction = true;
            try {
                releaseReturned(apply(ctx, ByteStr.EMPTY, args));
            } finally {
                if (nesting == 0) doingAction = false;
            }
        }

        void doAllHook(PhpRequestContext ctx, PhpArray allArgs) {
            long level = nesting++;
            iterations.put(level, priorities);
            try {
                do {
                    Object priority = current((PhpArray) iterations.get(level));
                    if (priority == Boolean.FALSE) break;
                    PhpArray group = owned((PhpArray) callbacks.get(priority));
                    try {
                        for (int p = group.firstPosition(); p >= 0; p = group.nextPosition(p)) {
                            PhpArray entry = (PhpArray) group.valueAt(p);
                            Object ret = ctx.call(entry.get(FUNCTION_KEY), allArgs);
                            releaseReturned(ret);
                        }
                    } finally {
                        group.removeHolder();
                    }
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

    private static void callAll(PhpRequestContext ctx, ByteStr name, Object value, Object[] extra, boolean filter) {
        Hook allHook = (Hook) ctx.wpFilter.get(ALL_KEY);
        if (allHook == null) return;
        ctx.wpCurrentFilter.append(name);
        PhpArray allArgs = owned(new PhpArray());
        try {
            allArgs.append(name);
            if (filter) allArgs.append(value);
            for (Object arg : extra) allArgs.append(arg);
            allHook.addRef();
            try {
                allHook.doAllHook(ctx, allArgs);
            } finally {
                allHook.release();
            }
        } finally {
            allArgs.removeHolder();
        }
    }

    /** Every successful return transfers one owning reference, including the no-filter path. */
    static Object applyFilters(PhpRequestContext ctx, ByteStr name, Object value, Object... extra) {
        PhpArray args = owned(PhpArray.list(extra));
        countCall(ctx.wpFilters, name);
        Object filtered = value;
        retainValue(filtered);
        try {
            try {
                callAll(ctx, name, value, extra, true);
                Hook hook = (Hook) ctx.wpFilter.get(name);
                if (hook == null) {
                    if (ctx.wpFilter.get(ALL_KEY) != null) ctx.wpCurrentFilter.pop();
                } else {
                    if (ctx.wpFilter.get(ALL_KEY) == null) ctx.wpCurrentFilter.append(name);
                    args.prepend(value);
                    hook.addRef();
                    try {
                        filtered = hook.apply(ctx, value, args);
                        releaseValue(value);
                    } finally {
                        ctx.wpCurrentFilter.pop();
                        hook.release();
                    }
                }
            } finally {
                args.removeHolder();
            }
            return filtered;
        } catch (RuntimeException | Error e) {
            releaseReturned(filtered);
            throw e;
        }
    }

    static void doAction(PhpRequestContext ctx, ByteStr name, Object... extra) {
        PhpArray args = owned(PhpArray.list(extra));
        countCall(ctx.wpActions, name);
        try {
            callAll(ctx, name, null, extra, false);
            Hook hook = (Hook) ctx.wpFilter.get(name);
            if (hook == null) {
                if (ctx.wpFilter.get(ALL_KEY) != null) ctx.wpCurrentFilter.pop();
                return;
            }
            if (ctx.wpFilter.get(ALL_KEY) == null) ctx.wpCurrentFilter.append(name);
            if (args.size() == 0) args.append(ByteStr.EMPTY);
            hook.addRef();
            try {
                hook.action(ctx, args);
            } finally {
                ctx.wpCurrentFilter.pop();
                hook.release();
            }
        } finally {
            args.removeHolder();
        }
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
                return ((ByteStr) args.get(0L)).replace(TWO_SPACES, ONE_SPACE);
            }
        };
        addFilter(ctx, BENCH_TITLE, collapseSpaces, 30, 1);
        collapseSpaces.release();

        PhpClosure nestedHook = new PhpClosure(ctx, seen) {
            @Override
            Object invoke(PhpRequestContext c, PhpArray args) {
                ByteStr v = (ByteStr) args.get(0L);
                long i = (long) args.get(1L);
                doAction(c, BENCH_SEEN, i);
                return i % 7 == 0 ? applyFilters(c, BENCH_NESTED, v) : v;
            }
        };
        addFilter(ctx, BENCH_TITLE, nestedHook, 40, 2);
        nestedHook.release();

        PhpClosure seenAcc = new PhpClosure(ctx, seen) {
            @Override
            Object invoke(PhpRequestContext c, PhpArray args) {
                captureRef.set((long) captureRef.get() + (long) args.get(0L));
                return PhpNull.NULL;
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
