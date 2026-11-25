package pl.codeloom.php.runtime;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Objects;

import static pl.codeloom.php.runtime.PhpNull.NULL;
import static pl.codeloom.php.runtime.Values.*;

/** Probe B semantic gates and the byte-comparable PHP D17 fixture. */
final class Checks {
    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("Expected [" + expected + "], got [" + actual + "]");
        }
    }

    // ---- D17 Destructor Correctness Fixture (mirrors php-ref/d17_fixture.php line-for-line) ----

    static final class HtmlToken extends PhpObject {
        final int index;
        final ByteStr bookmarkName;
        Object onDestroy;

        HtmlToken(PhpRequestContext ctx, PhpClass cls, int index, ByteStr bookmarkName, Object onDestroy) {
            super(ctx, cls);
            this.index = index;
            this.bookmarkName = bookmarkName;
            this.onDestroy = onDestroy;
            retainValue(onDestroy);
        }

        @Override
        void freeProperties() {
            releaseValue(onDestroy);
            onDestroy = NULL;
        }

        @Override
        void visitChildren(ChildVisitor visitor) {
            visitor.visit(onDestroy);
        }
    }

    static final class BookmarkProcessor extends PhpObject {
        PhpArray bookmarks = owned(new PhpArray());
        int peakBookmarks;
        int released;

        BookmarkProcessor(PhpRequestContext ctx, PhpClass cls) {
            super(ctx, cls);
        }

        @Override
        void freeProperties() {
            bookmarks.removeHolder();
        }

        @Override
        void visitChildren(ChildVisitor visitor) {
            visitor.visit(bookmarks);
        }
    }

    static final class CycleNode extends PhpObject {
        final ByteStr name;
        Object peer = NULL;

        CycleNode(PhpRequestContext ctx, PhpClass cls, ByteStr name) {
            super(ctx, cls);
            this.name = name;
        }

        void setPeer(Object val) {
            if (peer == val) return;
            retainValue(val);
            Object old = peer;
            peer = val;
            releaseValue(old);
        }

        @Override
        void freeProperties() {
            Object old = peer;
            peer = NULL;
            releaseValue(old);
        }

        @Override
        void visitChildren(ChildVisitor visitor) {
            visitor.visit(peer);
        }
    }

    static final class Phoenix extends PhpObject {
        final ByteStr name;

        Phoenix(PhpRequestContext ctx, PhpClass cls, ByteStr name) {
            super(ctx, cls);
            this.name = name;
        }
    }

    static final class ShutdownObj extends PhpObject {
        final ByteStr label;

        ShutdownObj(PhpRequestContext ctx, PhpClass cls, ByteStr label) {
            super(ctx, cls);
            this.label = label;
        }
    }

    static void runD17Fixture(PrintStream out) {
        PhpRequestContext ctx = new PhpRequestContext();

        PhpClass tokenCls = ctx.defineClass(ByteStr.of("HtmlToken"));
        tokenCls.addMethod(ByteStr.of("__destruct"), PhpInstanceMethod.of((c, self, args) -> {
            HtmlToken tok = (HtmlToken) self;
            if (tok.onDestroy != NULL) {
                PhpArray cbArgs = PhpArray.list(tok.bookmarkName, (long) tok.index);
                Object r = c.call(tok.onDestroy, cbArgs);
                cbArgs.releaseTemporary();
                releaseReturned(r);
            }
            return NULL;
        }));

        PhpClass procCls = ctx.defineClass(ByteStr.of("BookmarkProcessor"));

        PhpClass cycleCls = ctx.defineClass(ByteStr.of("CycleNode"));
        cycleCls.addMethod(ByteStr.of("__destruct"), PhpInstanceMethod.of((c, self, args) -> {
            out.print("cycle_dtor:" + ((CycleNode) self).name + "\n");
            return NULL;
        }));

        ByteStr resurrectedGlobal = ByteStr.of("resurrected_holder");
        ctx.setGlobal(resurrectedGlobal, NULL);
        PhpClass phoenixCls = ctx.defineClass(ByteStr.of("Phoenix"));
        phoenixCls.addMethod(ByteStr.of("__destruct"), PhpInstanceMethod.of((c, self, args) -> {
            out.print("phoenix_dtor:" + ((Phoenix) self).name + "\n");
            c.setGlobal(resurrectedGlobal, self);
            return NULL;
        }));

        ByteStr g1AliasKey = ByteStr.of("g1_alias");
        PhpClass shutdownCls = ctx.defineClass(ByteStr.of("ShutdownObj"));
        shutdownCls.addMethod(ByteStr.of("__destruct"), PhpInstanceMethod.of((c, self, args) -> {
            ShutdownObj obj = (ShutdownObj) self;
            out.print("shutdown_dtor:" + obj.label + "\n");
            if (obj.label.equals(ByteStr.of("G2"))) {
                c.setGlobal(g1AliasKey, NULL);
            }
            return NULL;
        }));

        // Part 1: 12,000-token WP_HTML_Token $on_destroy bookmark callback stream
        {
            BookmarkProcessor proc = new BookmarkProcessor(ctx, procCls);
            PhpClosure onDestroy = new PhpClosure(ctx, null, proc) {
                @Override
                Object invoke(PhpRequestContext c, PhpArray args) {
                    ByteStr name = (ByteStr) args.get(0L);
                    long idx = (long) args.get(1L);
                    BookmarkProcessor proc = (BookmarkProcessor) boundThis;
                    proc.bookmarks = writable(proc.bookmarks);
                    proc.bookmarks.remove(name);
                    proc.released++;
                    if (idx < 3 || idx >= 11997) {
                        out.print("bookmark_dtor:" + idx + ":active=" + proc.bookmarks.size() + "\n");
                    }
                    return NULL;
                }
            };
            ByteStr bmPrefix = ByteStr.of("bm_");
            for (int i = 0; i < 12000; i++) {
                ByteStr bname = bmPrefix.concat(ByteStr.ofLong(i));
                proc.bookmarks = writable(proc.bookmarks);
                proc.bookmarks.put(bname, (long) i);
                int cur = proc.bookmarks.size();
                if (cur > proc.peakBookmarks) proc.peakBookmarks = cur;
                HtmlToken tok = new HtmlToken(ctx, tokenCls, i, bname, onDestroy);
                tok.release();
            }
            out.print("bookmark_summary:active=" + proc.bookmarks.size()
                    + ",peak=" + proc.peakBookmarks
                    + ",released=" + proc.released + "\n");
            proc.release();
            equal(1, proc.refcount());
            equal(false, proc.isFreed());
            onDestroy.release();
            equal(0, proc.refcount());
            equal(true, proc.isFreed());
            // O(1) unregister verification: 12,000 freed tokens MUST NOT linger in ctx destructibles!
            equal(0, ctx.liveDestructibleCount);
            out.print("part1_gc:" + ctx.gcCollectCycles() + "\n");
        }

        // Part 2: Unreachable 2-object cycle
        {
            CycleNode a = new CycleNode(ctx, cycleCls, ByteStr.of("A"));
            CycleNode b = new CycleNode(ctx, cycleCls, ByteStr.of("B"));
            a.setPeer(b);
            b.setPeer(a);
            out.print("before_unset_cycle\n");
            a.release();
            b.release();
            out.print("after_unset_cycle\n");
            int collected1 = ctx.gcCollectCycles();
            out.print("gc_collect_1:" + collected1 + "\n");
            equal(0, a.refcount());
            equal(0, b.refcount());
        }

        // Part 3: Unreachable cycle through arrays
        {
            CycleNode c = new CycleNode(ctx, cycleCls, ByteStr.of("C"));
            CycleNode d = new CycleNode(ctx, cycleCls, ByteStr.of("D"));
            PhpArray arrC = assoc(ByteStr.of("next"), d);
            PhpArray arrD = assoc(ByteStr.of("next"), c);
            c.setPeer(arrC);
            d.setPeer(arrD);
            c.release();
            d.release();
            out.print("after_unset_array_cycle\n");
            int collected2 = ctx.gcCollectCycles();
            out.print("gc_collect_2:" + collected2 + "\n");
            equal(0, c.refcount());
            equal(0, d.refcount());
        }

        // Part 4: Resurrection inside __destruct
        {
            Phoenix p = new Phoenix(ctx, phoenixCls, ByteStr.of("P1"));
            out.print("phoenix_unset_first\n");
            p.release();
            out.print("phoenix_after_first_unset\n");
        }
        out.print("phoenix_unset_global\n");
        ctx.setGlobal(resurrectedGlobal, NULL);
        out.print("phoenix_done\n");

        // Part 5: Request shutdown order with aliased globals + destructor mutation + arrays
        ShutdownObj g1 = new ShutdownObj(ctx, shutdownCls, ByteStr.of("G1"));
        ctx.setGlobal(ByteStr.of("g1"), g1);
        ctx.setGlobal(g1AliasKey, g1);
        g1.release();

        ShutdownObj g3 = new ShutdownObj(ctx, shutdownCls, ByteStr.of("G3"));
        ctx.setGlobal(ByteStr.of("g3"), g3);
        ctx.setGlobal(ByteStr.of("g3_alias"), g3);
        g3.release();

        ShutdownObj g2 = new ShutdownObj(ctx, shutdownCls, ByteStr.of("G2"));
        ctx.setGlobal(ByteStr.of("g2"), g2);
        g2.release();

        ShutdownObj a1 = new ShutdownObj(ctx, shutdownCls, ByteStr.of("A1"));
        ShutdownObj a2 = new ShutdownObj(ctx, shutdownCls, ByteStr.of("A2"));
        PhpArray gArr = PhpArray.list(a1, a2);
        a1.release();
        a2.release();
        ctx.setGlobal(ByteStr.of("gArr"), gArr);
        out.print("script_end\n");

        ctx.shutdown();
    }

    // ---- Codex Round 1 Adversarial Counterexample Gate Tests ----

    private static void codexCycleSafetyChecks() {
        PhpRequestContext ctx = new PhpRequestContext();
        int[] dtors = {0};
        PhpClass nodeCls = ctx.defineClass(ByteStr.of("GateCycleNode"));
        nodeCls.addMethod(ByteStr.of("__destruct"), PhpInstanceMethod.of((c, self, args) -> {
            dtors[0]++;
            return NULL;
        }));

        // Counterexample 1a: Global holding an array on a 2-object cycle MUST NOT be collected!
        CycleNode a = new CycleNode(ctx, nodeCls, ByteStr.of("LiveA"));
        CycleNode b = new CycleNode(ctx, nodeCls, ByteStr.of("LiveB"));
        PhpArray sharedArr = PhpArray.list(b);
        a.setPeer(sharedArr);
        b.setPeer(a);
        ctx.setGlobal(ByteStr.of("live_arr"), sharedArr);
        a.release();
        b.release();
        int collected = ctx.gcCollectCycles();
        equal(0, collected);
        equal(0, dtors[0]);
        equal(false, a.isFreed());
        equal(false, b.isFreed());

        // Drop global -> now unreachable cycle collects cleanly with exact rc == 0
        ctx.setGlobal(ByteStr.of("live_arr"), NULL);
        int collectedAfter = ctx.gcCollectCycles();
        equal(3, collectedAfter); // LiveA + LiveB + sharedArr
        equal(2, dtors[0]);
        equal(0, a.refcount());
        equal(0, b.refcount());

        // Counterexample 1b: Cycle with resurrection inside __destruct
        ByteStr savedKey = ByteStr.of("saved_from_cycle");
        PhpClass resCls = ctx.defineClass(ByteStr.of("ResCycleNode"));
        resCls.addMethod(ByteStr.of("__destruct"), PhpInstanceMethod.of((c, self, args) -> {
            CycleNode n = (CycleNode) self;
            if (n.name.equals(ByteStr.of("RA"))) {
                c.setGlobal(savedKey, n);
            }
            return NULL;
        }));
        CycleNode ra = new CycleNode(ctx, resCls, ByteStr.of("RA"));
        CycleNode rb = new CycleNode(ctx, resCls, ByteStr.of("RB"));
        ra.setPeer(rb);
        rb.setPeer(ra);
        ra.release();
        rb.release();
        ctx.gcCollectCycles();
        equal(false, ra.isFreed());
        equal(false, rb.isFreed()); // reachable through resurrected RA->peer
        equal(rb, ra.peer);
        ctx.shutdown();
    }

    private static void codexStoreAndCallbackChecks() {
        PhpRequestContext ctx = new PhpRequestContext();
        PhpClass nodeCls = ctx.defineClass(ByteStr.of("StoreNode"));

        // Counterexample 2a: PhpRef.set([a] -> a) retain-before-release MUST NOT free a!
        CycleNode a = new CycleNode(ctx, nodeCls, ByteStr.of("Child"));
        PhpArray arr = new PhpArray();
        arr.append(a);
        a.release(); // only arr holds a now (rc == 1)
        PhpRef ref = new PhpRef(arr);
        ref.bind();
        equal(1, a.refcount());
        equal(false, a.isFreed());
        ref.set(a);
        equal(1, a.refcount());
        equal(false, a.isFreed());
        ref.unbind();
        equal(0, a.refcount());
        equal(true, a.isFreed());

        // Counterexample 2b: Filter returning fresh object, second filter replacing it -> rc drops to 0!
        CycleNode[] created = {null};
        ctx.registerFunction(ByteStr.of("maker"), PhpCallable.of((c, args) -> {
            CycleNode fresh = new CycleNode(c, nodeCls, ByteStr.of("Fresh"));
            created[0] = fresh;
            return fresh;
        }));
        ctx.registerFunction(ByteStr.of("dropper"), PhpCallable.of((c, args) -> ByteStr.of("done")));
        HooksOpenWorld.addFilter(ctx, ByteStr.of("foo"), ByteStr.of("maker"), 5, 1);
        HooksOpenWorld.addFilter(ctx, ByteStr.of("foo"), ByteStr.of("dropper"), 10, 1);
        Object res = HooksOpenWorld.applyFilters(ctx, ByteStr.of("foo"), ByteStr.of("init"));
        equal(ByteStr.of("done"), res);
        equal(0, created[0].refcount());
        equal(true, created[0].isFreed());

        // Counterexample 2c: Self-removing 'all' hook matches exact WordPress plugin.php:185-209 stack quirk
        // (line 186 pushes before doAllHook, line 200 sees !isset($wp_filter['all']) and pushes again, line 209 pops once -> 1 left!)
        ctx.registerFunction(ByteStr.of("self_remove_all"), PhpCallable.of((c, args) -> {
            HooksOpenWorld.removeAllFilters(c, HooksOpenWorld.ALL_KEY);
            return NULL;
        }));
        HooksOpenWorld.addFilter(ctx, HooksOpenWorld.ALL_KEY, ByteStr.of("self_remove_all"), 10, 1);
        HooksOpenWorld.applyFilters(ctx, ByteStr.of("foo"), ByteStr.of("init"));
        equal(1, ctx.wpCurrentFilter.size());
        ctx.wpCurrentFilter.pop();

        // Counterexample 2d: Constant retains counted object until shutdown
        int[] constDtor = {0};
        PhpClass cCls = ctx.defineClass(ByteStr.of("ConstObj"));
        cCls.addMethod(ByteStr.of("__destruct"), PhpInstanceMethod.of((c, self, args) -> {
            constDtor[0]++;
            return NULL;
        }));
        PhpObject cObj = new PhpObject(ctx, cCls) {};
        ByteStr constName = ByteStr.of("MY_CONST_OBJ");
        equal(true, ctx.defineConstant(constName, cObj));
        PhpObject duplicate = new PhpObject(ctx, cCls) {};
        equal(false, ctx.defineConstant(constName, duplicate));
        equal(cObj, ctx.getConstant(constName));
        equal(1, duplicate.rc);
        cObj.release();
        equal(1, cObj.refcount());
        equal(0, constDtor[0]);
        duplicate.release();
        equal(1, constDtor[0]);
        ctx.shutdown();
        equal(2, constDtor[0]);
        equal(true, cObj.isFreed());
    }

    private static void destructorReachability() {
        PhpRequestContext ctx = new PhpRequestContext();
        ByteStr saved = ByteStr.of("saved");
        PhpClass cls = ctx.defineClass(ByteStr.of("SavesSelf"));
        cls.addMethod(ByteStr.of("__destruct"), PhpInstanceMethod.of((c, self, args) -> {
            c.setGlobal(saved, self);
            ((CycleNode) self).setPeer(NULL);
            return NULL;
        }));
        CycleNode node = new CycleNode(ctx, cls, saved);
        node.setPeer(node);
        node.release();
        equal(0, ctx.gcCollectCycles());
        equal(node, ctx.getGlobal(saved));
        equal(1, node.rc);
        equal(false, node.isFreed());
        ctx.setGlobal(saved, NULL);
        equal(true, node.isFreed());

        // The external root can also be an array, with a path back through a closure and a reference.
        PhpClass viaArray = ctx.defineClass(ByteStr.of("SavesArray"));
        viaArray.addMethod(ByteStr.of("__destruct"), PhpInstanceMethod.of((c, self, args) -> {
            c.setGlobal(saved, ((CycleNode) self).peer);
            return NULL;
        }));
        node = new CycleNode(ctx, viaArray, saved);
        PhpRef ref = new PhpRef(node);
        PhpClosure closure = new PhpClosure(ctx, ref) {
            @Override Object invoke(PhpRequestContext c, PhpArray args) { return NULL; }
        };
        PhpArray array = PhpArray.list(closure);
        node.setPeer(array);
        closure.release();
        node.release();
        equal(0, ctx.gcCollectCycles());
        equal(false, node.isFreed());
        equal(1, node.rc);
        equal(1, closure.rc);
        equal(2, array.holderCount());
        ctx.setGlobal(saved, NULL);
        equal(3, ctx.gcCollectCycles());
        equal(0, closure.rc);
        equal(0, ref.bindingCount());
        equal(0, node.rc);

        // A throw leaves both objects and their other candidate nodes eligible for retry.
        int[] calls = {0};
        PhpError failure = new PhpError("cycle destructor");
        PhpClass throwsOnce = ctx.defineClass(ByteStr.of("ThrowsOnce"));
        throwsOnce.addMethod(ByteStr.of("__destruct"), PhpInstanceMethod.of((c, self, args) -> {
            if (++calls[0] == 1) throw failure;
            return NULL;
        }));
        CycleNode first = new CycleNode(ctx, throwsOnce, saved);
        CycleNode second = new CycleNode(ctx, throwsOnce, saved);
        array = PhpArray.list(second);
        first.setPeer(array);
        second.setPeer(first);
        first.release();
        second.release();
        try {
            ctx.gcCollectCycles();
            throw new AssertionError("Expected cycle destructor to throw");
        } catch (PhpError e) {
            equal(failure, e);
        }
        equal(1, first.rc);
        equal(1, second.rc);
        equal(1, array.holderCount());
        equal(3, ctx.gcCollectCycles());
        equal(2, calls[0]);
        equal(0, first.rc);
        equal(0, second.rc);
        equal(0, ctx.gcCollectCycles());
        ctx.shutdown();
    }

    private static void referenceAndArrayCycles() {
        PhpRequestContext ctx = new PhpRequestContext();
        ByteStr key = ByteStr.of("cycle");
        CycleNode node = new CycleNode(ctx, ctx.stdClassMeta, key);
        PhpRef shared = new PhpRef(node);
        shared.bind(); // external alias remains for the first collection
        PhpArray slots = new PhpArray();
        slots.putRef(0L, shared);
        slots.putRef(1L, shared);
        node.setPeer(slots);
        node.release();
        equal(3, shared.bindingCount());
        equal(0, ctx.gcCollectCycles());
        shared.unbind();
        equal(2, ctx.gcCollectCycles());
        equal(0, shared.bindingCount());
        equal(0, node.rc);
        equal(true, node.isFreed());

        PhpRef capture = new PhpRef(NULL);
        capture.bind();
        PhpClosure closure = new PhpClosure(ctx, capture) {
            @Override Object invoke(PhpRequestContext c, PhpArray args) { return NULL; }
        };
        capture.set(closure);
        closure.release();
        equal(0, ctx.gcCollectCycles());
        capture.unbind();
        equal(1, ctx.gcCollectCycles());
        equal(0, closure.rc);
        equal(0, capture.bindingCount());

        for (boolean global : new boolean[] {true, false}) {
            node = new CycleNode(ctx, ctx.stdClassMeta, key);
            PhpArray top = PhpArray.list(node);
            for (int i = 1; i < 10; i++) top = PhpArray.list(top);
            node.setPeer(top);
            if (global) {
                ctx.setGlobal(key, top);
            } else {
                top.addHolder();
                top.addHolder();
                top.removeHolder(); // first buffer must discover the request ten arrays down
                equal(ctx, top.gcOwner);
                equal(top, ctx.gcArrayRoots.get(top.gcArrayIdx));
            }
            node.release();
            equal(0, ctx.gcCollectCycles());
            if (global) ctx.setGlobal(key, NULL);
            else top.removeHolder();
            equal(11, ctx.gcCollectCycles());
            equal(true, top.isReleased());
            equal(true, node.isFreed());
        }

        PhpArray self = new PhpArray();
        ctx.setGlobal(key, self);
        PhpRef back = new PhpRef(self);
        self.putRef(0L, back);
        back.bind();
        ctx.setGlobal(key, NULL);
        equal(0, ctx.gcCollectCycles());
        back.unbind();
        equal(1, ctx.gcCollectCycles());
        equal(0, back.bindingCount());
        equal(0, self.holderCount());
        equal(0, ctx.gcCollectCycles());
        ctx.shutdown();
    }

    private static void hookReturnOwnership() {
        PhpRequestContext ctx = new PhpRequestContext();
        ByteStr name = ByteStr.of("return_lifetime");
        PhpObject input = new PhpObject(ctx, ctx.stdClassMeta) {};
        Object unchanged = HooksOpenWorld.applyFilters(ctx, name, input);
        equal(input, unchanged);
        releaseReturned(unchanged);
        equal(1, input.rc);
        equal(false, input.isFreed());

        ByteStr make = ByteStr.of("make_array");
        ByteStr inspect = ByteStr.of("inspect_previous");
        ctx.registerFunction(make, PhpCallable.of((c, args) -> {
            equal(1, args.size());
            equal(ByteStr.EMPTY, args.get(0L));
            return PhpArray.list(input);
        }));
        ctx.registerFunction(inspect, PhpCallable.of((c, args) -> {
            equal(2, input.rc); // previous action return still holds input
            return PhpArray.list(input);
        }));
        HooksOpenWorld.addFilter(ctx, name, make, 10, 1);
        HooksOpenWorld.addFilter(ctx, name, inspect, 20, 0);
        HooksOpenWorld.doAction(ctx, name);
        equal(1, input.rc);
        HooksOpenWorld.removeAllFilters(ctx, name);

        // The named hook returns an object. Dropping the outer argument array then runs a throwing destructor.
        PhpError failure = new PhpError("argument cleanup");
        PhpClass bombClass = ctx.defineClass(ByteStr.of("ThrowOnRelease"));
        bombClass.addMethod(ByteStr.of("__destruct"), PhpInstanceMethod.of((c, self, args) -> { throw failure; }));
        ByteStr bombKey = ByteStr.of("bomb");
        for (boolean named : new boolean[] {true, false}) {
            PhpObject bomb = new PhpObject(ctx, bombClass) {};
            ctx.setGlobal(bombKey, bomb);
            bomb.release();
            PhpObject[] fresh = {null};
            ctx.registerFunction(make, PhpCallable.of((c, args) -> {
                c.setGlobal(bombKey, NULL);
                if (!named) return NULL;
                fresh[0] = new PhpObject(c, c.stdClassMeta) {};
                return fresh[0];
            }));
            ByteStr hook = named ? name : HooksOpenWorld.ALL_KEY;
            HooksOpenWorld.addFilter(ctx, hook, make, 10, 1);
            try {
                HooksOpenWorld.applyFilters(ctx, name, input, bomb);
                throw new AssertionError("Expected argument cleanup to throw");
            } catch (PhpError e) {
                equal(failure, e);
            }
            equal(true, bomb.isFreed());
            if (named) equal(true, fresh[0].isFreed());
            equal(1, input.rc);
            HooksOpenWorld.removeAllFilters(ctx, hook);
        }
        input.release();
        ctx.shutdown();
    }

    private static void selfRemovingReceivers() {
        for (boolean action : new boolean[] {false, true}) {
            for (boolean all : new boolean[] {false, true}) {
                PhpRequestContext ctx = new PhpRequestContext();
                ByteStr name = ByteStr.of("self_removing");
                ByteStr hookName = all ? HooksOpenWorld.ALL_KEY : name;
                ByteStr cb = ByteStr.of("remove");
                HooksOpenWorld.addFilter(ctx, hookName, cb, 10, 1);
                HooksOpenWorld.Hook receiver = (HooksOpenWorld.Hook) ctx.wpFilter.get(hookName);
                ctx.registerFunction(cb, PhpCallable.of((c, args) -> {
                    HooksOpenWorld.removeAllFilters(c, hookName);
                    equal(false, receiver.isFreed());
                    equal(1, receiver.rc);
                    return NULL;
                }));
                if (action) HooksOpenWorld.doAction(ctx, name);
                else HooksOpenWorld.applyFilters(ctx, name, NULL);
                equal(true, receiver.isFreed());
                equal(0, receiver.rc);
                ctx.shutdown();
            }
        }
    }

    private static void reentrantDeclaredWrites() {
        PhpRequestContext ctx = new PhpRequestContext();
        StdClass row = new StdClass(ctx);
        WPPost post = new WPPost(ctx, row);
        for (ByteStr property : WPPost.DECLARED_NAMES) {
            PhpClass cls = ctx.defineClass(property);
            cls.addMethod(ByteStr.of("__destruct"), PhpInstanceMethod.of((c, self, args) -> {
                equal(7L, post.readProp(property));
                post.writeProp(property, 99L);
                return NULL;
            }));
            PhpObject old = new PhpObject(ctx, cls) {};
            post.writeProp(property, old);
            old.release();
            post.writeProp(property, 7L);
            equal(0, old.rc);
            equal(true, old.isFreed());
            equal(99L, post.readProp(property));
        }
        post.release();
        row.release();
        ctx.shutdown();
    }

    private static void shutdownUnderMutation() {
        PhpRequestContext ctx = new PhpRequestContext();
        StringBuilder trace = new StringBuilder();
        ShutdownObj[] objects = new ShutdownObj[3];
        PhpClass cls = ctx.defineClass(ByteStr.of("ShutdownMutation"));
        cls.addMethod(ByteStr.of("__destruct"), PhpInstanceMethod.of((c, self, args) -> {
            trace.append(((ShutdownObj) self).label);
            if (self == objects[0]) {
                objects[1].release();
                objects[0].release();
            }
            return NULL;
        }));
        for (int i = 0; i < objects.length; i++) {
            objects[i] = new ShutdownObj(ctx, cls, ByteStr.of("ABC".substring(i, i + 1)));
        }
        ctx.shutdown();
        equal("ABC", trace.toString());
        equal(0, ctx.liveDestructibleCount);
        equal(true, objects[0].isFreed());
        equal(0, objects[0].rc);
        equal(true, objects[1].isFreed());
        objects[2].release();
        equal(0, objects[2].rc);
    }

    // ---- Core Open-World Semantic Checks ----

    private static void byteStrChecks() {
        ByteStr bin = ByteStr.wrapOwned(new byte[]{0, 'a', 0, (byte) 0xFF, 'z'});
        equal(5, bin.length());
        equal(0, bin.byteAt(0));
        equal(0xFF, bin.byteAt(3));
        ByteStr trimmed = bin.trim();
        equal(4, trimmed.length());
        equal((int) 'a', trimmed.byteAt(0));

        ByteStr utf8 = ByteStr.ofUtf8("zażółć");
        equal(10, utf8.length());

        ByteStr n1 = ByteStr.wrapOwned(new byte[]{'a', 0, 'b'});
        ByteStr n2 = ByteStr.wrapOwned(new byte[]{'a', 0, 'c'});
        equal(false, n1.equals(n2));
        equal(true, n1.equals(ByteStr.wrapOwned(new byte[]{'a', 0, 'b'})));

        for (long n = 0; n < 10; n++) equal(true, ByteStr.ofLong(n) == ByteStr.ofLong(n));
        for (long n : new long[] { Long.MIN_VALUE, -10, -1, 10, Long.MAX_VALUE }) {
            equal(ByteStr.of(Long.toString(n)), ByteStr.ofLong(n));
            equal(false, ByteStr.ofLong(n) == ByteStr.ofLong(n));
        }

        // Direct byte[] ArrayKey edge cases
        equal(Long.MIN_VALUE, ArrayKey.ofString(ByteStr.of("-9223372036854775808")));
        equal(Long.MAX_VALUE, ArrayKey.ofString(ByteStr.of("9223372036854775807")));
        equal(ByteStr.of("9223372036854775808"), ArrayKey.ofString(ByteStr.of("9223372036854775808")));
        equal(ByteStr.of("-9223372036854775809"), ArrayKey.ofString(ByteStr.of("-9223372036854775809")));
        equal(0L, ArrayKey.ofString(ByteStr.of("0")));
        equal(ByteStr.of("-0"), ArrayKey.ofString(ByteStr.of("-0")));
        equal(ByteStr.of("01"), ArrayKey.ofString(ByteStr.of("01")));
    }

    private static void callbackSnapshotAndTrim() {
        PhpRequestContext ctx = new PhpRequestContext();
        HooksOpenWorld.Hook hook = new HooksOpenWorld.Hook(ctx);
        hook.add(ByteStr.of("trim"), 10L, 1);
        PhpArray original = owned(hook.callbacks);
        hook.add(ByteStr.of("bench_upper_first"), 10L, 1);
        PhpArray oldGroup = (PhpArray) original.get(10L);
        equal(1, oldGroup.size());
        equal(true, oldGroup.containsKey(ByteStr.of("trim")));
        equal(false, oldGroup.containsKey(ByteStr.of("bench_upper_first")));
        equal(2, ((PhpArray) hook.callbacks.get(10L)).size());
        PhpArray beforeRemoval = owned(hook.callbacks);
        hook.removeAll(10L);
        equal(false, hook.callbacks.containsKey(10L));
        equal(2, ((PhpArray) beforeRemoval.get(10L)).size());
        original.removeHolder();
        beforeRemoval.removeHolder();
        hook.release();

        HooksOpenWorld.addFilter(ctx, ByteStr.of("trim_mask"), ByteStr.of("trim"), 10, 2);
        equal(ByteStr.of("A"), HooksOpenWorld.applyFilters(ctx, ByteStr.of("trim_mask"), ByteStr.of("xxAxx"), ByteStr.of("x")));
        equal(ByteStr.of("A"), HooksOpenWorld.applyFilters(ctx, ByteStr.of("trim_mask"), ByteStr.of("az9A1b"), ByteStr.of("a..z0..9")));
        equal(ByteStr.of("xxAxx"), HooksOpenWorld.applyFilters(ctx, ByteStr.of("trim_mask"), ByteStr.of("xxAxx"), ByteStr.EMPTY));
        equal(ByteStr.of(" x "), HooksOpenWorld.applyFilters(ctx, ByteStr.of("trim_mask"), ByteStr.of(" x "), ByteStr.of("x")));
        equal(ByteStr.of("A"), HooksOpenWorld.applyFilters(ctx, ByteStr.of("trim_mask"), ByteStr.of("x0A0x"), ByteStr.of("0x")));
        equal(ByteStr.of("A"), HooksOpenWorld.applyFilters(ctx, ByteStr.of("trim_mask"), ByteStr.of("\tA\0")));
        ctx.shutdown();
    }

    private static void declaredOwnershipAndHotClose() {
        PhpRequestContext ctx = new PhpRequestContext();
        StdClass row = new StdClass(ctx);
        PhpArray content = PhpArray.list(ByteStr.of("before"));
        row.set(WPPost.K_POST_CONTENT, content);
        WPPost post = new WPPost(ctx, row);
        equal(2, content.holderCount());
        equal(content, post.readProp(WPPost.K_POST_CONTENT));

        // $post->post_content[] = 'after' (in-place CoW separation of property slot)
        PhpArray edited = writable((PhpArray) post.readProp(WPPost.K_POST_CONTENT));
        post.post_content = edited;
        edited.append(ByteStr.of("after"));
        PhpArray rowVars = row.getObjectVars();
        PhpArray original = (PhpArray) rowVars.get(WPPost.K_POST_CONTENT);
        equal(1, original.size());
        equal(ByteStr.of("before"), original.get(0L));
        equal(ByteStr.of("after"), ((PhpArray) post.readProp(WPPost.K_POST_CONTENT)).get(1L));
        rowVars.removeHolder();

        post.writeProp(WPPost.K_POST_CONTENT, content);
        equal(0, edited.holderCount());
        equal(2, content.holderCount());
        post.release();
        equal(1, content.holderCount());
        row.release();

        // Verify HotOpenWorld.close() releases row and all 20 HotC objects cleanly
        HotOpenWorld hot = new HotOpenWorld(ctx, false);
        PhpObject sampleHotObj = hot.hotObjects[0];
        StdClass sampleRow = hot.row;
        hot.close();
        ctx.shutdown();
        equal(0, sampleRow.refcount());
        equal(0, sampleHotObj.refcount());
    }

    private static void twoContextIsolation(Path corpus) throws Exception {
        PhpArray headings = Corpus.headings(corpus);
        PhpRequestContext ctxA = new PhpRequestContext();
        PhpRequestContext ctxB = new PhpRequestContext();
        HooksOpenWorld.registerHooksDomain(ctxA, false, ByteStr.of("A:"));
        HooksOpenWorld.registerHooksDomain(ctxB, true, ByteStr.of("B:"));

        long[] allCountB = {0L};
        PhpClosure allListener = new PhpClosure(ctxB, null) {
            @Override
            Object invoke(PhpRequestContext c, PhpArray args) {
                allCountB[0]++;
                return NULL;
            }
        };
        HooksOpenWorld.addFilter(ctxB, HooksOpenWorld.ALL_KEY, allListener, 10, 1);
        allListener.release();

        PhpArray resA = HooksOpenWorld.hooksKernel(ctxA, headings);
        PhpArray resB = HooksOpenWorld.hooksKernel(ctxB, headings);
        String sumA = ProbeBMain.checksum(resA);
        String sumB = ProbeBMain.checksum(resB);

        equal(ProbeBMain.HOOKS_CHECKSUM, sumA);
        equal(false, sumA.equals(sumB));
        equal(true, allCountB[0] > 0L);
        // Including resA above, exercise ten consecutive rounds without an intervening collection.
        for (int round = 1; round < 10; round++) {
            PhpArray result = HooksOpenWorld.hooksKernel(ctxA, headings);
            equal(ProbeBMain.HOOKS_CHECKSUM, ProbeBMain.checksum(result));
            result.releaseTemporary();
        }
        for (int i = 0; i < ctxA.gcArrayRoots.size(); i++) {
            PhpArray root = ctxA.gcArrayRoots.get(i);
            equal(false, root.isReleased());
            equal(true, root.holderCount() > 0);
            equal(i, root.gcArrayIdx);
            equal(ctxA, root.gcOwner);
        }
        equal(null, ctxA.wpFilter.get(HooksOpenWorld.ALL_KEY));
        equal(true, ctxB.wpFilter.get(HooksOpenWorld.ALL_KEY) != null);

        ctxA.shutdown();
        ctxB.shutdown();
    }

    static void runAll(Path corpus) throws Exception {
        byteStrChecks();
        codexCycleSafetyChecks();
        codexStoreAndCallbackChecks();
        destructorReachability();
        referenceAndArrayCycles();
        hookReturnOwnership();
        selfRemovingReceivers();
        reentrantDeclaredWrites();
        shutdownUnderMutation();
        callbackSnapshotAndTrim();
        declaredOwnershipAndHotClose();
        if (corpus != null) {
            twoContextIsolation(corpus);
        }
        System.out.println("probe-b semantic checks passed");
    }
}
