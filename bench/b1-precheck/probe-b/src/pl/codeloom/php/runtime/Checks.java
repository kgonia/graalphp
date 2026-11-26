package pl.codeloom.php.runtime;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
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

        ByteStr mixed = ByteStr.wrapOwned(new byte[]{'A', 0, (byte) 0xff, 'z', 'Z'});
        ByteStr folded = mixed.toLowerAscii();
        ByteStr expected = ByteStr.wrapOwned(new byte[]{'a', 0, (byte) 0xff, 'z', 'z'});
        equal(expected, folded);
        equal(expected.hashCode(), folded.hashCode());
        equal(true, folded == mixed.toLowerAscii());
        equal(true, folded == folded.toLowerAscii());
        equal((int) 'A', mixed.byteAt(0));

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

    private static void fixedArityDispatch() {
        PhpRequestContext ctx = new PhpRequestContext();
        PhpObject item = new PhpObject(ctx, ctx.stdClassMeta) {};
        PhpArray nested = owned(PhpArray.list(17L));
        PhpError failure = new PhpError("callback failed");
        PhpCallable.Body inspect = (c, args) -> {
            equal(ctx, c);
            if (args.size() == 0) return new PhpObject(c, c.stdClassMeta) {};
            equal(item, args.get(0L));
            equal(false, item.isFreed());
            if (args.size() == 2) {
                equal(nested, args.get(1L));
                equal(true, nested.isShared());
                throw failure;
            }
            item.addRef();
            return item;
        };
        PhpCallable fn = PhpCallable.of(inspect);
        ctx.registerFunction(ByteStr.of("CheckFn"), fn);
        PhpClass cls = ctx.defineClass(ByteStr.of("CheckClass"));
        PhpObject receiver = new PhpObject(ctx, cls) {};
        PhpInstanceMethod method = PhpInstanceMethod.of((c, self, args) -> {
            equal(receiver, self);
            return inspect.invoke(c, args);
        });
        cls.addMethod(ByteStr.of("__invoke"), method);
        cls.addMethod(ByteStr.of("method"), method);
        cls.addStaticMethod(ByteStr.of("stat"), fn);
        PhpClosure closure = new PhpClosure(ctx, null) {
            @Override Object invoke(PhpRequestContext c, PhpArray args) { return inspect.invoke(c, args); }
        };
        PhpArray instancePair = owned(PhpArray.list(receiver, ByteStr.of("METHOD")));
        PhpArray staticPair = owned(PhpArray.list(ByteStr.of("CHECKCLASS"), ByteStr.of("STAT")));
        Object[] forms = {ByteStr.of("CHECKFN"), ByteStr.of("checkCLASS::StAt"), staticPair, instancePair, receiver, closure};
        for (Object cb : forms) {
            PhpObject fresh = (PhpObject) ctx.call0(cb);
            releaseReturned(fresh);
            equal(true, fresh.isFreed());
            Object returned = ctx.call1(cb, item);
            equal(item, returned);
            equal(2, item.rc);
            releaseReturned(returned);
            try {
                ctx.call2(cb, item, nested);
                throw new AssertionError("Lost callback exception");
            } catch (PhpError e) {
                equal(failure, e);
            }
            equal(1, item.rc);
            equal(1, nested.holderCount());
        }
        instancePair.removeHolder();
        staticPair.removeHolder();
        receiver.release();
        closure.release();
        item.release();
        nested.removeHolder();
        ctx.shutdown();
    }

    private static void sliceDispatch() {
        PhpRequestContext ctx = new PhpRequestContext();
        ByteStr name = ByteStr.of("slice");
        PhpObject item = new PhpObject(ctx, ctx.stdClassMeta) {};
        PhpArray nested = owned(PhpArray.list(item));
        PhpArray args = owned(PhpArray.list(item, nested, 3L, 4L));
        item.release();
        nested.removeHolder();
        ctx.registerFunction(name, new PhpCallable() {
            @Override Object invoke0(PhpRequestContext c) { return 0L; }
            @Override Object invoke1(PhpRequestContext c, Object a0) {
                equal(item, a0);
                equal(args.size() > 1 ? 3 : 2, item.rc); // Includes the fixed-arity parameter.
                return 1L;
            }
            @Override Object invoke2(PhpRequestContext c, Object a0, Object a1) {
                equal(item, a0);
                equal(nested, a1);
                equal(2, nested.holderCount()); // The slot and the parameter each hold the array.
                return 2L;
            }
            @Override Object invoke(PhpRequestContext c, PhpArray frame) { return owned(frame); }
        });
        equal(0L, ctx.callSlice(name, args, 0));
        equal(1L, ctx.callSlice(name, args, 1));
        equal(2L, ctx.callSlice(name, args, 2));
        PhpArray whole = (PhpArray) ctx.callSlice(name, args, Long.MAX_VALUE);
        equal(4, whole.size());
        equal(item, whole.get(0L));
        equal(nested, whole.get(1L));
        equal(3L, whole.get(2L));
        equal(4L, whole.get(3L));
        whole.removeHolder();
        for (long take : new long[]{3L, -1L, Long.MIN_VALUE}) {
            PhpArray slice = (PhpArray) ctx.callSlice(name, args, take);
            equal(false, slice == args);
            equal(take == Long.MIN_VALUE ? 0 : 3, slice.size());
            if (slice.size() > 0) {
                equal(nested, slice.get(1L));
                equal(3L, slice.get(2L));
            }
            slice.removeHolder();
            equal(2, item.rc);
            equal(1, nested.holderCount());
        }
        args.remove(3L);
        args.remove(2L);
        equal(2L, ctx.callSlice(name, args, 9));
        args.remove(1L);
        // The sole remaining element owns item.
        equal(1L, ctx.callSlice(name, args, 9));
        args.remove(0L);
        equal(0L, ctx.callSlice(name, args, 9));
        args.removeHolder();
        equal(true, item.isFreed());

        ByteStr key = ByteStr.of("named");
        PhpArray sparse = owned(new PhpArray());
        sparse.put(9L, 1L);
        sparse.put(42L, 2L);
        sparse.remove(9L);
        PhpRef ref = new PhpRef(3L);
        ref.bind();
        sparse.putRef(key, ref);
        sparse.put(98L, 4L);
        PhpArray sliced = (PhpArray) ctx.callSlice(name, sparse, 2);
        equal(2, sliced.size());
        equal(2L, sliced.get(0L));
        equal(ref, sliced.getRaw(key));
        sliced.put(key, 5L);
        equal(5L, sparse.get(key));
        sliced.removeHolder();
        sparse.removeHolder();
        ref.unbind();
        ctx.shutdown();
    }

    private static void parameterLifetime(boolean slice, int arity) {
        PhpRequestContext ctx = new PhpRequestContext();
        ByteStr name = ByteStr.of("clear_slots");
        StringBuilder destroyed = new StringBuilder();
        PhpClass cls = ctx.defineClass(ByteStr.of("Parameter"));
        cls.addMethod(ByteStr.of("__destruct"), PhpInstanceMethod.of((c, self, args) -> {
            destroyed.append(((CycleNode) self).name);
            return NULL;
        }));
        PhpArray slots = owned(new PhpArray());
        for (int i = 0; i < arity; i++) {
            CycleNode node = new CycleNode(ctx, cls, ByteStr.ofLong(i));
            slots.append(node);
            node.release();
        }
        ctx.registerFunction(name, new PhpCallable() {
            @Override Object invoke(PhpRequestContext c, PhpArray args) {
                throw new AssertionError("Expected fixed-arity dispatch");
            }
            @Override Object invoke1(PhpRequestContext c, Object a0) {
                slots.remove(0L);
                equal(false, ((PhpObject) a0).isFreed());
                return NULL;
            }
            @Override Object invoke2(PhpRequestContext c, Object a0, Object a1) {
                slots.remove(1L);
                invoke1(c, a0);
                equal(false, ((PhpObject) a1).isFreed());
                return NULL;
            }
        });
        if (slice) ctx.callSlice(name, slots, arity);
        else if (arity == 1) ctx.call1(name, slots.get(0L));
        else ctx.call2(name, slots.get(0L), slots.get(1L));
        equal(arity == 1 ? "0" : "10", destroyed.toString());
        slots.removeHolder();
        ctx.shutdown();
    }

    private static void sparsePositionalArguments() {
        PhpRequestContext ctx = new PhpRequestContext();
        ByteStr trim = ByteStr.of("trim");
        PhpArray args = owned(assoc(9L, ByteStr.of(" x ")));
        equal(ByteStr.of("x"), ctx.callSlice(trim, args, 1));
        args.put(12L, ByteStr.of("x"));
        equal(ByteStr.of(" x "), ctx.callSlice(trim, args, Long.MAX_VALUE));
        args.put(9L, ByteStr.of("xx xx"));
        equal(ByteStr.of(" "), ctx.callSlice(trim, args, 2));

        // A referenced mask forces the full array entry point, even for two arguments.
        PhpRef mask = new PhpRef(ByteStr.of("x"));
        mask.bind();
        args.putRef(12L, mask);
        equal(ByteStr.of(" "), ctx.callSlice(trim, args, Long.MAX_VALUE));
        args.removeHolder();
        mask.unbind();

        args = owned(assoc(91L, 1L, -8L, 2L, 0L, 3L, 5L, 4L));
        ByteStr inspect = ByteStr.of("inspect_positions");
        ctx.registerFunction(inspect, PhpCallable.of((c, frame) -> {
            for (int i = 0; i < frame.size(); i++) {
                equal((long) i + 1, frame.get((long) i));
            }
            return (long) frame.size();
        }));
        equal(3L, ctx.callSlice(inspect, args, 3));
        equal(4L, ctx.callSlice(inspect, args, Long.MAX_VALUE));
        args.removeHolder();
        ctx.shutdown();
    }

    private static void hookPopBeforeDestruction(boolean action, boolean throwsBoth, boolean all) {
        PhpRequestContext ctx = new PhpRequestContext();
        ByteStr key = ByteStr.of("extra");
        ByteStr name = ByteStr.of("hook");
        ByteStr cb = ByteStr.of("clear_global");
        PhpError callbackFailure = new PhpError("cb");
        PhpError destructorFailure = new PhpError("dtor");
        PhpClass cls = ctx.defineClass(ByteStr.of("Extra"));
        cls.addMethod(ByteStr.of("__destruct"), PhpInstanceMethod.of((c, self, args) -> {
            equal(0, c.wpCurrentFilter.size());
            if (throwsBoth) throw destructorFailure;
            return NULL;
        }));
        PhpObject extra = new PhpObject(ctx, cls) {};
        ctx.setGlobal(key, extra);
        extra.release();
        ctx.registerFunction(cb, PhpCallable.of((c, args) -> {
            equal(1, c.wpCurrentFilter.size());
            c.setGlobal(key, NULL);
            equal(false, extra.isFreed());
            if (throwsBoth) throw callbackFailure;
            return 42L;
        }));
        HooksOpenWorld.addFilter(ctx, all ? HooksOpenWorld.ALL_KEY : name, cb, 10, 0);
        try {
            if (action) HooksOpenWorld.doAction(ctx, name, extra);
            else equal(all ? 1L : 42L, HooksOpenWorld.applyFilters(ctx, name, 1L, extra));
            if (throwsBoth) throw new AssertionError("Expected a throwing destructor");
        } catch (PhpError e) {
            equal(true, throwsBoth);
            equal(destructorFailure, e);
            equal(List.of(callbackFailure), List.of(e.getSuppressed()));
        }
        equal(true, extra.isFreed());
        equal(0, ctx.wpCurrentFilter.size());
        ctx.shutdown();
    }

    private static void callbackAndDestructorFailures() {
        PhpRequestContext ctx = new PhpRequestContext();
        ByteStr name = ByteStr.of("throw_cb");
        ByteStr key = ByteStr.of("owner");
        PhpError cb = new PhpError("cb");
        PhpClass cls = ctx.defineClass(ByteStr.of("ThrowingParameter"));
        cls.addMethod(ByteStr.of("__destruct"), PhpInstanceMethod.of((c, self, args) -> {
            throw new PhpError("dtor");
        }));
        ctx.registerFunction(name, PhpCallable.of((c, args) -> {
            c.setGlobal(key, NULL);
            throw cb;
        }));
        PhpObject arg = new PhpObject(ctx, cls) {};
        ctx.setGlobal(key, arg);
        arg.release();
        try {
            ctx.call2(name, arg, arg);
            throw new AssertionError("Expected argument cleanup to throw");
        } catch (PhpError e) {
            equal("dtor", e.getMessage());
            equal(List.of(cb), List.of(e.getSuppressed()));
        }
        equal(true, arg.isFreed());

        // A reference selects the packed wrapper. Both parameters still unwind in reverse order.
        PhpError firstFailure = new PhpError("first");
        PhpError secondFailure = new PhpError("second");
        PhpClass pairClass = ctx.defineClass(ByteStr.of("ThrowingPair"));
        PhpObject[] pair = new PhpObject[2];
        pairClass.addMethod(ByteStr.of("__destruct"), PhpInstanceMethod.of((c, self, args) -> {
            throw self == pair[0] ? firstFailure : secondFailure;
        }));
        pair[0] = new PhpObject(ctx, pairClass) {};
        pair[1] = new PhpObject(ctx, pairClass) {};
        PhpRef ref = new PhpRef(pair[1]);
        pair[1].release();
        ctx.setGlobal(key, pair[0]);
        pair[0].release();
        try {
            ctx.call2(name, pair[0], ref);
            throw new AssertionError("Expected both destructors to throw");
        } catch (PhpError e) {
            equal(firstFailure, e);
            equal(List.of(secondFailure), List.of(e.getSuppressed()));
            equal(List.of(cb), List.of(secondFailure.getSuppressed()));
        }
        equal(true, pair[0].isFreed());
        equal(true, pair[1].isFreed());

        // A value created in the callback can die specifically in the default wrapper's frame.
        PhpCallable.Body body = (c, frame) -> {
            PhpObject local = new PhpObject(c, cls) {};
            frame.append(local);
            local.release();
            throw cb;
        };
        ctx.registerFunction(name, PhpCallable.of(body));
        PhpClass invokable = ctx.defineClass(ByteStr.of("Invokable"));
        invokable.addMethod(ByteStr.of("__invoke"), PhpInstanceMethod.of((c, self, frame) -> body.invoke(c, frame)));
        PhpObject receiver = new PhpObject(ctx, invokable) {};
        PhpClosure closure = new PhpClosure(ctx, null) {
            @Override Object invoke(PhpRequestContext c, PhpArray frame) { return body.invoke(c, frame); }
        };
        for (Object target : new Object[]{name, receiver, closure}) {
            try {
                ctx.call1(target, 1L);
                throw new AssertionError("Expected wrapper cleanup to throw");
            } catch (PhpError e) {
                equal("dtor", e.getMessage());
                equal(List.of(cb), List.of(e.getSuppressed()));
            }
        }
        PhpArray sparse = owned(assoc(7L, 1L, 9L, 2L, 12L, 3L));
        try {
            ctx.callSlice(name, sparse, 3);
            throw new AssertionError("Expected slice cleanup to throw");
        } catch (PhpError e) {
            equal("dtor", e.getMessage());
            equal(List.of(cb), List.of(e.getSuppressed()));
        }
        sparse.removeHolder();
        closure.release();
        receiver.release();
        ctx.shutdown();
    }

    private static void throwingFrameCleanup() {
        PhpRequestContext ctx = new PhpRequestContext();
        PhpError failure = new PhpError("frame destructor");
        PhpClass cls = ctx.defineClass(ByteStr.of("FrameDestructor"));
        cls.addMethod(ByteStr.of("__destruct"), PhpInstanceMethod.of((c, self, args) -> { throw failure; }));
        PhpObject bomb = new PhpObject(ctx, cls) {};
        PhpObject tail = new PhpObject(ctx, ctx.stdClassMeta) {};
        ByteStr key = ByteStr.of("argument_source");
        ctx.setGlobal(key, bomb);
        bomb.release();
        PhpObject[] result = {null};
        ByteStr name = ByteStr.of("drop_source");
        ctx.registerFunction(name, PhpCallable.of((c, args) -> {
            c.setGlobal(key, NULL);
            equal(false, bomb.isFreed());
            result[0] = new PhpObject(c, c.stdClassMeta) {};
            return PhpArray.list(result[0]);
        }));
        try {
            ctx.call2(name, bomb, tail);
            throw new AssertionError("Lost argument destructor exception");
        } catch (PhpError e) {
            equal(failure, e);
        }
        equal(true, bomb.isFreed());
        equal(1, tail.rc);
        equal(1, result[0].rc); // The undelivered return array released its element.
        tail.release();
        result[0].release();
        ctx.shutdown();
    }

    private static void capturedHookArgs() {
        PhpRequestContext ctx = new PhpRequestContext();
        ByteStr name = ByteStr.of("capture_args");
        ByteStr capture = ByteStr.of("capture");
        ByteStr after = ByteStr.of("after");
        ctx.registerFunction(capture, PhpCallable.of((c, args) -> {
            equal(2, args.holderCount()); // Hook.applyFrame + callPacked each hold args during the callback
            equal(10L, args.get(0L));
            c.setGlobal(capture, args);
            return 20L;
        }));
        ctx.registerFunction(after, PhpCallable.of((c, args) -> {
            PhpArray snapshot = (PhpArray) c.getGlobal(capture);
            equal(false, snapshot == args);
            equal(10L, snapshot.get(0L));
            equal(20L, args.get(0L));
            return 30L;
        }));
        HooksOpenWorld.addFilter(ctx, name, capture, 1, 3);
        HooksOpenWorld.addFilter(ctx, name, after, 2, 3);
        equal(30L, HooksOpenWorld.applyFilters(ctx, name, 10L, 7L, 8L));
        ctx.setGlobal(capture, NULL);
        HooksOpenWorld.Hook hook = (HooksOpenWorld.Hook) ctx.wpFilter.get(name);
        PhpArray moved = owned(PhpArray.list(0L, 7L, 8L));
        equal(30L, hook.applyOwnedArgs(ctx, 10L, moved));
        equal(moved, ctx.getGlobal(capture)); // First write took place in the original storage.
        ctx.setGlobal(capture, NULL);
        PhpArray borrowed = owned(PhpArray.list(0L, 7L, 8L));
        equal(30L, hook.apply(ctx, 10L, borrowed));
        equal(0L, borrowed.get(0L));
        equal(false, borrowed == ctx.getGlobal(capture));
        equal(1, borrowed.holderCount());
        borrowed.removeHolder();
        ctx.shutdown();
    }

    private static void variadicArgumentLifetime() {
        PhpRequestContext ctx = new PhpRequestContext();
        ByteStr name = ByteStr.of("late_filter");
        PhpObject item = new PhpObject(ctx, ctx.stdClassMeta) {};
        equal(1L, HooksOpenWorld.applyFilters(ctx, name, 1L, PhpArray.list(item)));
        equal(1, item.rc);
        HooksOpenWorld.doAction(ctx, name, PhpArray.list(item), PhpArray.list(item));
        equal(1, item.rc);

        ByteStr source = ByteStr.of("source");
        ctx.setGlobal(source, item);
        item.release();
        ByteStr install = ByteStr.of("install");
        ByteStr check = ByteStr.of("check_arg");
        ctx.registerFunction(install, PhpCallable.of((c, args) -> {
            equal(name, args.get(0L));
            equal(item, args.get(2L));
            c.setGlobal(source, NULL);
            HooksOpenWorld.addFilter(c, name, check, 1, 2);
            return NULL;
        }));
        ctx.registerFunction(check, PhpCallable.of((c, args) -> {
            equal(item, args.get(1L));
            equal(false, item.isFreed());
            return 42L;
        }));
        HooksOpenWorld.addFilter(ctx, HooksOpenWorld.ALL_KEY, install, 1, 0);
        equal(42L, HooksOpenWorld.applyFilters(ctx, name, 1L, item));
        equal(true, item.isFreed());
        equal(0, ctx.wpCurrentFilter.size());
        ctx.shutdown();
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
        ByteStr hotF0 = ByteStr.of("hot_f0");
        equal(7L, ctx.call1(hotF0, ByteStr.of("7")));
        equal(-20.0d, ctx.call1(hotF0, ByteStr.of(" -2e1 ")));
        equal(1L, ctx.call1(hotF0, Boolean.TRUE));
        equal(0L, ctx.call1(hotF0, NULL));
        PhpArray method = owned(PhpArray.list(hot.hotObjects[0], HotOpenWorld.METHOD_B));
        equal(9L, ctx.call2(method, ByteStr.of("2"), ByteStr.of("7")));
        method.removeHolder();
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

    /** Regression tests for Codex t5r fallback edge cases (ref-bearing 2-arg slice, 3-arg doAction, float string add). */
    private static void fallbackSliceAndFloatArithmetic() {
        PhpRequestContext ctx = new PhpRequestContext();

        // 1. Two-element packed array containing a PhpRef keeps the other object arg alive across clearing source slot
        boolean[] freedDuringCall = {true};
        CycleNode victim = new CycleNode(ctx, ctx.stdClassMeta, ByteStr.of("Victim"));
        PhpArray refPair = owned(PhpArray.list(victim));
        victim.release(); // refPair[0] is sole owner
        PhpRef r = new PhpRef(1L);
        refPair.putRef(1L, r);
        ctx.registerFunction(ByteStr.of("clear_slot_0"), PhpCallable.of((c, args) -> {
            PhpObject p0 = (PhpObject) args.get(0L);
            refPair.put(0L, NULL); // clears caller's array slot 0
            freedDuringCall[0] = p0.isFreed();
            return NULL;
        }));
        ctx.callSlice(ByteStr.of("clear_slot_0"), refPair, 2);
        equal(false, freedDuringCall[0]);
        equal(true, victim.isFreed());
        refPair.removeHolder();

        // 2. 3-arg doAction callback removing sole owning slot does not destroy extra arg before outer pop
        int[] depthOnDtor = {-1};
        PhpClass watchCls = ctx.defineClass(ByteStr.of("ActionArgWatch"));
        watchCls.addMethod(ByteStr.of("__destruct"), PhpInstanceMethod.of((c, self, args) -> {
            depthOnDtor[0] = c.wpCurrentFilter.size();
            return NULL;
        }));
        PhpObject argObj = new PhpObject(ctx, watchCls) {};
        ctx.setGlobal(ByteStr.of("act_holder"), argObj);
        argObj.release();
        ctx.registerFunction(ByteStr.of("act3"), PhpCallable.of((c, args) -> {
            c.setGlobal(ByteStr.of("act_holder"), NULL);
            PhpArray localArgs = writable(owned(args));
            localArgs.put(2L, NULL); // PHP CoW write on shared args must not destroy hook's outer args before pop!
            localArgs.removeHolder();
            return NULL;
        }));
        HooksOpenWorld.addFilter(ctx, ByteStr.of("act3_hook"), ByteStr.of("act3"), 10, 3);
        HooksOpenWorld.doAction(ctx, ByteStr.of("act3_hook"), 1L, 2L, ctx.getGlobal(ByteStr.of("act_holder")));
        equal(0, depthOnDtor[0]);

        // 3. Float numeric-string arithmetic in MegaGenerated: hot_f0("7.5") == 7.5 (Double), hot_f0("7") == 7L
        PhpObject[] hotObjs = MegaGenerated.registerAndCreate(ctx, false);
        equal(7.5d, ctx.call1(ByteStr.of("hot_f0"), ByteStr.of("7.5")));
        equal(7L, ctx.call1(ByteStr.of("hot_f0"), ByteStr.of("7")));
        for (PhpObject o : hotObjs) o.release();
        ctx.shutdown();
    }

    static void runAll(Path corpus) throws Exception {
        byteStrChecks();
        codexCycleSafetyChecks();
        codexStoreAndCallbackChecks();
        destructorReachability();
        referenceAndArrayCycles();
        hookReturnOwnership();
        fixedArityDispatch();
        sliceDispatch();
        for (int arity = 1; arity <= 2; arity++) {
            parameterLifetime(false, arity);
            parameterLifetime(true, arity);
        }
        sparsePositionalArguments();
        for (boolean action : new boolean[]{false, true}) {
            for (boolean all : new boolean[]{false, true}) {
                hookPopBeforeDestruction(action, false, all);
                hookPopBeforeDestruction(action, true, all);
            }
        }
        callbackAndDestructorFailures();
        throwingFrameCleanup();
        capturedHookArgs();
        variadicArgumentLifetime();
        selfRemovingReceivers();
        reentrantDeclaredWrites();
        shutdownUnderMutation();
        callbackSnapshotAndTrim();
        declaredOwnershipAndHotClose();
        fallbackSliceAndFloatArithmetic();
        if (corpus != null) {
            twoContextIsolation(corpus);
        }
        System.out.println("probe-b semantic checks passed");
    }
}
