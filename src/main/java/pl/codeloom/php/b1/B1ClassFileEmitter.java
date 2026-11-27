package pl.codeloom.php.b1;

import java.lang.classfile.*;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;
import static pl.codeloom.php.ir.IrUnit.*;
import static pl.codeloom.php.ir.AsciiCase.lower;

/** Emits executable CFG edges and PHP operations. The generated class carries no reference to compiler data. */
final class B1ClassFileEmitter {
    private static ClassDesc runtime(String name) { return ClassDesc.of("pl.codeloom.php.b1.runtime." + name); }
    static final ClassDesc CALLABLE = runtime("B1Callable"), CTX = runtime("B1RequestContext");
    private static final ClassDesc FRAME = runtime("B1Frame"), OBJ = runtime("B1Object"), THROWN = runtime("B1Thrown");
    private static final ClassDesc ARRAY = runtime("B1Array"), CLOSURE = runtime("B1Closure"), REF = runtime("B1Ref");
    private static final java.lang.constant.DirectMethodHandleDesc LITERAL_BOOTSTRAP = ofConstantBootstrap(
            ClassDesc.of(B1LiteralBlob.class.getName()), "bootstrap", CD_Object, CD_int);
    private static final java.lang.constant.DirectMethodHandleDesc TEXT_BOOTSTRAP = ofConstantBootstrap(
            ClassDesc.of(B1LiteralBlob.class.getName()), "text", CD_Object, CD_String);
    private static final ClassDesc INTS = CD_int.arrayType();
    private static final java.lang.constant.DirectMethodHandleDesc INDICES_BOOTSTRAP = ofConstantBootstrap(
            ClassDesc.of(B1LiteralBlob.class.getName()), "indices", CD_Object, CD_int);
    private static final ClassDesc ARGS = CD_Object.arrayType(), BOOLS = CD_boolean.arrayType(), NAMES = CD_String.arrayType();
    private static final MethodTypeDesc RUN = sig(CD_Object, CTX, OBJ, CALLABLE, ARGS, CD_Object, CD_Object);
    /** A fast function's run: a third fixed argument (invoke3), no step helpers. */
    private static final MethodTypeDesc RUN3 = sig(CD_Object, CTX, OBJ, CALLABLE, ARGS, CD_Object, CD_Object, CD_Object);
    private static final MethodTypeDesc STEP = sig(CD_int, CTX, OBJ, CALLABLE, ARGS, CD_Object, CD_Object, FRAME);
    private static final MethodTypeDesc VALUE = STEP.changeReturnType(CD_Object);
    private static final MethodTypeDesc CTOR = sig(CD_void, BOOLS, NAMES);

    static MethodTypeDesc sig(ClassDesc ret, ClassDesc... args) { return MethodTypeDesc.of(ret, args); }
    /** Process-wide ids of folded symbol names: hidden classes are shared by every context of a code cache, and
     * each context resolves a call site's id into its own table. */

    private static MethodTypeDesc fixed(int n) {
        var args = new ClassDesc[n + 2];
        java.util.Arrays.fill(args, CD_Object);
        args[0] = CTX;
        args[1] = OBJ;
        return sig(CD_Object, args);
    }

    private static final java.util.concurrent.atomic.AtomicInteger PROFILE_SEQ = new java.util.concurrent.atomic.AtomicInteger();
    static byte[] emit(B1Cfg cfg) {
        return emit(cfg, new ArrayList<>());
    }
    static byte[] emit(B1Cfg cfg, List<byte[]> blobs) {
        return build(cfg, blobs, List.of(), Set.of(), new B1Symbols());
    }

    record Chunk(int first, int end) {}
    // A first-pass method above the classfile u2 limit has no parsable Code attribute.
    // JDK 25 reports the measured length and the failing method while writing Code.
    private static final java.util.regex.Pattern CODE_OVERFLOW = java.util.regex.Pattern.compile(
            "^Code length ([0-9]+) is outside the allowed range in (runFiber|runLeaf|run|resumeBody|fragmentBlock([0-9]+))\\(");

    record Emission(byte[] bytes, List<byte[]> blobs, int helpers, int maxCode) {}
    static Emission measured(B1Cfg cfg, int limit) { return measured(cfg, limit, new B1Symbols()); }
    static Emission measured(B1Cfg cfg, int limit, B1Symbols symbols) {
        if (limit <= 0 || limit > 7500) throw new IllegalArgumentException("Invalid bytecode budget " + limit);
        var chunks = new ArrayList<Chunk>();
        var fragmented = new HashSet<Integer>();
        boolean noFast = false, noLeaf = false;
        int leafPad = 0;
        while (true) {
            var blobs = new ArrayList<byte[]>();
            byte[] bytes;
            try { bytes = build(cfg, blobs, chunks, fragmented, symbols, noFast, noLeaf, leafPad); }
            catch (IllegalArgumentException ex) {
                var match = CODE_OVERFLOW.matcher(String.valueOf(ex.getMessage()));
                if (!match.find()) throw ex;
                // A fast function whose frame body (for fibers) does not fit stays on frames altogether.
                if (match.group(2).equals("runFiber")) noFast = true;
                else if (match.group(2).equals("runLeaf")) noLeaf = true;
                else if (chunks.isEmpty()) chunks.add(new Chunk(1,cfg.blocks().size()));
                else if (match.group(3) != null) split(chunks, fragmented, Integer.parseInt(match.group(3)), Integer.parseInt(match.group(1)), limit);
                else throw ex;
                continue;
            }
            if (leafPad == 0) {
                int leaf = leafLength(bytes);
                if (leaf > 0 && leaf < LEAF_INLINE_LIMIT) { leafPad = LEAF_INLINE_LIMIT - leaf; continue; }
            }
            var next = new ArrayList<>(chunks);
            boolean retry = false;
            int max = 0, helpers = 0;
            for (var method : ClassFile.of().parse(bytes).methods()) {
                int length = method.findAttribute(Attributes.code()).orElseThrow().codeLength();
                if (method.methodName().stringValue().startsWith("fragment")) helpers++;
                max = Math.max(max,length);
                if (length <= limit) continue;
                String name = method.methodName().stringValue();
                if (name.equals("runFiber")) { noFast = true; retry = true; }
                else if (name.equals("runLeaf")) { noLeaf = true; retry = true; }
                else if (chunks.isEmpty() && (name.equals("run") || name.equals("resumeBody"))) next.add(new Chunk(1,cfg.blocks().size()));
                else if (name.startsWith("fragmentBlock")) {
                    split(next,fragmented,Integer.parseInt(name.substring("fragmentBlock".length())),length,limit);
                } else throw new IllegalArgumentException(name + " requires " + length + " bytes (budget " + limit + ")");
            }
            if (max <= limit) return new Emission(bytes, blobs, helpers, max);
            if (!retry) chunks = next;
        }
    }

    /** C2 inlines a hot callee of up to FreqInlineSize (325) bytecodes into its caller. A leaf body inlined into
     * a large caller gets what is left of the caller's inlining budget (DesiredMethodLimit), and its own runtime
     * helpers then stay calls: spectralnorm's k0_A ran twice as slow inlined into k0_Av as compiled alone. A
     * shorter leaf body is padded (with unreachable code) to this size, so it always compiles as its own unit. */
    static final int LEAF_INLINE_LIMIT = 326;
    private static int leafLength(byte[] bytes) {
        for (var method : ClassFile.of().parse(bytes).methods())
            if (method.methodName().stringValue().equals("runLeaf")) return method.findAttribute(Attributes.code()).orElseThrow().codeLength();
        return 0;
    }

    private static void split(List<Chunk> chunks, Set<Integer> fragmented, int start, int length, int limit) {
        for (int i = 0; i < chunks.size(); i++) {
            Chunk chunk = chunks.get(i);
            if (chunk.first != start) continue;
            int mid = (start + chunk.end) >>> 1;
            if (mid == start && fragmented.add(start)) return;
            if (mid == start) throw new IllegalArgumentException("CFG block " + start + " requires " + length + " bytes (budget " + limit + ")");
            chunks.set(i,new Chunk(start,mid));
            chunks.add(i + 1,new Chunk(mid,chunk.end));
            return;
        }
        throw new IllegalStateException("Unknown outline region " + start);
    }

    private static String dispatcher(ClassBuilder cb, ClassDesc cls, List<Chunk> chunks) {
        if (chunks.size() == 1) return "fragmentBlock" + chunks.getFirst().first;
        int mid = chunks.size() / 2;
        String left = dispatcher(cb, cls, chunks.subList(0,mid));
        String right = dispatcher(cb, cls, chunks.subList(mid,chunks.size()));
        String name = "dispatchRegion" + chunks.get(mid).first;
        cb.withMethodBody(name,STEP,ACC_PRIVATE | ACC_STATIC,c -> {
            Label upper = c.newLabel();
            c.aload(6).getfield(FRAME,"pc",CD_int).loadConstant(chunks.get(mid).first).if_icmpge(upper);
            step(c,cls,left,6); c.ireturn().labelBinding(upper);
            step(c,cls,right,6); c.ireturn();
        });
        return name;
    }

    private static void step(CodeBuilder c, ClassDesc cls, String name, int frame) {
        for (int i = 0; i < 6; i++) c.aload(i);
        c.aload(frame).invokestatic(cls,name,STEP);
    }
    private static byte[] build(B1Cfg cfg, List<byte[]> blobs, List<Chunk> chunks, Set<Integer> fragmented, B1Symbols symbols) {
        return build(cfg, blobs, chunks, fragmented, symbols, false, false, 0);
    }
    private static byte[] build(B1Cfg cfg, List<byte[]> blobs, List<Chunk> chunks, Set<Integer> fragmented, B1Symbols symbols,
                                boolean noFast, boolean noLeaf, int leafPad) {
        ClassDesc cls = ClassDesc.of(B1Compiler.PROFILE_CLASSES ? "pl.codeloom.php.b1.P" + PROFILE_SEQ.incrementAndGet() + "_"
                + (cfg.function().owner() == null ? "" : cfg.function().owner() + "__") + cfg.function().name().replaceAll("[^A-Za-z0-9_]", "_")
                : "pl.codeloom.php.b1.Compiled" + cfg.function().id());
        var fragments = new Fragments(cls,fragmented,symbols);
        fragments.leafPad = leafPad;
        return ClassFile.of().build(cls, cb -> {
            cb.withFlags(ACC_FINAL | ACC_SUPER).withSuperclass(CALLABLE);
            cb.withMethodBody("<init>", CTOR, ACC_PUBLIC, c ->
                    c.aload(0).aload(1).aload(2).invokespecial(CALLABLE, "<init>", CTOR).return_());
            String dispatch = chunks.isEmpty() ? (cfg.function().generator() ? "resumeBody" : null) : dispatcher(cb,cls,chunks);
            boolean fast = !noFast && dispatch == null && fastEligible(cfg);
            MethodTypeDesc run = fast ? RUN3 : RUN;
            int fixedArgs = fast ? 3 : 2;
            boolean leaf = fast && !noLeaf && fragmented.isEmpty() && leafEligible(cfg);
            int params = cfg.function().params().size();
            cb.withMethodBody("invoke", sig(CD_Object, CTX, OBJ, ARGS), ACC_PUBLIC, c -> {
                c.aload(1).aload(2).aload(0).aload(3);
                for (int i = 0; i < fixedArgs; i++) c.aconst_null();
                c.invokestatic(cls, "run", run).areturn();
            });
            for (int n = 0; n <= fixedArgs; n++) {
                int arity = n;
                cb.withMethodBody("invoke" + arity, fixed(arity), ACC_PUBLIC, c -> {
                if (leaf && arity == params) {
                    // Exactly the parameters, positionally: the leaf entry (which can still decline, see enterLeaf).
                    Label ordinary = c.newLabel();
                    for (int i = 0; i < arity; i++) c.aload(3 + i).invokestatic(CALLABLE,"positional",sig(CD_boolean,CD_Object)).ifeq(ordinary);
                    c.aload(1).aload(2).aload(0).aconst_null();
                    for (int i = 0; i < fixedArgs; i++) { if (i < arity) c.aload(3 + i); else c.getstatic(CALLABLE,"OMITTED",CD_Object); }
                    c.invokestatic(cls, "runLeaf", run).areturn();
                    c.labelBinding(ordinary);
                }
                c.aload(1).aload(2).aload(0).aconst_null();
                for (int i = 0; i < fixedArgs; i++) { if (i < arity) c.aload(3 + i); else c.getstatic(CALLABLE,"OMITTED",CD_Object); }
                c.invokestatic(cls, "run", run).areturn();
                });
            }
            if (cfg.function().generator() && chunks.isEmpty()) {
                cb.withMethodBody(dispatch,STEP,ACC_PRIVATE | ACC_STATIC,c ->
                        body(c,cls,in -> new Body(cfg,in,blobs,new Chunk(1,cfg.blocks().size()),fragments),b -> b.emit(cls,null)));
            }
            if (cfg.function().generator()) {
                cb.withMethodBody("resumeFrame",sig(CD_int,CTX,OBJ,FRAME),ACC_PUBLIC,c ->
                    c.aload(1).aload(2).aload(0).aconst_null().aconst_null().aconst_null().aload(3)
                            .invokestatic(cls,dispatch,STEP).ireturn());
            }
            cb.withMethodBody("run", run, ACC_PRIVATE | ACC_STATIC, c -> body(c,cls,in -> new Body(cfg,in,blobs,null,fragments,fast),b -> b.emit(cls,dispatch)));
            // Inside a fiber a frame can be parked with the fiber: the cycle collector must see its CVs and
            // temporaries, so a fast function runs its frame-based body there.
            if (fast) cb.withMethodBody("runFiber", RUN, ACC_PRIVATE | ACC_STATIC, c -> body(c,cls,in -> new Body(cfg,in,blobs,null,fragments,false),b -> b.emit(cls,null)));
            if (leaf) cb.withMethodBody("runLeaf", RUN3, ACC_PRIVATE | ACC_STATIC, c -> body(c,cls,in -> new Body(cfg,in,blobs,null,fragments,true,true),b -> b.emit(cls,null)));
            for (var chunk : chunks) cb.withMethodBody("fragmentBlock" + chunk.first, STEP, ACC_PRIVATE | ACC_STATIC,
                    c -> body(c,cls,in -> new Body(cfg,in,blobs,chunk,fragments),b -> b.emit(cls,null)));
            for (int i = 0; i < fragments.methods.size(); i++) {
                var method = fragments.methods.get(i);
                cb.withMethodBody(method.name(),method.signature(),ACC_PRIVATE | ACC_STATIC,c ->
                    body(c,cls,in -> new Body(cfg,in,blobs,new Chunk(0,0),fragments),body -> {
                        body.fragment = true;
                        method.emit().accept(body);
                    }));
            }
        });
    }

    /** Emits a Body through its line-store transform (see {@link LineStores}). */
    private static void body(CodeBuilder outer, ClassDesc cls, java.util.function.Function<CodeBuilder,Body> make,
                             java.util.function.Consumer<Body> emit) {
        var lines = new LineStores(cls);
        outer.transforming(lines, in -> {
            Body body = make.apply(in);
            body.lines = lines;
            lines.frame = body.frame;
            // A chunk or fragment receives the frame as a parameter; run() activates once enter/enterFast stored it.
            lines.active = body.chunk != null;
            emit.accept(body);
        });
    }

    /** Lazy {@code frame.line} stores. PHP observes the current line only from inside a call (diagnostics, traces,
     * a callee's "called in" location, destructors), so line(n) only records it and the store is emitted before
     * the next call that can observe it. Every control transfer and merge point also stores a pending line, so
     * at each label frame.line holds exactly what eager stores would have left there. */
    static final class LineStores implements CodeTransform {
        static final int NONE = Integer.MIN_VALUE;
        final ClassDesc cls;
        int frame;
        boolean active;
        /** A leaf body: its line lives in the context until its frame exists (B1RequestContext.leafLine). */
        boolean leaf;
        /** The line frame.line holds here (NONE: unknown), and the line it should hold (NONE: it already does). */
        int known = NONE, want = NONE;
        /** Labels that are no merge points: block starts reached only by fall-through, ends of exception ranges. */
        final Set<Label> transparent = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        LineStores(ClassDesc cls) { this.cls = cls; }

        void flush(CodeBuilder b) {
            if (!active || want == NONE) return;
            if (want != known) {
                if (leaf) b.aload(0).loadConstant(want).aload(2).invokevirtual(CTX, "leafLine", sig(CD_void, CD_int, CALLABLE));
                else b.aload(frame).loadConstant(want).putfield(FRAME, "line", CD_int);
                known = want;
            }
            want = NONE;
        }
        /** Instructions emitted so far (an exception range must not be empty). */
        int emitted;
        @Override public void accept(CodeBuilder b, CodeElement e) {
            if (e instanceof Instruction) emitted++;
            switch (e) {
                case java.lang.classfile.instruction.LabelTarget t -> {
                    if (!transparent.contains(t.label())) {
                        flush(b);
                        known = NONE;
                    }
                    b.with(e);
                }
                case java.lang.classfile.instruction.InvokeInstruction i -> {
                    if (!pure(i)) flush(b);
                    b.with(e);
                    // A helper of this class (fragment, region, fiber body) can store lines of its own.
                    if (i.owner().asSymbol().equals(cls)) known = NONE;
                }
                case java.lang.classfile.instruction.BranchInstruction ignored -> { flush(b); b.with(e); }
                case java.lang.classfile.instruction.TableSwitchInstruction ignored -> { flush(b); b.with(e); }
                case java.lang.classfile.instruction.LookupSwitchInstruction ignored -> { flush(b); b.with(e); }
                case java.lang.classfile.instruction.ReturnInstruction ignored -> { flush(b); b.with(e); }
                case java.lang.classfile.instruction.ThrowInstruction ignored -> { flush(b); b.with(e); }
                default -> b.with(e);
            }
        }
        /** Runtime calls that can neither report, throw a PHP error, nor run PHP code. */
        private static final Set<String> PURE = Set.of("java/lang/Long.valueOf", "java/lang/Double.valueOf",
                "java/lang/Boolean.valueOf", "java/util/Objects.nonNull", "java/util/Objects.isNull",
                "pl/codeloom/php/b1/runtime/B1Object.retain", "pl/codeloom/php/b1/runtime/B1Frame.quiet",
                "pl/codeloom/php/b1/runtime/B1Frame.box", "pl/codeloom/php/b1/runtime/B1RequestContext.truth",
                "pl/codeloom/php/b1/runtime/B1RequestContext.identicalTest", "pl/codeloom/php/b1/runtime/B1Builtins.isString",
                "pl/codeloom/php/b1/runtime/B1Builtins.isObject", "pl/codeloom/php/b1/runtime/B1RequestContext.inFiber",
                "pl/codeloom/php/b1/runtime/B1RequestContext.leafArgument", "pl/codeloom/php/b1/runtime/B1RequestContext.leafMirror",
                "pl/codeloom/php/b1/runtime/B1Callable.positional");
        private static boolean pure(java.lang.classfile.instruction.InvokeInstruction i) {
            String key = i.owner().asInternalName() + "." + i.name().stringValue();
            // The static B1Frame.temp(Object,boolean) counts a value; the instance temp(int) only reads a slot.
            if (key.equals("pl/codeloom/php/b1/runtime/B1Frame.temp")) return true;
            return PURE.contains(key);
        }
    }

    /** A function whose CVs and temporaries can live in JVM locals: nothing addresses them by name or across
     * suspensions (no eval, include, generator, static variables or by-reference places of this extended set). */
    static boolean fastEligible(B1Cfg cfg) {
        IrFunction f = cfg.function();
        if (f.generator() || f.fileBody() || Boolean.getBoolean("b1.slow")) return false;
        for (var block : cfg.blocks()) {
            switch (block.op()) {
                case B1Cfg.YieldPoint ignored -> { return false; }
                case B1Cfg.Receive ignored -> { return false; }
                case B1Cfg.ResolveCall ignored -> { return false; }
                case B1Cfg.SendCall ignored -> { return false; }
                case B1Cfg.InvokeCall ignored -> { return false; }
                default -> {}
            }
        }
        return f.body().stream().allMatch(B1ClassFileEmitter::fastNode);
    }
    /** A leaf function runs without a frame until something needs one (B1RequestContext.enterLeaf): up to three
     * plain untyped parameters, no captures or return type, and a body that calls no PHP code (only intrinsics),
     * never addresses its CVs by name and has no try/finally. Bounded in size: the leaf body is a second copy. */
    static boolean leafEligible(B1Cfg cfg) {
        IrFunction f = cfg.function();
        if (Boolean.getBoolean("b1.noLeaf") || f.params().size() > 3 || !f.captures().isEmpty() || f.returnType() != null
                || cfg.blocks().size() > 160) return false;
        for (Param p : f.params()) if (p.byRef() || p.variadic() || p.type() != null) return false;
        return f.body().stream().allMatch(B1ClassFileEmitter::leafNode);
    }
    private static final Set<Class<?>> LEAF_NODES = Set.of(Region.class, Branch.class, Loop.class, Each.class, Jump.class,
            Mark.class, LoopExit.class, Test.class, ReleaseTemps.class, ReleaseTemp.class, Item.class, Argument.class,
            Target.class, pl.codeloom.php.ir.IrSourceSpan.class,
            Write.class, Return.class, Evaluate.class, Echo.class, WriteProperty.class, WriteNamedProperty.class,
            WriteDim.class, Unset.class, CoalesceDim.class,
            Literal.class, Read.class, QuietRead.class, LoadTemp.class, StoreTemp.class, Prepared.class, ArrayValue.class,
            Dim.class, IsSet.class, Not.class, Choose.class, Update.class, Cast.class, Increment.class, IncrementPlace.class,
            Binary.class, Property.class, QuietProperty.class, This.class, Assign.class, InstanceOf.class, Constant.class);
    private static boolean leafNode(Object node) {
        if (node == null) return true;
        if (node instanceof List<?> list) return list.stream().allMatch(B1ClassFileEmitter::leafNode);
        if (!(node instanceof Record record)) return true;
        if (node instanceof Call call) { if (!Body.intrinsicCall(call)) return false; }
        else if (!LEAF_NODES.contains(node.getClass())) return false;
        for (var component : record.getClass().getRecordComponents()) {
            try {
                if (!leafNode(component.getAccessor().invoke(record))) return false;
            } catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
        }
        return true;
    }
    /** An expression whose value is never counted (null, bool, int, float or string): retaining or releasing it
     * does nothing, so a temporary holding only such values needs neither. */
    static boolean scalar(Expr e) {
        return switch (e) {
            case Literal ignored -> true;
            case IsSet ignored -> true;
            case Not ignored -> true;
            case InstanceOf ignored -> true;
            case Cast ignored -> true;
            case Increment ignored -> true;
            case IncrementPlace ignored -> true;
            // + also unions arrays; ?? and ?: pass an operand through.
            case Binary b -> b.op() != Operator.ADD && !b.preservesValue();
            case Update u -> scalar(u.value());
            case Assign a -> scalar(a.value());
            case Choose c -> scalar(c.yes()) && scalar(c.no());
            case Call call when Body.intrinsicCall(call) -> switch (lower(call.target().name())) {
                case "count", "sizeof", "strlen", "is_array", "is_string", "is_int", "is_null", "is_object",
                     "array_key_exists", "trim", "ucfirst", "key" -> true;
                default -> false;
            };
            default -> false;
        };
    }
    /** The temporaries that can hold a counted value: written by a non-scalar StoreTemp, a send, or an iterator.
     * The rest hold scalars only, whose stores and releases emit nothing. */
    static Set<Integer> countedTemps(IrFunction f) {
        var counted = new HashSet<Integer>();
        for (int i = 0; i < f.temps().size(); i++) if (f.temps().get(i) != TempKind.VALUE) counted.add(i);
        countedTemps(f.body(), counted);
        return counted;
    }
    private static void countedTemps(Object node, Set<Integer> counted) {
        if (node instanceof List<?> list) { for (var item : list) countedTemps(item, counted); return; }
        if (!(node instanceof Record record)) return;
        switch (node) {
            case StoreTemp t when !scalar(t.value()) -> counted.add(t.id());
            case Argument a when a.bindVariable() || !scalar(a.value()) -> counted.add(a.slot());
            case Each e -> counted.add(e.iterator());
            default -> {}
        }
        for (var component : record.getClass().getRecordComponents()) {
            try { countedTemps(component.getAccessor().invoke(record), counted); }
            catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
        }
    }
    /** CVs that can come to hold a binding (B1Ref): by-reference parameters and captures, =&, global, static, and
     * every CV a place is fetched from (dimension writes, by-reference sends and array items, ++ and op= on
     * dimensions). Every other CV holds UNDEF or a plain value. */
    static Set<Integer> boxableCvs(IrFunction f) {
        var boxable = new HashSet<Integer>();
        for (int i = 0; i < f.params().size(); i++) if (f.params().get(i).byRef()) boxable.add(i);
        for (var capture : f.captures()) if (capture.byRef()) boxable.add(capture.inner());
        boxableCvs(f.body(), boxable);
        return boxable;
    }
    private static void boxableCvs(Object node, Set<Integer> boxable) {
        if (node instanceof List<?> list) { for (var item : list) boxableCvs(item, boxable); return; }
        if (!(node instanceof Record record)) return;
        switch (node) {
            case Bind b -> { boxable.add(b.local()); placeRoot(b.place(), boxable); }
            case Global g -> boxable.add(g.local());
            case StaticVar v -> boxable.add(v.local());
            case CoalesceDim d -> boxable.add(d.local());
            case Unset u when u.key() != null -> placeRoot(u.root(), boxable);
            case WriteDim d -> placeRoot(d.root(), boxable);
            case IncrementPlace p -> placeRoot(p.place(), boxable);
            case UpdatePlace u -> placeRoot(u.root(), boxable);
            case ByReference r -> placeRoot(r.place(), boxable);
            case Argument a when a.bindVariable() -> placeRoot(a.value(), boxable);
            case Closure c -> { for (var capture : c.captures()) if (capture.byRef()) boxable.add(capture.outer()); }
            default -> {}
        }
        for (var component : record.getClass().getRecordComponents()) {
            try { boxableCvs(component.getAccessor().invoke(record), boxable); }
            catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
        }
    }
    private static void placeRoot(Expr e, Set<Integer> boxable) {
        switch (e) {
            case Prepared p -> placeRoot(p.result(), boxable);
            case StoreTemp t -> placeRoot(t.value(), boxable);
            case Dim d -> placeRoot(d.base(), boxable);
            case Read r -> boxable.add(r.local());
            case QuietRead r -> boxable.add(r.local());
            default -> {}
        }
    }
    /** Per block: the CVs definitely assigned (not UNDEF) on entry. A must-analysis over every CFG edge; only
     * whole statements assign (Write, a top-level assignment), plus foreach and catch bindings; unset removes. */
    static java.util.BitSet[] definedCvs(B1Cfg cfg) {
        var blocks = cfg.blocks();
        int n = blocks.size(), cvs = cfg.function().locals().size();
        var in = new java.util.BitSet[n];
        for (var block : blocks) {
            // A finally region resumes suspended exits later: no facts there.
            if (block.op() instanceof B1Cfg.Suspend || block.op() instanceof B1Cfg.Resume || block.op() instanceof B1Cfg.Cancel) {
                for (int i = 0; i < n; i++) in[i] = new java.util.BitSet();
                return in;
            }
        }
        for (int i = 0; i < n; i++) { in[i] = new java.util.BitSet(); in[i].set(0, cvs); }
        in[cfg.entry()].clear();
        var work = new java.util.ArrayDeque<Integer>();
        for (int i = 0; i < n; i++) work.add(i);
        var queued = new boolean[n];
        java.util.Arrays.fill(queued, true);
        while (!work.isEmpty()) {
            int id = work.poll();
            queued[id] = false;
            var block = blocks.get(id);
            var entry = in[id];
            var out = (java.util.BitSet) entry.clone();
            var kept = (java.util.BitSet) entry.clone();
            switch (block.op()) {
                case B1Cfg.Enter ignored -> {
                    IrFunction f = cfg.function();
                    for (int i = 0; i < f.params().size(); i++) out.set(i);
                    for (var capture : f.captures()) out.set(capture.inner());
                }
                case B1Cfg.Execute(var at, var action) -> {
                    switch (action) {
                        case Write w -> { out.set(w.local()); assignedChain(w.value(), out); }
                        case Evaluate(Assign a) -> { out.set(a.local()); assignedChain(a.value(), out); }
                        case Evaluate(Update u) -> out.set(u.local());
                        case Evaluate(Increment i) -> out.set(i.local());
                        case Unset(Read r, var key) when key == null -> { out.clear(r.local()); kept.clear(r.local()); }
                        default -> {}
                    }
                }
                case B1Cfg.Catch(var type, var local) -> out.set(local);
                default -> {}
            }
            if (block.normal() >= 0) {
                var normal = out;
                if (block.op() instanceof B1Cfg.NextEach(var each, var end)) {
                    normal = (java.util.BitSet) entry.clone();
                    normal.set(each.value());
                    if (each.key() >= 0) normal.set(each.key());
                }
                flow(in, block.normal(), normal, work, queued);
            }
            if (block.exceptional() >= 0) flow(in, block.exceptional(), kept, work, queued);
            switch (block.op()) {
                case B1Cfg.TestBranch(var test, var no) -> flow(in, no, out, work, queued);
                case B1Cfg.NextEach(var each, var end) -> flow(in, end, entry, work, queued);
                default -> {}
            }
        }
        return in;
    }
    /** $a = $b = $c = v: the nested assignments of a completed statement's value happened too. */
    private static void assignedChain(Expr value, java.util.BitSet out) {
        while (true) {
            if (value instanceof StoreTemp(var temp, var inner)) value = inner;
            else if (value instanceof Assign(var id, var inner)) { out.set(id); value = inner; }
            else return;
        }
    }
    private static void flow(java.util.BitSet[] in, int target, java.util.BitSet state, java.util.ArrayDeque<Integer> work, boolean[] queued) {
        var next = (java.util.BitSet) in[target].clone();
        next.and(state);
        if (next.equals(in[target])) return;
        in[target] = next;
        if (!queued[target]) { queued[target] = true; work.add(target); }
    }
    // ---------------------------------------------------------------- unboxed float CVs
    // PHP's + - * / with a float operand yield a float or throw (an array or object operand, division by zero),
    // whatever the other operand is. A CV whose every write is such an expression, that is read only where it is
    // definitely assigned and is never addressed (no reference, no isset/++/unset/foreach/catch binding), always
    // holds a float: it lives in a JVM double local. Float-only arithmetic on such operands is emitted as JVM
    // double arithmetic; the rest still runs through the runtime helper, whose (float) result is unboxed.
    record Doubles(Set<Integer> cvs, Set<Integer> loadedTemps) {
        static final Doubles NONE = new Doubles(Set.of(), Set.of());
        /** Statically a float: a float literal, a float CV, or float arithmetic. */
        boolean of(Expr e) {
            return switch (e) {
                case Literal(var value) -> value instanceof Double;
                case Read(var id) -> cvs.contains(id);
                case StoreTemp(var id, var value) -> !loadedTemps.contains(id) && of(value);
                case Binary b -> arithmetic(b.op()) && (of(b.left()) || of(b.right()));
                case Choose ch -> of(ch.yes()) && of(ch.no());
                case Assign(var id, var value) -> cvs.contains(id);
                case Update(var id, var value) -> cvs.contains(id);
                default -> false;
            };
        }
        /** A double operand: statically a float, or an int literal (converted at compile time). */
        boolean operand(Expr e) { return of(e) || e instanceof Literal(Long n); }
        /** Float arithmetic computed with a JVM double operation: both operands are double operands (each one
         * computes itself, through the runtime only where it has to). */
        boolean direct(Binary b) {
            return arithmetic(b.op()) && operand(b.left()) && operand(b.right()) && (of(b.left()) || of(b.right()));
        }
        static boolean arithmetic(Operator op) {
            return op == Operator.ADD || op == Operator.SUB || op == Operator.MUL || op == Operator.DIV;
        }
    }
    static Doubles doubleCvs(B1Cfg cfg, Set<Integer> boxable, java.util.BitSet[] defined) {
        IrFunction f = cfg.function();
        if (Boolean.getBoolean("b1.noDoubles") || defined == null) return Doubles.NONE;
        var candidates = new HashSet<Integer>();
        for (int i = f.params().size(); i < f.locals().size(); i++) candidates.add(i);
        candidates.removeAll(boxable);
        candidates.removeAll(cfg.longLocals());
        for (var capture : f.captures()) candidates.remove(capture.inner());
        var loaded = new HashSet<Integer>();
        var writes = new HashMap<Integer, List<Expr>>();
        doubleUses(f.body(), candidates, loaded, writes);
        // Each read must see an assigned CV (an undefined one reads as null, with a warning).
        for (var block : cfg.blocks()) {
            var reads = new HashSet<Integer>();
            cvReads(block.op(), reads);
            for (int id : reads) if (!defined[block.label()].get(id)) candidates.remove(id);
        }
        candidates.removeIf(id -> !writes.containsKey(id));
        while (true) {
            var typed = new Doubles(Set.copyOf(candidates), Set.copyOf(loaded));
            if (!candidates.removeIf(id -> !writes.get(id).stream().allMatch(typed::of))) return typed;
        }
    }
    /** Collects the writes of candidate CVs, drops the ones used in any other way, and the LoadTemp slots. */
    private static void doubleUses(Object node, Set<Integer> candidates, Set<Integer> loaded, Map<Integer, List<Expr>> writes) {
        if (node instanceof List<?> list) { for (var item : list) doubleUses(item, candidates, loaded, writes); return; }
        if (!(node instanceof Record record)) return;
        switch (node) {
            case Write(var id, var value) -> writes.computeIfAbsent(id, k -> new ArrayList<>()).add(value);
            case Assign(var id, var value) -> writes.computeIfAbsent(id, k -> new ArrayList<>()).add(value);
            case Update(var id, var value) -> writes.computeIfAbsent(id, k -> new ArrayList<>()).add(value);
            case LoadTemp(var id) -> loaded.add(id);
            case QuietRead(var id) -> candidates.remove(id);
            case Increment(var id, var up, var prefix) -> candidates.remove(id);
            case Unset(Read(var id), var key) -> candidates.remove(id);
            case Each e -> { candidates.remove(e.value()); candidates.remove(e.key()); }
            case TryCatch t -> candidates.remove(t.variable());
            case CoalesceDim d -> candidates.remove(d.local());
            default -> {}
        }
        for (var component : record.getClass().getRecordComponents()) {
            try { doubleUses(component.getAccessor().invoke(record), candidates, loaded, writes); }
            catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
        }
    }
    /** The CVs a CFG operation reads (an op= reads its target, a closure its by-value captures). */
    private static void cvReads(Object node, Set<Integer> reads) {
        if (node instanceof List<?> list) { for (var item : list) cvReads(item, reads); return; }
        if (!(node instanceof Record record)) return;
        if (node instanceof Read(var id)) reads.add(id);
        if (node instanceof Update(var id, var value)) reads.add(id);
        // use ($v) reads $v when the closure is created (an undefined one warns and captures null).
        if (node instanceof Capture cap && !cap.byRef()) reads.add(cap.outer());
        // A foreach body is lowered into its own blocks; its operation carries only the source.
        if (node instanceof B1Cfg.StartEach(var each)) { cvReads(each.source(), reads); return; }
        if (node instanceof B1Cfg.NextEach) return;
        for (var component : record.getClass().getRecordComponents()) {
            try { cvReads(component.getAccessor().invoke(record), reads); }
            catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
        }
    }
    private static boolean fastNode(Object node) {
        if (node == null) return true;
        if (node instanceof Eval || node instanceof Include || node instanceof ByReference || node instanceof Yield
                || node instanceof StaticVar) return false;
        if (node instanceof List<?> list) return list.stream().allMatch(B1ClassFileEmitter::fastNode);
        if (node instanceof Record record) {
            for (var component : record.getClass().getRecordComponents()) {
                try {
                    if (!fastNode(component.getAccessor().invoke(record))) return false;
                } catch (ReflectiveOperationException ex) { throw new IllegalStateException(ex); }
            }
        }
        return true;
    }

    /** Oversized single blocks factor their expression trees at value boundaries. All PHP
     * owners stay in the original frame, including sends in progress and protected receivers. */
    private static final class Fragments {
        final ClassDesc cls;
        final Set<Integer> blocks;
        record Helper(String name, MethodTypeDesc signature, java.util.function.Consumer<Body> emit) {}
        final List<Helper> methods = new ArrayList<>();
        final Map<Expr,String> ids = new IdentityHashMap<>();
        final B1Symbols symbols;
        /** Unreachable bytes appended to runLeaf (see LEAF_INLINE_LIMIT). */
        int leafPad;
        Fragments(ClassDesc cls, Set<Integer> blocks, B1Symbols symbols) { this.cls = cls; this.blocks = blocks; this.symbols = symbols; }
        String helper(MethodTypeDesc type, java.util.function.Consumer<Body> emit) {
            String name = "fragment" + methods.size();
            methods.add(new Helper(name,type,emit));
            return name;
        }
        String value(Expr e) {
            return ids.computeIfAbsent(e, ignored -> helper(VALUE, body -> { body.inline(e); body.c.areturn(); }));
        }
    }

    private static final class Body {
        // run(ctx, self, callable, argv, arg0, arg1); fixed-arity entry points leave argv null.
        final B1Cfg cfg;
        final CodeBuilder c;
        final IrFunction f;
        final int frame, result, pending;
        final Label[] labels, handlers;
        final Map<Integer, Integer> longs = new HashMap<>();

        final List<byte[]> blobs;
        final Chunk chunk;
        final Fragments fragments;
        boolean fragment;
        final java.util.Set<Integer> edges = new java.util.TreeSet<>();
        /** Fast mode: CV i lives in JVM local cvs[i], temporary i in temps[i]. */
        final boolean fast;
        final int[] cvs, temps;
        int scratchValue, scratchOld;
        LineStores lines;
        Body(B1Cfg cfg, CodeBuilder c, List<byte[]> blobs, Chunk chunk, Fragments fragments) {
            this(cfg,c,blobs,chunk,fragments,false);
        }
        /** Leaf mode (runLeaf): a fast body entered without a frame; {@code frame} stays null unless it materialized. */
        final boolean leaf;
        int flags = -1;
        /** Fast mode: temporaries that can hold counted values (the others are never retained or released). */
        Set<Integer> counted = Set.of();
        /** Fast mode: CVs that can hold a binding, and per block the CVs definitely assigned on entry. A plain,
         * assigned CV is read straight from its local. */
        Set<Integer> boxable = Set.of();
        java.util.BitSet[] defined;
        java.util.BitSet here = new java.util.BitSet();
        /** Fast mode: the float CVs and their double locals. */
        Doubles doubles = Doubles.NONE;
        final Map<Integer,Integer> doubleSlots = new HashMap<>();
        Body(B1Cfg cfg, CodeBuilder c, List<byte[]> blobs, Chunk chunk, Fragments fragments, boolean fast) {
            this(cfg,c,blobs,chunk,fragments,fast,false);
        }
        Body(B1Cfg cfg, CodeBuilder c, List<byte[]> blobs, Chunk chunk, Fragments fragments, boolean fast, boolean leaf) {
            this.fast = fast;
            this.leaf = leaf;
            if (fast) {
                counted = countedTemps(cfg.function());
                boxable = boxableCvs(cfg.function());
                defined = definedCvs(cfg);
                doubles = doubleCvs(cfg, boxable, defined);
            }
            this.fragments = fragments;
            this.chunk = chunk;
            this.blobs = blobs;
            this.cfg = cfg;
            this.c = c;
            f = cfg.function();
            frame = chunk == null ? ref() : 6; result = ref(); pending = ref();
            labels = new Label[chunk != null && chunk.end == 0 ? 0 : cfg.blocks().size()];
            handlers = new Label[labels.length];
            for (int i = 0; i < labels.length; i++) labels[i] = c.newLabel();
            cvs = new int[fast ? f.locals().size() : 0];
            temps = new int[fast ? cfg.tempCount() : 0];
            for (int i = 0; i < cvs.length; i++) cvs[i] = ref();
            for (int i = 0; i < temps.length; i++) temps[i] = ref();
            if (fast) { scratchValue = ref(); scratchOld = ref(); }
            if (fast) for (var block : cfg.blocks()) if (block.op() instanceof B1Cfg.StartEach(var each))
                positions.putIfAbsent(each.iterator(), c.allocateLocal(TypeKind.INT));
        }
        /** Fast foreach: the position of each array iterator temporary. */
        final Map<Integer,Integer> positions = new HashMap<>();
        int ref() { return c.allocateLocal(TypeKind.REFERENCE); }
        /** The id of a folded symbol in the registry this unit is linked with. */
        int sym(String folded) { return fragments.symbols.of(folded); }

        // ---- fast-mode CV and temporary primitives
        boolean isParam(int id) { return id < f.params().size(); }
        /** The new CV object on the stack becomes CV id (and the parameter's trace mirror). */
        void setCv(int id) {
            c.astore(cvs[id]);
            if (isParam(id)) mirror(id);
        }
        void mirror(int id) {
            if (leaf) { c.aload(0).aload(cvs[id]).loadConstant(id).aload(2).invokevirtual(CTX,"leafMirror",sig(CD_void,CD_Object,CD_int,CALLABLE)); return; }
            if (id < 4) c.aload(frame).aload(cvs[id]).putfield(FRAME,"m" + id,CD_Object);
            else c.aload(frame).loadConstant(id).aload(cvs[id]).invokevirtual(FRAME,"mirror",sig(CD_void,CD_int,CD_Object));
        }
        boolean plain(int id) { return !boxable.contains(id) && here.get(id); }
        void initDoubles() {
            for (int id : doubles.cvs()) {
                int slot = c.allocateLocal(TypeKind.DOUBLE);
                doubleSlots.put(id, slot);
                c.dconst_0().dstore(slot);
            }
        }
        void boxDouble() { c.invokestatic(CD_Double,"valueOf",sig(CD_Double,CD_double)); }
        /** Pushes a float-typed expression (or an int literal) as a JVM double. */
        void emitDouble(Expr e) {
            switch (e) {
                case Literal(Long n) -> c.loadConstant((double) n);
                case Literal(Double d) -> c.loadConstant(d);
                case Read(var id) -> c.dload(doubleSlots.get(id));
                case StoreTemp(var id, var value) -> emitDouble(value);
                case Assign(var id, var value) -> { emitDouble(value); c.dup2().dstore(doubleSlots.get(id)); }
                case Update(var id, var value) -> { emitDouble(value); c.dup2().dstore(doubleSlots.get(id)); }
                case Choose(var test, var yes, var no) -> {
                    Label otherwise = c.newLabel(), done = c.newLabel();
                    truth(test); c.ifeq(otherwise); emitDouble(yes); c.goto_(done).labelBinding(otherwise); emitDouble(no); c.labelBinding(done);
                }
                case Binary b when doubles.direct(b) -> {
                    doubleOperands(b);
                    switch (b.op()) {
                        case ADD -> c.dadd();
                        case SUB -> c.dsub();
                        case MUL -> c.dmul();
                        default -> {
                            // Division by (signed) zero raises DivisionByZeroError: the runtime helper reports it.
                            int divisor = c.allocateLocal(TypeKind.DOUBLE), dividend = c.allocateLocal(TypeKind.DOUBLE);
                            Label nonzero = c.newLabel(), done = c.newLabel();
                            c.dstore(divisor).dstore(dividend);
                            c.dload(divisor).dconst_0().dcmpl().ifne(nonzero);
                            c.aload(0).dload(dividend); boxDouble(); c.dload(divisor); boxDouble();
                            c.invokevirtual(CTX,"divide",sig(CD_Object,CD_Object,CD_Object)).checkcast(CD_Double)
                                    .invokevirtual(CD_Double,"doubleValue",sig(CD_double)).goto_(done);
                            c.labelBinding(nonzero).dload(dividend).dload(divisor).ddiv().labelBinding(done);
                        }
                    }
                }
                case Binary b -> { binary(b); c.checkcast(CD_Double).invokevirtual(CD_Double,"doubleValue",sig(CD_double)); }
                default -> throw new IllegalStateException("not a float expression: " + e);
            }
        }
        /** Pushes both operands as JVM doubles. A deferred left CV is read after the right operand (which may
         * assign it), as PHP does; a double cannot be swapped on the stack, so the right one waits in a local. */
        void doubleOperands(Binary b) {
            if (!b.deferredLeft()) { emitDouble(b.left()); emitDouble(b.right()); return; }
            int right = c.allocateLocal(TypeKind.DOUBLE);
            emitDouble(b.right()); c.dstore(right);
            emitDouble(b.left()); c.dload(right);
        }
        /** A float CV, an assignment to one (these have no object form) or pure float arithmetic. */
        boolean floatNode(Expr e) {
            return switch (e) {
                case Read(var id) -> doubleSlots.containsKey(id);
                case Assign(var id, var value) -> doubleSlots.containsKey(id);
                case Update(var id, var value) -> doubleSlots.containsKey(id);
                case Binary b -> doubles.direct(b);
                default -> false;
            };
        }
        void readCv(int id) {
            if (doubleSlots.containsKey(id)) { c.dload(doubleSlots.get(id)); boxDouble(); return; }
            if (plain(id)) { c.aload(cvs[id]); return; }
            c.aload(0).aload(cvs[id]).aload(frame).loadConstant(id).invokestatic(FRAME,"read",sig(CD_Object,CTX,CD_Object,FRAME,CD_int));
        }
        void quietCv(int id) {
            if (plain(id)) { c.aload(cvs[id]); return; }
            c.aload(cvs[id]).invokestatic(FRAME,"quiet",sig(CD_Object,CD_Object));
        }
        /** Writes the borrowed value on the stack into CV id. */
        void writeCv(int id) {
            c.astore(scratchValue);
            c.aload(cvs[id]).astore(scratchOld);
            c.aload(0).aload(scratchOld).aload(scratchValue).invokestatic(FRAME,"store",sig(CD_Object,CTX,CD_Object,CD_Object));
            setCv(id);
            c.aload(0).aload(scratchOld).aload(cvs[id]).invokestatic(FRAME,"drop",sig(CD_void,CTX,CD_Object,CD_Object));
        }
        /** Pushes CV id as a place (boxed into its own binding). */
        void placeCv(int id) {
            c.aload(cvs[id]).invokestatic(FRAME,"box",sig(REF,CD_Object)).dup();
            setCv(id);
        }
        /** The value on the stack is stored into temporary id and stays on the stack. */
        void storeTempFast(int id, boolean owned) {
            if (!counted.contains(id)) { c.dup().astore(temps[id]); return; }
            c.loadConstant(owned ? 1 : 0).invokestatic(FRAME,"temp",sig(CD_Object,CD_Object,CD_boolean)).dup().astore(temps[id]);
        }
        void loadTemp(int id) {
            if (fast) c.aload(temps[id]);
            else c.aload(frame).loadConstant(id).invokevirtual(FRAME,"temp",sig(CD_Object,CD_int));
        }
        void releaseTempFast(int id, boolean unwind) {
            if (!counted.contains(id)) return;
            c.aload(0).aload(temps[id]).aconst_null().astore(temps[id]);
            if (unwind) c.aload(pending).invokestatic(FRAME,"unwindOwned",sig(CD_Throwable,CTX,CD_Object,CD_Throwable)).astore(pending);
            else c.invokestatic(FRAME,"releaseOwned",sig(CD_void,CTX,CD_Object));
        }
        void frameCtx() { c.aload(frame).aload(0); }
        Label target(int id) {
            if (chunk != null && (id < chunk.first || id >= chunk.end)) edges.add(id);
            return labels[id];
        }
        void go(int id) { c.goto_(target(id)); }
        void line(int line) {
            lines.want = line;
            if (B1Compiler.PROFILE_CLASSES) c.lineNumber(line); // profilers attribute generated code to PHP lines
        }

        void emit(ClassDesc cls, String dispatcher) {
            if (chunk == null && leaf) {
                Label entered = c.newLabel();
                flags = c.allocateLocal(TypeKind.INT);
                c.aload(0).aload(2).invokevirtual(CTX,"enterLeaf",sig(CD_int,CALLABLE)).dup().istore(flags).ifge(entered);
                for (int i = 0; i < 7; i++) c.aload(i);
                c.invokestatic(cls,"run",RUN3).areturn();
                c.labelBinding(entered);
                c.aconst_null().astore(frame);
                lines.leaf = true;
                lines.active = true;
                lines.known = f.at().line(); // enterLeaf starts the call at its declaration line
                for (int cv : cvs) c.getstatic(FRAME,"UNDEF",CD_Object).astore(cv);
                for (int t : temps) c.aconst_null().astore(t);
                for (int p : positions.values()) c.iconst_0().istore(p);
                c.aconst_null().astore(scratchValue).aconst_null().astore(scratchOld);
                for (int id : cfg.longLocals()) {
                    int slot = c.allocateLocal(TypeKind.LONG);
                    longs.put(id, slot);
                    c.lconst_0().lstore(slot);
                }
                initDoubles();
                fallthrough();
                c.astore(result).aconst_null().astore(pending);
                fallInto = cfg.entry();
            } else if (chunk == null && fast) {
                Label notFiber = c.newLabel(), packed = c.newLabel();
                c.aload(0).invokevirtual(CTX,"inFiber",sig(CD_boolean)).ifeq(notFiber);
                c.aload(3).ifnonnull(packed).aload(6).getstatic(CALLABLE,"OMITTED",CD_Object).if_acmpeq(packed);
                c.aload(0).aload(1).aload(2).aload(4).aload(5).aload(6)
                        .invokestatic(FRAME,"arguments",sig(CD_Object.arrayType(),CD_Object,CD_Object,CD_Object));
                c.aconst_null().aconst_null().invokestatic(cls,"runFiber",RUN).areturn();
                c.labelBinding(packed);
                for (int i = 0; i < 6; i++) c.aload(i);
                c.invokestatic(cls,"runFiber",RUN).areturn();
                c.labelBinding(notFiber);
                c.aload(0).aload(2).ldc(f.at().file()).ldc(f.owner() == null ? f.name() : f.owner() + "::" + f.name())
                        .invokevirtual(CTX, "enterFast", sig(FRAME, CALLABLE, CD_String, CD_String)).astore(frame);
                lines.active = true;
                for (int cv : cvs) c.getstatic(FRAME,"UNDEF",CD_Object).astore(cv);
                for (int t : temps) c.aconst_null().astore(t);
                for (int p : positions.values()) c.iconst_0().istore(p);
                c.aconst_null().astore(scratchValue).aconst_null().astore(scratchOld);
                for (int id : cfg.longLocals()) {
                    int slot = c.allocateLocal(TypeKind.LONG);
                    longs.put(id, slot);
                    c.lconst_0().lstore(slot);
                }
                initDoubles();
                fallthrough();
                c.astore(result).aconst_null().astore(pending);
                fallInto = cfg.entry();
            } else if (chunk == null) {
                c.aload(0).aload(2).ldc(f.at().file()).ldc(f.owner() == null ? f.name() : f.owner() + "::" + f.name()).loadConstant(cfg.tempCount())
                        .loadConstant(f.fileBody() ? 1 : 0)
                        .invokevirtual(CTX, "enter", sig(FRAME, CALLABLE, CD_String, CD_String, CD_int, CD_boolean)).astore(frame);
                lines.active = true;
                if (f.generator()) {
                    // The entry block binds arguments and captures synchronously, even for an
                    // unstarted generator. PHP starts the body only on its first iteration.
                    c.aload(frame).loadConstant(cfg.entry()).putfield(FRAME,"pc",CD_int);
                    step(c,cls,dispatcher,frame);
                    c.aload(0).aload(1).aload(2).aload(frame)
                            .invokestatic(runtime("B1Generator"),"create",sig(runtime("B1Generator"),CD_int,CTX,OBJ,CALLABLE,FRAME)).areturn();
                    return;
                }
                fallthrough();
                c.astore(result).aconst_null().astore(pending);
                if (dispatcher != null) {
                    c.aload(frame).aload(result).putfield(FRAME,"result",CD_Object);
                    c.aload(frame).loadConstant(cfg.entry()).putfield(FRAME,"pc",CD_int);
                    Label loop = c.newBoundLabel();
                    c.aload(frame); step(c,cls,dispatcher,frame);
                    c.dup_x1().putfield(FRAME,"pc",CD_int).ifne(loop);
                    frameCtx(); c.aload(frame).getfield(FRAME,"pending",CD_Throwable)
                            .aload(frame).getfield(FRAME,"result",CD_Object)
                            .invokevirtual(FRAME,"leave",sig(CD_Object,CTX,CD_Throwable,CD_Object)).areturn();
                    return;
                }
                for (int id : cfg.longLocals()) {
                    int slot = c.allocateLocal(TypeKind.LONG);
                    longs.put(id, slot);
                    c.lconst_0().lstore(slot);
                }
                fallInto = cfg.entry();
            } else {
                c.aload(frame).getfield(FRAME,"result",CD_Object).astore(result);
                c.aload(frame).getfield(FRAME,"pending",CD_Throwable).astore(pending);
                if (chunk.end - chunk.first > 1) {
                    var cases = new ArrayList<java.lang.classfile.instruction.SwitchCase>();
                    for (int i = chunk.first; i < chunk.end; i++) cases.add(java.lang.classfile.instruction.SwitchCase.of(i,labels[i]));
                    c.aload(frame).getfield(FRAME,"pc",CD_int).tableswitch(chunk.first,chunk.end-1,labels[chunk.first],cases);
                }
            }
            List<B1Cfg.Block> order = emissionOrder();
            int[] incoming = incoming();
            // A chunk's tableswitch reaches each of its blocks.
            boolean dispatched = chunk != null && chunk.end - chunk.first > 1;
            if (fallInto >= 0) {
                if (order.isEmpty() || order.getFirst().label() != fallInto) go(fallInto);
                else if (incoming[fallInto] == 1) lines.transparent.add(labels[fallInto]);
            }
            for (int k = 0; k < order.size(); k++) {
                var block = order.get(k);
                int next = k + 1 < order.size() ? order.get(k + 1).label() : -1;
                fragment = fragments.blocks.contains(block.label());
                here = defined == null ? new java.util.BitSet() : defined[block.label()];
                Label start = labels[block.label()];
                // Adjacent blocks unwinding to the same handler share one exception-table range. Leave and Resume
                // run unprotected; an empty Pass block can neither throw nor end a range.
                boolean neutral = block.op() instanceof B1Cfg.Pass;
                if (block.op() instanceof B1Cfg.Leave || block.op() instanceof B1Cfg.Resume) closeRange();
                else if (!neutral && rangeTarget != block.exceptional()) {
                    closeRange();
                    rangeStart = start;
                    rangeTarget = block.exceptional();
                }
                c.labelBinding(start);
                int before = lines.emitted;
                switch (block.op()) {
                    case B1Cfg.Leave ignored when leaf -> {
                        // The caller's discard first (beginLeave's order), then: never materialized, the call is
                        // unlinked and its CVs released here; otherwise its frame leaves as a fast frame does.
                        Label kept = c.newLabel(), deferred = c.newLabel();
                        c.iload(flags).iconst_2().iand().ifeq(kept).aload(pending).ifnonnull(kept);
                        c.aload(0).aload(result).invokestatic(FRAME,"discarded",sig(CD_Throwable,CTX,CD_Object)).astore(pending)
                                .aconst_null().astore(result);
                        c.labelBinding(kept);
                        c.aload(0).aload(2).invokevirtual(CTX,"leafFrame",sig(FRAME,CALLABLE)).astore(frame);
                        c.aload(frame).ifnull(deferred);
                        fastLeave();
                        c.labelBinding(deferred);
                        for (int cv : cvs) c.aload(0).aload(cv).aload(pending)
                                .invokestatic(FRAME,"unwindOwned",sig(CD_Throwable,CTX,CD_Object,CD_Throwable)).astore(pending);
                        c.aload(0).aload(pending).aload(result).invokestatic(FRAME,"leafResult",sig(CD_Object,CTX,CD_Throwable,CD_Object)).areturn();
                        continue;
                    }
                    case B1Cfg.Leave ignored when fast -> {
                        fastLeave();
                        continue;
                    }
                    case B1Cfg.Leave ignored -> {
                        frameCtx();
                        c.aload(pending).aload(result).invokevirtual(FRAME, "leave", sig(CD_Object, CTX, CD_Throwable, CD_Object)).areturn();
                        continue;
                    }
                    case B1Cfg.Enter ignored -> {
                        bind();
                        if (f.generator()) { c.loadConstant(block.normal()).ireturn(); }
                    }
                    case B1Cfg.YieldPoint(var key,var value,var delegate,var clean) -> {
                        saveFlight();
                        c.aload(frame).getfield(FRAME,"generator",runtime("B1Generator")).aload(0);
                        if (key == null) c.getstatic(CALLABLE,"OMITTED",CD_Object); else expr(key);
                        expr(value); c.loadConstant(delegate ? 1 : 0).loadConstant(block.normal())
                                .invokevirtual(runtime("B1Generator"),"pause",sig(CD_int,CTX,CD_Object,CD_Object,CD_boolean,CD_int));
                        if (!clean.slots().isEmpty()) {
                            int status = c.allocateLocal(TypeKind.INT);
                            c.istore(status);
                            cleanup(clean,false);
                            c.iload(status);
                        }
                        c.ireturn();
                    }
                    case B1Cfg.Receive(var slot) -> {
                        frameCtx(); c.loadConstant(slot).aload(frame).getfield(FRAME,"generator",runtime("B1Generator"))
                                .invokevirtual(runtime("B1Generator"),"receive",sig(CD_Object)).iconst_1()
                                .invokevirtual(FRAME,"storeTemp",sig(CD_Object,CTX,CD_int,CD_Object,CD_boolean)).pop();
                    }
                    case B1Cfg.ResolveCall(var target,var calleeSlot) -> {
                        frameCtx(); c.loadConstant(calleeSlot);
                        resolve(target,ref(),ref()); c.iconst_0().invokevirtual(FRAME,"storeTemp",sig(CD_Object,CTX,CD_int,CD_Object,CD_boolean)).pop();
                    }
                    case B1Cfg.SendCall(var arg,var index,var slot) -> send(arg,index,callee(slot));
                    case B1Cfg.InvokeCall(var call,var slot,var dest) -> {
                        int calleeLocal = callee(slot);
                        frameCtx(); c.loadConstant(dest);
                        Target t = call.target();
                        int receiver = ref(), dynamic = ref();
                        if (t.kind() == Dispatch.DYNAMIC) {
                            expr(t.receiver()); c.astore(dynamic).aload(0).aload(dynamic).invokevirtual(CTX,"receiver",sig(OBJ,CD_Object));
                        } else if (t.kind() == Dispatch.CONSTRUCTOR || t.kind() == Dispatch.METHOD) {
                            c.aload(frame).loadConstant(((StoreTemp)t.receiver()).id()).invokevirtual(FRAME,"temp",sig(CD_Object,CD_int)).checkcast(OBJ);
                        } else if (t.kind() == Dispatch.STATIC && !fiberStatic(t)) {
                            c.aload(0).aload(calleeLocal).aload(1).invokevirtual(CTX,"staticReceiver",sig(OBJ,CALLABLE,OBJ));
                        } else c.aconst_null();
                        c.astore(receiver);
                        invoke(call,calleeLocal,receiver,dynamic);
                        c.iconst_1().invokevirtual(FRAME,"storeTemp",sig(CD_Object,CTX,CD_int,CD_Object,CD_boolean)).pop();
                        cleanup(call.cleanup(),false);
                    }
                    case B1Cfg.Pass ignored -> {}
                    case B1Cfg.Suspend(var continuation) -> {
                        saveFlight();
                        c.aload(frame).loadConstant(continuation).invokevirtual(FRAME,"suspend",sig(CD_void,CD_int));
                        loadFlight();
                    }
                    case B1Cfg.Cancel ignored -> {
                        saveFlight();
                        frameCtx(); c.invokevirtual(FRAME,"cancel",sig(CD_void,CTX));
                        loadFlight();
                    }
                    case B1Cfg.Resume(var targets) -> {
                        c.aload(frame).invokevirtual(FRAME,"resume",sig(CD_int));
                        loadFlight();
                        if (chunk != null) { saveFlight(); c.ireturn(); continue; }
                        var cases = targets.stream().map(id -> java.lang.classfile.instruction.SwitchCase.of(id,target(id))).toList();
                        c.lookupswitch(target(targets.getFirst()),cases);
                        continue;
                    }
                    case B1Cfg.TestBranch(var test, var no) -> {
                        line(test.at().line()); truth(test.value()); cleanup(test.cleanup(),false);
                        c.ifeq(target(no));
                    }
                    case B1Cfg.StartEach(var each) when fast -> {
                        line(each.source().at().line());
                        c.aload(0); expr(each.source().value());
                        c.invokestatic(FRAME,"startEachFast",sig(CD_Object,CTX,CD_Object)).astore(temps[each.iterator()]);
                        c.iconst_0().istore(positions.get(each.iterator()));
                        cleanup(each.source().cleanup(),false);
                    }
                    case B1Cfg.NextEach(var each, var end) when fast -> {
                        int it = temps[each.iterator()], pos = positions.get(each.iterator());
                        Label generic = c.newLabel(), more = c.newLabel(), done = c.newLabel();
                        c.aload(it).instanceOf(ARRAY).ifeq(generic);
                        c.iload(pos).aload(it).checkcast(ARRAY).invokevirtual(ARRAY,"size",sig(CD_int)).if_icmpge(target(end));
                        c.aload(it).checkcast(ARRAY).iload(pos).invokevirtual(ARRAY,"valueAt",sig(CD_Object,CD_int));
                        c.iinc(pos,1);
                        writeCv(each.value());
                        if (each.key() >= 0) {
                            c.aload(it).checkcast(ARRAY).iload(pos).iconst_1().isub().invokevirtual(ARRAY,"keyAt",sig(CD_Object,CD_int));
                            writeCv(each.key());
                        }
                        c.goto_(done);
                        c.labelBinding(generic);
                        c.aload(0).aload(it).invokestatic(FRAME,"nextValue",sig(CD_Object,CTX,CD_Object)).dup()
                                .getstatic(FRAME,"UNDEF",CD_Object);
                        c.if_acmpne(more).pop().goto_(target(end)).labelBinding(more);
                        writeCv(each.value());
                        if (each.key() >= 0) {
                            c.aload(it).invokestatic(FRAME,"currentKey",sig(CD_Object,CD_Object));
                            writeCv(each.key());
                        }
                        c.labelBinding(done);
                    }
                    case B1Cfg.StartEach(var each) -> {
                        line(each.source().at().line()); frameCtx(); c.loadConstant(each.iterator());
                        if (each.source().value() instanceof ByReference(var source)) {
                            refPlace(source,true);
                            c.invokevirtual(FRAME,"startEachRef",sig(CD_void,CTX,CD_int,REF));
                        } else {
                            expr(each.source().value());
                            c.invokevirtual(FRAME,"startEach",sig(CD_void,CTX,CD_int,CD_Object));
                        }
                        cleanup(each.source().cleanup(),false);
                    }
                    case B1Cfg.NextEach(var each, var end) -> {
                        frameCtx(); c.loadConstant(each.iterator()).loadConstant(each.key()).loadConstant(each.value())
                                .invokevirtual(FRAME,"nextEach",sig(CD_boolean,CTX,CD_int,CD_int,CD_int)).ifeq(target(end));
                    }
                    case B1Cfg.Declare(var id, var name, var at) -> {
                        line(at.line());
                        c.aload(0).aload(2).loadConstant(id).ldc(lower(name))
                                .invokevirtual(CTX, "declareClass", sig(CD_void, CALLABLE, CD_int, CD_String));
                    }
                    case B1Cfg.Execute(var at, var action) -> { line(at.line()); action(action); }
                    case B1Cfg.Cleanup(var temps, var unwind) -> cleanup(temps, unwind);
                    case B1Cfg.Catch(var type, var id) -> {
                        c.aload(pending).ldc(lower(type)).invokestatic(THROWN, "matches", sig(CD_boolean, CD_Throwable, CD_String))
                                .ifeq(target(block.exceptional()));
                        if (fast) {
                            c.aload(cvs[id]).astore(scratchOld);
                            c.aload(0).aload(scratchOld).aload(pending).checkcast(THROWN)
                                    .invokestatic(FRAME,"caught",sig(CD_Object,CTX,CD_Object,THROWN));
                            setCv(id);
                            c.aconst_null().astore(pending);
                            c.aload(0).aload(scratchOld).aload(cvs[id]).invokestatic(FRAME,"drop",sig(CD_void,CTX,CD_Object,CD_Object));
                        } else {
                        frameCtx();
                        c.loadConstant(id).aload(pending).checkcast(THROWN)
                                .invokevirtual(FRAME, "takeException", sig(CD_void, CTX, CD_int, THROWN))
                                .aconst_null().astore(pending);
                        }
                        // A return cancelled by a throwing cleanup no longer owns its protected value.
                        c.aload(0).aload(result);
                        fallthrough();
                        c.astore(result).invokestatic(OBJ, "release", sig(CD_void, CTX, CD_Object));
                    }
                }
                if (!neutral) {
                    if (lines.emitted == before) c.nop();
                    rangeEnd = c.newBoundLabel();
                    lines.transparent.add(rangeEnd);
                    int target = block.exceptional();
                    if (handlers[target] == null) handlers[target] = c.newLabel();
                }
                if (block.normal() != next) go(block.normal());
                else if (!dispatched && incoming[next] == 1) lines.transparent.add(labels[next]);
            }
            closeRange();
            for (int i = 0; i < handlers.length; i++) {
                if (handlers[i] != null) {
                    c.labelBinding(handlers[i]).astore(pending);
                    go(i);
                }
            }
            if (leaf && chunk == null) for (int i = 0; i < fragments.leafPad; i++) c.nop();
            if (chunk != null && !edges.isEmpty()) {
                Label save = c.newLabel();
                for (int id : edges) c.labelBinding(labels[id]).loadConstant(id).goto_(save);
                c.labelBinding(save).aload(frame).aload(result).putfield(FRAME,"result",CD_Object);
                c.aload(frame).aload(pending).putfield(FRAME,"pending",CD_Throwable).ireturn();
            }
        }

        /** The prologue's jump to the entry block (-1: none); it falls through when the entry is emitted first. */
        int fallInto = -1;
        Label rangeStart, rangeEnd;
        int rangeTarget = -1;
        void closeRange() {
            if (rangeStart != null && rangeEnd != null) c.exceptionCatchAll(rangeStart, rangeEnd, handlers[rangeTarget]);
            rangeStart = rangeEnd = null;
            rangeTarget = -1;
        }
        /** This body's blocks with each one's normal successor placed right after it where possible, so the
         * edge falls through instead of jumping. */
        List<B1Cfg.Block> emissionOrder() {
            var blocks = cfg.blocks();
            var placed = new boolean[blocks.size()];
            var order = new ArrayList<B1Cfg.Block>();
            for (var first : blocks.reversed()) {
                for (var block = first; block != null && !placed[block.label()] && inChunk(block.label());
                     block = block.normal() >= 0 ? blocks.get(block.normal()) : null) {
                    placed[block.label()] = true;
                    order.add(block);
                }
            }
            return order;
        }
        boolean inChunk(int label) { return chunk == null || label >= chunk.first && label < chunk.end; }
        /** Static count of the jumps that can reach each block (an overestimate only makes fewer labels transparent). */
        int[] incoming() {
            int[] count = new int[cfg.blocks().size()];
            count[cfg.entry()]++;
            for (var block : cfg.blocks()) {
                if (block.normal() >= 0) count[block.normal()]++;
                if (block.exceptional() >= 0) count[block.exceptional()] += 2;
                switch (block.op()) {
                    case B1Cfg.TestBranch(var test, var no) -> count[no]++;
                    case B1Cfg.NextEach(var each, var end) -> count[end]++;
                    case B1Cfg.Resume(var targets) -> { for (int t : targets) count[t] += 2; }
                    case B1Cfg.Suspend(var continuation) -> count[continuation] += 2;
                    default -> {}
                }
            }
            return count;
        }

        /** A fast frame's leave. Every statement releases its temporaries on both exits (IR Region/Test cleanups),
         * so only the CVs remain: released in order after the frame is unlinked. */
        void fastLeave() {
            if (cvs.length <= 3) {
                c.aload(frame).aload(0).aload(pending).aload(result);
                var params = new ArrayList<ClassDesc>(List.of(CTX, CD_Throwable, CD_Object));
                for (int cv : cvs) { c.aload(cv); params.add(CD_Object); }
                c.invokevirtual(FRAME,"leave" + cvs.length,MethodTypeDesc.of(CD_Object,params)).areturn();
                return;
            }
            c.aload(frame).aload(0).aload(pending).aload(result)
                    .invokevirtual(FRAME,"beginLeave",sig(CD_Throwable,CTX,CD_Throwable,CD_Object)).astore(pending);
            for (int cv : cvs) c.aload(0).aload(cv).aload(pending)
                    .invokestatic(FRAME,"unwindOwned",sig(CD_Throwable,CTX,CD_Object,CD_Throwable)).astore(pending);
            c.aload(frame).aload(0).aload(pending).invokevirtual(FRAME,"endLeave",sig(CD_Object,CTX,CD_Throwable)).areturn();
        }

        void fallthrough() {
            if (f.fileBody()) {
                Label script = c.newLabel(), done = c.newLabel();
                c.aload(2).getfield(CALLABLE,"evalBody",CD_boolean).ifeq(script).aconst_null().goto_(done).labelBinding(script);
                literal(1L); c.labelBinding(done);
            } else c.aconst_null();
        }

        void saveFlight() {
            c.aload(frame).aload(result).putfield(FRAME,"result",CD_Object);
            c.aload(frame).aload(pending).putfield(FRAME,"pending",CD_Throwable);
        }
        void loadFlight() {
            c.aload(frame).getfield(FRAME,"result",CD_Object).astore(result);
            c.aload(frame).getfield(FRAME,"pending",CD_Throwable).astore(pending);
        }

        void bind() {
            if (leaf) { bindLeaf(); return; }
            if (fast) { bindFast(); return; }
            line(f.at().line());
            frameCtx();
            c.aload(2).aload(3).aload(4).aload(5).invokevirtual(FRAME, "bindArguments",
                    sig(CD_void, CTX, CALLABLE, ARGS, CD_Object, CD_Object));
            int required = f.requiredParams();
            if (fragment) range(CALLABLE,2,0,f.params().size(),1,(body,i,ignored) -> body.parameter(i,required));
            else for (int i = 0; i < f.params().size(); i++) parameter(i,required);
            if (fragment) range(CALLABLE,2,0,f.captures().size(),4,(body,i,ignored) -> body.bindCapture(i));
            else for (int i = 0; i < f.captures().size(); i++) bindCapture(i);
        }

        /** A leaf call's exactly passed, untyped parameters take their arguments. */
        void bindLeaf() {
            line(f.at().line());
            for (int i = 0; i < f.params().size(); i++) {
                c.aload(0).aload(4 + i).iload(flags).invokestatic(FRAME,"takeArgument",sig(CD_Object,CTX,CD_Object,CD_int)).astore(cvs[i]);
                c.aload(0).loadConstant(i).aload(cvs[i]).invokevirtual(CTX,"leafArgument",sig(CD_void,CD_int,CD_Object));
            }
        }

        void bindFast() {
            line(f.at().line());
            Label general = c.newLabel(), bound = c.newLabel();
            c.aload(frame).aload(0).aload(2).aload(3).aload(4).aload(5).aload(6).invokevirtual(FRAME,"bindFast",
                    sig(CD_boolean, CTX, CALLABLE, ARGS, CD_Object, CD_Object, CD_Object)).ifeq(general);
            // Positional fixed-arity call: argument i is local 4 + i.
            for (int i = 0; i < f.params().size(); i++) {
                if (f.params().get(i).variadic()) continue;
                if (i >= 3) { c.getstatic(FRAME,"UNDEF",CD_Object).astore(cvs[i]); continue; }
                Label omitted = c.newLabel(), store = c.newLabel();
                c.aload(4 + i).getstatic(CALLABLE,"OMITTED",CD_Object).if_acmpeq(omitted);
                if (f.params().get(i).byRef()) c.aload(frame).aload(4 + i).invokevirtual(FRAME,"takeRef",sig(CD_Object,CD_Object));
                else c.aload(frame).aload(0).aload(4 + i).invokevirtual(FRAME,"take",sig(CD_Object,CTX,CD_Object));
                c.goto_(store).labelBinding(omitted).getstatic(FRAME,"UNDEF",CD_Object).labelBinding(store).astore(cvs[i]);
                if (i < 4) mirror(i);
            }
            c.goto_(bound).labelBinding(general);
            for (int i = 0; i < f.params().size(); i++) {
                if (f.params().get(i).variadic()) continue;
                c.aload(frame).aload(0).aload(2).loadConstant(i).aload(3).aload(4).aload(5).aload(6)
                        .invokevirtual(FRAME,"param",sig(CD_Object,CTX,CALLABLE,CD_int,ARGS,CD_Object,CD_Object,CD_Object)).astore(cvs[i]);
            }
            c.labelBinding(bound);
            c.aload(frame).aload(0).invokevirtual(FRAME,"bound",sig(CD_void,CTX));
            int required = f.requiredParams();
            for (int i = 0; i < f.params().size(); i++) {
                Param p = f.params().get(i);
                line(p.at().line());
                if (p.variadic()) {
                    c.aload(frame).aload(0).loadConstant(i).invokevirtual(FRAME,"variadicFast",sig(CD_Object,CTX,CD_int)).astore(cvs[i]);
                    continue;
                }
                Label checked = c.newLabel();
                if (p.defaultValue() != null) {
                    Label given = c.newLabel();
                    c.aload(cvs[i]).getstatic(FRAME,"UNDEF",CD_Object).if_acmpne(given);
                    expr(p.defaultValue());
                    if (!(p.defaultValue() instanceof ArrayValue)) c.invokestatic(OBJ,"retain",sig(CD_Object,CD_Object));
                    c.astore(cvs[i]);
                    mirror(i); // a parameter skipped by named arguments reports its default in traces
                    c.goto_(checked).labelBinding(given);
                }
                if (p.type() == null) c.aload(cvs[i]).getstatic(FRAME,"UNDEF",CD_Object).if_acmpne(checked);
                c.aload(frame).aload(0).aload(cvs[i]).loadConstant(i).loadConstant(required).loadConstant(f.params().size());
                if (p.type() == null) c.aconst_null(); else c.ldc(p.type());
                c.invokevirtual(FRAME,"checkFast",sig(CD_Object,CTX,CD_Object,CD_int,CD_int,CD_int,CD_String)).astore(cvs[i]);
                c.labelBinding(checked);
            }
            for (int i = 0; i < f.captures().size(); i++) {
                c.aload(frame).loadConstant(i).invokevirtual(FRAME,"captured",sig(CD_Object,CD_int)).astore(cvs[f.captures().get(i).inner()]);
            }
        }

        void bindCapture(int i) {
            frameCtx(); c.loadConstant(f.captures().get(i).inner()).loadConstant(i)
                    .invokevirtual(FRAME,"bindCapture",sig(CD_void,CTX,CD_int,CD_int));
        }

        void parameter(int i, int required) {
            Param p = f.params().get(i);
            line(p.at().line());
            if (p.variadic()) { frameCtx(); c.loadConstant(i).invokevirtual(FRAME,"bindVariadic",sig(CD_void,CTX,CD_int)); return; }
            Label checked = c.newLabel();
            if (p.defaultValue() != null) {
                Label given = c.newLabel();
                c.aload(frame).loadConstant(i).invokevirtual(FRAME,"supplied",sig(CD_boolean,CD_int)).ifne(given);
                frameCtx(); c.loadConstant(i); expr(p.defaultValue());
                int defaultSlot = -1;
                if (p.defaultValue() instanceof ArrayValue) {
                    defaultSlot = ref();
                    c.dup().astore(defaultSlot);
                }
                c.invokevirtual(FRAME,"write",sig(CD_void,CTX,CD_int,CD_Object));
                if (defaultSlot >= 0) c.aload(0).aload(defaultSlot).invokestatic(OBJ,"release",sig(CD_void,CTX,CD_Object));
                c.goto_(checked).labelBinding(given);
            }
            frameCtx(); c.loadConstant(i).loadConstant(required).loadConstant(f.params().size());
            if (p.type() == null) c.aconst_null(); else c.ldc(p.type());
            c.invokevirtual(FRAME,"checkParameter",sig(CD_void,CTX,CD_int,CD_int,CD_int,CD_String));
            c.labelBinding(checked);
        }

        void cleanup(ReleaseTemps temps, boolean unwind) {
            if (fast) {
                for (ReleaseTemp t : temps.slots()) releaseTempFast(t.id(), unwind);
                return;
            }
            if (temps.slots().size() > 6) {
                frameCtx();
                indices(temps.slots().stream().mapToInt(ReleaseTemp::id).toArray());
                if (unwind) c.aload(pending).invokevirtual(FRAME,"unwindAll",sig(CD_Throwable,CTX,INTS,CD_Throwable)).astore(pending);
                else c.invokevirtual(FRAME,"releaseAll",sig(CD_void,CTX,INTS));
                return;
            }
            for (ReleaseTemp t : temps.slots()) {
                frameCtx(); c.loadConstant(t.id());
                if (unwind) {
                    c.aload(pending).invokevirtual(FRAME, "unwindTemp", sig(CD_Throwable, CTX, CD_int, CD_Throwable)).astore(pending);
                } else {
                    c.invokevirtual(FRAME, "releaseTemp", sig(CD_void, CTX, CD_int));
                }
            }
        }

        void action(Action action) {
            switch (action) {
                case Echo(var value) -> { c.aload(0); expr(value); c.invokevirtual(CTX, "echo", sig(CD_void, CD_Object)); }
                case Write(var id, var value) when doubleSlots.containsKey(id) -> { emitDouble(value); c.dstore(doubleSlots.get(id)); }
                case Write(var id, var value) when fast && !longs.containsKey(id) -> { expr(value); writeCv(id); }
                case Write(var id, var value) -> {
                    if (longs.containsKey(id)) {
                        c.loadConstant((Long)((Literal)value).value()).lstore(longs.get(id));
                    } else {
                        frameCtx(); c.loadConstant(id); expr(value);
                        c.invokevirtual(FRAME, "write", sig(CD_void, CTX, CD_int, CD_Object));
                    }
                }
                case Bind(var id,var source) when fast -> {
                    place(source); c.astore(scratchValue);
                    c.aload(cvs[id]).astore(scratchOld);
                    c.aload(0).aload(scratchValue).invokestatic(FRAME,"bindRef",sig(REF,CTX,REF));
                    setCv(id);
                    c.aload(0).aload(scratchOld).invokestatic(FRAME,"releaseOwned",sig(CD_void,CTX,CD_Object));
                }
                case Bind(var id,var source) -> {
                    frameCtx(); c.loadConstant(id); place(source);
                    c.invokevirtual(FRAME,"bind",sig(CD_void,CTX,CD_int,runtime("B1Ref")));
                }
                case WriteStatic(var p,var value) -> {
                    place(p); c.aload(0); expr(value);
                    c.invokevirtual(runtime("B1Ref"),"setProperty",sig(CD_void,CTX,CD_Object));
                }
                case Global(var id) when fast -> {
                    c.aload(cvs[id]).astore(scratchOld);
                    c.aload(0).aload(scratchOld).aload(frame).loadConstant(id).loadConstant(sym("$" + f.locals().get(id)))
                            .invokestatic(FRAME,"globalFast",sig(CD_Object,CTX,CD_Object,FRAME,CD_int,CD_int));
                    setCv(id);
                    c.aload(0).aload(scratchOld).aload(cvs[id]).invokestatic(FRAME,"drop",sig(CD_void,CTX,CD_Object,CD_Object));
                }
                case Global(var id) -> { frameCtx(); c.loadConstant(id).invokevirtual(FRAME,"bindGlobal",sig(CD_void,CTX,CD_int)); }
                case StaticVar(var id,var init) -> {
                    Label bound = c.newLabel();
                    frameCtx(); c.loadConstant(id).aload(2).invokevirtual(FRAME,"bindStatic",sig(CD_boolean,CTX,CD_int,CALLABLE)).ifeq(bound);
                    if (init != null) { frameCtx(); c.loadConstant(id); expr(init); c.invokevirtual(FRAME,"write",sig(CD_void,CTX,CD_int,CD_Object)); }
                    c.labelBinding(bound);
                }
                case Unset(var root,var key) when fast && key == null -> {
                    int id = ((Read)root).local();
                    c.aload(cvs[id]).astore(scratchOld);
                    c.getstatic(FRAME,"UNDEF",CD_Object); setCv(id);
                    c.aload(0).aload(scratchOld).invokestatic(FRAME,"releaseOwned",sig(CD_void,CTX,CD_Object));
                }
                case Unset(var root,var key) -> {
                    if (key == null) { frameCtx(); c.loadConstant(((Read)root).local()).invokevirtual(FRAME,"unset",sig(CD_void,CTX,CD_int)); }
                    else if (nestedUnset(root)) {
                        unsetPlace(root); c.aload(0); expr(key);
                        c.invokestatic(REF,"unsetElementOf",sig(CD_void,REF,CTX,CD_Object));
                    }
                    else { place(root); c.aload(0); expr(key); c.invokevirtual(runtime("B1Ref"),"unsetElement",sig(CD_void,CTX,CD_Object)); }
                }
                case WriteNamedProperty(var recv,var name,var value) -> {
                    // ZEND_ASSIGN_OBJ: computed operands are evaluated first and owned by their temporaries; CV and
                    // property-chain operands are fetched when the assignment executes, after the value (PHP's
                    // delayed fetches), so the value's side effects cannot free an operand still in use.
                    boolean deferRecv = delayable(recv), deferName = delayable(name);
                    int r = -1, n = -1, v = ref();
                    if (deferRecv) recv = held(recv); else { r = ref(); expr(recv); c.astore(r); }
                    if (deferName) name = held(name); else { n = ref(); expr(name); c.astore(n); }
                    expr(value); c.astore(v);
                    if (deferRecv) expr(recv); else c.aload(r);
                    c.checkcast(OBJ).aload(0);
                    if (deferName) expr(name); else c.aload(n);
                    c.aload(v).invokevirtual(OBJ,"writeNamed",sig(CD_void,CTX,CD_Object,CD_Object));
                }
                case CoalesceDim(var local,var key,var value) -> {
                    int k = ref(), v = ref();
                    Label present = c.newLabel();
                    expr(key); c.astore(k).aload(0);
                    if (fast) quietCv(local);
                    else { frameCtx(); c.loadConstant(local).invokevirtual(FRAME,"quiet",sig(CD_Object,CTX,CD_int)); }
                    c.aload(k).iconst_1()
                            .invokevirtual(CTX,"dim",sig(CD_Object,CD_Object,CD_Object,CD_boolean)).ifnonnull(present);
                    expr(value); c.astore(v);
                    place(new Read(local)); c.aload(0).aload(k).iconst_0().aload(v)
                            .invokevirtual(runtime("B1Ref"),"writeElement",sig(CD_void,CTX,CD_Object,CD_boolean,CD_Object));
                    c.labelBinding(present);
                }
                case WriteDim dim -> {
                    var keys = dim.keys();
                    int[] indices = new int[keys.size()];
                    for (int i = 0; i < keys.size(); i++) {
                        if (dim.early(i)) { expr(keys.get(i)); indices[i] = ref(); c.astore(indices[i]); }
                    }
                    expr(dim.value()); int v = ref(); c.astore(v);
                    place(dim.root());
                    for (int i = 0; i < keys.size(); i++) {
                        c.aload(0);
                        boolean append = keys.get(i) == null;
                        if (append) c.aconst_null();
                        else if (dim.early(i)) c.aload(indices[i]);
                        else expr(keys.get(i));
                        c.loadConstant(append ? 1 : 0);
                        if (i + 1 < keys.size()) c.invokevirtual(runtime("B1Ref"),"child",sig(runtime("B1Ref"),CTX,CD_Object,CD_boolean));
                        else c.aload(v).invokevirtual(runtime("B1Ref"),"writeElement",sig(CD_void,CTX,CD_Object,CD_boolean,CD_Object));
                    }
                }
                case WriteProperty(var recv, var name, var value) -> {
                    expr(value); int val = ref(); c.astore(val);
                    expr(recv); c.checkcast(OBJ).aload(0).ldc(name).aload(val)
                            .loadConstant(sym("->" + name)).swap()
                            .invokevirtual(OBJ, "writeAt", sig(CD_void, CTX, CD_String, CD_int, CD_Object));
                }
                case Return(var value) -> {
                    expr(value);
                    if (!scalar(value)) c.invokestatic(OBJ, "retain", sig(CD_Object, CD_Object));
                    c.astore(result);
                }
                case Raise(var value) -> {
                    c.aload(0); expr(value);
                    c.invokestatic(THROWN,"from",sig(THROWN,CTX,CD_Object)).athrow();
                }
                case Evaluate(var value) -> { expr(value); c.pop(); }
            }
        }

        void boxLong() { c.invokestatic(CD_Long, "valueOf", sig(CD_Long, CD_long)); }
        void literal(Object value) {
            if (value == null) c.aconst_null();
            else if (value instanceof Long n) { c.loadConstant(n); boxLong(); }
            else if (value instanceof Boolean b) c.getstatic(CD_Boolean,b ? "TRUE" : "FALSE",CD_Boolean);
            else if (value instanceof Double d) c.loadConstant(d).invokestatic(CD_Double,"valueOf",sig(CD_Double,CD_double));
            else if (value instanceof byte[] || value instanceof String text && text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > B1LiteralBlob.BYTE_THRESHOLD) blob(new Literal(value),CD_Object);
            else c.ldc(java.lang.constant.DynamicConstantDesc.ofNamed(TEXT_BOOTSTRAP,"text",runtime("B1Bytes"),(String)value));
        }
        /** The index of a blob in this class; an identical blob (the same literal in the fiber body) is shared. */
        int blobIndex(byte[] data) {
            for (int i = 0; i < blobs.size(); i++) if (java.util.Arrays.equals(blobs.get(i), data)) return i;
            blobs.add(data);
            return blobs.size() - 1;
        }
        void blob(Expr e, ClassDesc type) {
            int index = blobIndex(B1LiteralBlob.encode(e));
            c.ldc(java.lang.constant.DynamicConstantDesc.ofNamed(LITERAL_BOOTSTRAP,"literal",type,index));
        }
        void indices(int[] slots) {
            var bytes = java.nio.ByteBuffer.allocate(slots.length * Integer.BYTES);
            bytes.asIntBuffer().put(slots);
            int index = blobIndex(bytes.array());
            c.ldc(java.lang.constant.DynamicConstantDesc.ofNamed(INDICES_BOOTSTRAP,"indices",INTS,index));
        }
        /** Partition list-shaped operations too. This keeps a single variadic call, parameter
         * list, or nonconstant array literal from becoming an indivisible oversized CFG block. */
        interface RangeItem { void emit(Body body, int index, int carrier); }
        void range(ClassDesc type, int carrier, int start, int end, int batch, RangeItem item) {
            if (end - start <= batch) {
                for (int i = start; i < end; i++) item.emit(this,i,carrier);
                return;
            }
            var signature = STEP.changeReturnType(CD_void).insertParameterTypes(7,type);
            String name = fragments.helper(signature, body -> {
                int mid = (start + end) >>> 1;
                body.range(type,7,start,mid,batch,item);
                body.range(type,7,mid,end,batch,item);
                body.c.return_();
            });
            for (int i = 0; i < 6; i++) c.aload(i);
            c.aload(frame).aload(carrier).invokestatic(fragments.cls,name,signature);
        }
        /** Pushes the PHP truth of e as a JVM boolean. Boolean-shaped expressions skip the Boolean box. */
        void truth(Expr e) {
            if (!fragment) {
                switch (e) {
                    case Not(var inner) -> { truth(inner); c.iconst_1().ixor(); return; }
                    case IsSet(var inner) -> { expr(inner); c.invokestatic(ClassDesc.of("java.util.Objects"),"nonNull",sig(CD_boolean,CD_Object)); return; }
                    case Binary b when b.op() == Operator.AND || b.op() == Operator.OR -> {
                        Label done = c.newLabel();
                        truth(b.left()); c.dup();
                        if (b.op() == Operator.AND) c.ifeq(done); else c.ifne(done);
                        c.pop(); truth(b.right()); c.labelBinding(done);
                        return;
                    }
                    case Binary b when doubleComparison(b) -> {
                        // NaN compares false (unequal): dcmpg for < and <=, dcmpl otherwise.
                        Label yes = c.newLabel(), done = c.newLabel();
                        doubleOperands(b);
                        boolean lower = b.op() == Operator.LT || b.op() == Operator.LE;
                        if (lower) c.dcmpg(); else c.dcmpl();
                        switch (b.op()) {
                            case LT -> c.iflt(yes);
                            case LE -> c.ifle(yes);
                            case GT -> c.ifgt(yes);
                            case GE -> c.ifge(yes);
                            case EQ, IDENTICAL -> c.ifeq(yes);
                            default -> c.ifne(yes);
                        }
                        c.iconst_0().goto_(done).labelBinding(yes).iconst_1().labelBinding(done);
                        return;
                    }
                    case Binary b when b.op() == Operator.IDENTICAL || b.op() == Operator.NOT_IDENTICAL || b.op() == Operator.LT
                            || b.op() == Operator.LE || b.op() == Operator.GT || b.op() == Operator.GE
                            || b.op() == Operator.EQ || b.op() == Operator.NE -> {
                        boolean identity = b.op() == Operator.IDENTICAL || b.op() == Operator.NOT_IDENTICAL;
                        if (!identity) c.aload(0);
                        if (b.deferredLeft()) { expr(b.right()); expr(b.left()); c.swap(); }
                        else { expr(b.left()); expr(b.right()); }
                        if (identity) {
                            c.invokestatic(CTX,"identicalTest",sig(CD_boolean,CD_Object,CD_Object));
                            if (b.op() == Operator.NOT_IDENTICAL) c.iconst_1().ixor();
                        } else c.loadConstant(b.op().ordinal()).invokevirtual(CTX,"test",sig(CD_boolean,CD_Object,CD_Object,CD_int));
                        cleanup(b.consumed(), false);
                        return;
                    }
                    case StoreTemp(var id, Call call) when typeTest(call) != null -> {
                        line(call.at().line());
                        expr(call.args().getFirst().value());
                        switch (typeTest(call)) {
                            case "is_array" -> c.instanceOf(ARRAY);
                            case "is_int" -> c.instanceOf(CD_Long);
                            case "is_null" -> c.invokestatic(ClassDesc.of("java.util.Objects"),"isNull",sig(CD_boolean,CD_Object));
                            case "is_string" -> c.invokestatic(runtime("B1Builtins"),"isString",sig(CD_boolean,CD_Object));
                            default -> c.invokestatic(runtime("B1Builtins"),"isObject",sig(CD_boolean,CD_Object));
                        }
                        // The call frees its sent operands before the condition goes on (new D() destructs here).
                        cleanup(call.cleanup(), false);
                        return;
                    }
                    default -> {}
                }
            }
            expr(e); c.invokestatic(CTX,"truth",sig(CD_boolean,CD_Object));
        }
        /** A comparison of float operands (=== needs both to be floats; the others compare an int as a float). */
        boolean doubleComparison(Binary b) {
            if (doubleSlots.isEmpty() || !doubles.operand(b.left()) || !doubles.operand(b.right())) return false;
            return switch (b.op()) {
                case LT, LE, GT, GE, EQ, NE -> doubles.of(b.left()) || doubles.of(b.right());
                case IDENTICAL, NOT_IDENTICAL -> doubles.of(b.left()) && doubles.of(b.right());
                default -> false;
            };
        }
        /** is_array/is_string/is_int/is_null/is_object of one by-value operand: a type test without a result temporary. */
        static String typeTest(Call call) {
            if (call.target().kind() != Dispatch.FUNCTION || call.args().size() != 1) return null;
            String name = lower(call.target().name());
            return switch (name) { case "is_array", "is_string", "is_int", "is_null", "is_object" -> name; default -> null; };
        }
        void bool() { c.invokestatic(CD_Boolean,"valueOf",sig(CD_Boolean,CD_boolean)); }
        void array(ArrayValue a, int slot) {
            if (B1LiteralBlob.useBlob(a)) {
                if (!B1LiteralBlob.validAppendKeys(a)) c.aload(0).invokevirtual(CTX,"occupiedAppend",sig(ARRAY));
                else blob(a,ARRAY);
                return;
            }
            c.new_(ARRAY).dup().loadConstant(a.items().size()).invokespecial(ARRAY,"<init>",sig(CD_void,CD_int));
            if (slot >= 0 && fast) c.dup().astore(temps[slot]);
            else if (slot >= 0) {
                int partial = ref();
                c.astore(partial);
                frameCtx(); c.loadConstant(slot).aload(partial).iconst_1()
                        .invokevirtual(FRAME,"storeTemp",sig(CD_Object,CTX,CD_int,CD_Object,CD_boolean)).checkcast(ARRAY);
            }
            if (fragment && a.items().size() > 4) {
                int array = ref(); c.astore(array);
                range(ARRAY,array,0,a.items().size(),2,(body,i,carrier) -> {
                    body.c.aload(carrier);
                    body.item(a.items().get(i));
                    body.c.pop();
                });
                c.aload(array);
            } else for (Item item : a.items()) item(item);
        }

        void item(Item item) {
            c.aload(0);
            if (item.key() != null) expr(item.key());
            if (item.value() instanceof ByReference(var source)) {
                refPlace(source,false); c.aload(0).invokevirtual(REF,"asReference",sig(REF,CTX));
            } else expr(item.value());
            if (item.key() == null) c.invokevirtual(ARRAY,"append",sig(ARRAY,CTX,CD_Object));
            else c.invokevirtual(ARRAY,"putInit",sig(ARRAY,CTX,CD_Object,CD_Object));
            if (item.value() instanceof StoreTemp temp) cleanup(new ReleaseTemps(List.of(new ReleaseTemp(temp.id()))),false);
        }

        void expr(Expr value) {
            if (fragment && !(value instanceof Read || value instanceof Literal || value instanceof This)) {
                for (int i = 0; i < 6; i++) c.aload(i);
                c.aload(frame).invokestatic(fragments.cls,fragments.value(value),VALUE);
            } else inline(value);
        }

        void inline(Expr expr) {
            if (!doubleSlots.isEmpty() && floatNode(expr)) {
                emitDouble(expr); boxDouble();
                return;
            }
            switch (expr) {
                case Yield ignored -> throw new IllegalStateException("yield outside resume block");
                case Literal(var value) -> literal(value);
                case Read(var id) when fast && !longs.containsKey(id) -> readCv(id);
                case Read(var id) -> {
                    if (longs.containsKey(id)) { c.lload(longs.get(id)); boxLong(); }
                    else { frameCtx(); c.loadConstant(id).invokevirtual(FRAME, "read", sig(CD_Object, CTX, CD_int)); }
                }
                case Prepared(var operands,var result) -> { for (var temp : operands) { expr(temp); c.pop(); } expr(result); }
                case LoadTemp(var id) -> loadTemp(id);
                case ArrayValue a -> array(a,-1);
                case QuietRead(var id) when fast -> quietCv(id);
                case QuietRead(var id) -> { frameCtx(); c.loadConstant(id).invokevirtual(FRAME,"quiet",sig(CD_Object,CTX,CD_int)); }
                case Dim(var base,var key,var quiet) -> {
                    c.aload(0); expr(base); expr(key);
                    c.loadConstant(quiet ? 1 : 0).invokevirtual(CTX,"dim",sig(CD_Object,CD_Object,CD_Object,CD_boolean));
                }
                case Update(var id,var value) when fast -> {
                    int saved = ref();
                    expr(value); c.dup().astore(saved);
                    writeCv(id); c.aload(saved);
                }
                case Update(var id,var value) -> {
                    frameCtx(); c.loadConstant(id); expr(value);
                    int saved = ref(); c.dup().astore(saved).invokevirtual(FRAME,"write",sig(CD_void,CTX,CD_int,CD_Object)).aload(saved);
                }
                case Increment(var id, var up, var prefix) when fast -> {
                    int old = ref(), next = ref();
                    readCv(id); c.astore(old);
                    c.aload(0).aload(old).loadConstant(up ? 1L : -1L); boxLong();
                    c.invokevirtual(CTX,"add",sig(CD_Object,CD_Object,CD_Object)).astore(next);
                    c.aload(next); writeCv(id);
                    c.aload(prefix ? next : old);
                }
                case Increment(var id, var up, var prefix) -> {
                    frameCtx(); c.loadConstant(id).loadConstant(up ? 1 : 0).loadConstant(prefix ? 1 : 0)
                            .invokevirtual(FRAME,"increment",sig(CD_Object,CTX,CD_int,CD_boolean,CD_boolean));
                }
                case Cast(var value,var string) -> {
                    c.aload(0); expr(value); c.invokevirtual(CTX,string ? "string" : "castLong",sig(string ? CD_String : CD_Object,CD_Object));
                }
                case IncrementPlace(Property(var recv, var name),var up,var prefix) -> {
                    expr(recv); c.checkcast(OBJ).aload(0).ldc(name).loadConstant(sym("->" + name))
                            .loadConstant(up ? 1 : 0).loadConstant(prefix ? 1 : 0)
                            .invokevirtual(OBJ,"incrementAt",sig(CD_Object,CTX,CD_String,CD_int,CD_boolean,CD_boolean));
                }
                case IncrementPlace(var p,var up,var prefix) -> {
                    place(p); c.aload(0).loadConstant(up ? 1 : 0).loadConstant(prefix ? 1 : 0)
                            .invokevirtual(runtime("B1Ref"),"increment",sig(CD_Object,CTX,CD_boolean,CD_boolean));
                }
                case CheckedReturn(var value,var missing) -> {
                    frameCtx(); c.ldc(f.returnType());
                    if (missing) c.aconst_null(); else expr(value);
                    c.loadConstant(missing ? 1 : 0).invokevirtual(FRAME,"checkReturn",sig(CD_Object,CTX,CD_String,CD_Object,CD_boolean));
                }
                case PassedArguments ignored -> { frameCtx(); c.invokevirtual(FRAME,"traceArguments",sig(ARRAY,CTX)); }
                case IsSet(var e) -> { expr(e); c.invokestatic(ClassDesc.of("java.util.Objects"),"nonNull",sig(CD_boolean,CD_Object)); bool(); }
                case Not(var e) -> { truth(e); c.iconst_1().ixor(); bool(); }
                case Choose(var test,var yes,var no) -> {
                    Label otherwise = c.newLabel(), done = c.newLabel();
                    truth(test); c.ifeq(otherwise); expr(yes); c.goto_(done).labelBinding(otherwise); expr(no); c.labelBinding(done);
                }
                case Closure(var id,var captures) -> {
                    c.new_(CLOSURE).dup().aload(0).aload(2).getfield(CALLABLE,"siblings",CALLABLE.arrayType()).loadConstant(id).aaload()
                            .loadConstant(captures.size()).aload(1).invokespecial(CLOSURE,"<init>",sig(CD_void,CTX,CALLABLE,CD_int,OBJ));
                    int closure = ref(); c.astore(closure);
                    RangeItem capture = (body,i,carrier) -> {
                        var cap = captures.get(i);
                        body.c.aload(carrier).loadConstant(i);
                        if (body.fast) {
                            if (cap.byRef()) { body.c.aload(0); body.placeCv(cap.outer()); body.c.invokestatic(FRAME,"reference",sig(REF,CTX,REF)); }
                            else body.readCv(cap.outer());
                        }
                        else {
                            body.frameCtx(); body.c.loadConstant(cap.outer());
                            if (cap.byRef()) body.c.invokevirtual(FRAME,"reference",sig(runtime("B1Ref"),CTX,CD_int));
                            else body.c.invokevirtual(FRAME,"read",sig(CD_Object,CTX,CD_int));
                        }
                        body.c.invokevirtual(CLOSURE,"capture",sig(CLOSURE,CD_int,CD_Object)).pop();
                    };
                    if (fragment) range(CLOSURE,closure,0,captures.size(),4,capture);
                    else for (int i = 0; i < captures.size(); i++) capture.emit(this,i,closure);
                    c.aload(closure);
                }
                case Forward(var fn,var args,var offset,var length) -> {
                    c.aload(0); expr(fn); expr(args);
                    if (offset == null) c.aconst_null(); else expr(offset);
                    if (length == null) c.aconst_null(); else expr(length);
                    c.loadConstant(offset == null ? 0 : 1).invokevirtual(CTX,"forward",sig(CD_Object,CD_Object,CD_Object,CD_Object,CD_Object,CD_boolean));
                }
                case Eval(var code,var at) -> {
                    line(at.line()); c.aload(0); expr(code); c.aload(1).aload(2).getfield(CALLABLE,"methodBudget",CD_int)
                            .aload(2).getfield(CALLABLE,"extendedLanguage",CD_boolean)
                            .invokevirtual(CTX,"eval",sig(CD_Object,CD_Object,OBJ,CD_int,CD_boolean));
                }
                case Include(var path,var kind,var at) -> {
                    line(at.line()); c.aload(0); expr(path);
                    c.loadConstant(kind).aload(2).getfield(CALLABLE,"methodBudget",CD_int)
                            .invokevirtual(CTX,"include",sig(CD_Object,CD_Object,CD_int,CD_int));
                }
                case Assign(var id,var value) when fast -> {
                    int value0 = ref();
                    expr(value); c.dup().astore(value0).invokestatic(OBJ,"retain",sig(CD_Object,CD_Object)).pop();
                    c.aload(cvs[id]).astore(scratchOld);
                    c.aload(0).aload(scratchOld).aload(value0).invokestatic(FRAME,"assignStore",sig(CD_Object,CTX,CD_Object,CD_Object));
                    setCv(id);
                    c.aload(0).aload(scratchOld).aload(cvs[id]).aload(value0)
                            .invokestatic(FRAME,"assigned",sig(CD_Object,CTX,CD_Object,CD_Object,CD_Object));
                }
                case Assign(var id,var value) -> {
                    frameCtx(); c.loadConstant(id); expr(value);
                    c.invokevirtual(FRAME,"assign",sig(CD_Object,CTX,CD_int,CD_Object));
                }
                case InstanceOf(var value,var type) -> {
                    c.aload(0); expr(value); c.ldc(lower(type)).invokevirtual(CTX,"instanceOf",sig(CD_boolean,CD_Object,CD_String)); bool();
                }
                case UpdatePlace update -> updatePlace(update);
                case Constant(var name) -> c.aload(0).ldc(name).invokevirtual(CTX,"constant",sig(CD_Object,CD_String));
                case Convert(var value,var type) -> { c.aload(0); expr(value); c.ldc(type).invokevirtual(CTX,"convert",sig(CD_Object,CD_Object,CD_String)); }
                case ByReference ignored -> throw new IllegalStateException("by-reference operand outside foreach or an array literal");
                case DynamicAllocate(var name,var at) -> { line(at.line()); c.aload(0); expr(name); c.invokevirtual(CTX,"allocateDynamic",sig(OBJ,CD_Object)); }
                case This ignored -> c.aload(1);
                case StaticProperty p -> { place(p); c.aload(0).invokevirtual(runtime("B1Ref"),"readProperty",sig(CD_Object,CTX)); }
                case QuietProperty(var recv,var name) -> {
                    expr(recv); c.aload(0).ldc(name).loadConstant(sym("->" + name))
                            .invokestatic(CTX,"quietProperty",sig(CD_Object,CD_Object,CTX,CD_String,CD_int));
                }
                case Property(var recv, var name) -> {
                    expr(recv); c.aload(0).ldc(name).loadConstant(sym("->" + name))
                            .invokestatic(CTX, "readProperty", sig(CD_Object, CD_Object, CTX, CD_String, CD_int));
                }
                case Binary binary -> binary(binary);
                case StoreTemp(var id, var value) -> {
                    if (value instanceof ArrayValue array && !B1LiteralBlob.useBlob(array)) {
                        array(array,id);
                        break;
                    }
                    boolean owned = value instanceof Call || value instanceof Allocate || value instanceof DynamicAllocate || value instanceof Eval || value instanceof Include || value instanceof Convert || value instanceof Assign || value instanceof Forward || value instanceof PassedArguments || value instanceof Closure || value instanceof ArrayValue;
                    if (fast) {
                        expr(value); storeTempFast(id, owned);
                        if (value instanceof Call call) cleanup(call.cleanup(), false);
                        if (value instanceof Binary b && b.preservesValue()) cleanup(b.consumed(),false);
                        break;
                    }
                    frameCtx(); c.loadConstant(id); expr(value);
                    c.loadConstant(value instanceof Call || value instanceof Allocate || value instanceof DynamicAllocate || value instanceof Eval || value instanceof Include || value instanceof Convert || value instanceof Assign || value instanceof Forward || value instanceof PassedArguments || value instanceof Closure || value instanceof ArrayValue ? 1 : 0)
                            .invokevirtual(FRAME, "storeTemp", sig(CD_Object, CTX, CD_int, CD_Object, CD_boolean));
                    if (value instanceof Call call) cleanup(call.cleanup(), false);
                    if (value instanceof Binary b && b.preservesValue()) cleanup(b.consumed(),false);
                }
                case Allocate(var name, var at) -> {
                    line(at.line());
                    c.aload(0).ldc(lower(name)).ldc(name).invokevirtual(CTX, "allocate", sig(OBJ, CD_String, CD_String));
                }
                case Call call -> call(call);
            }
        }

        void binary(Binary binary) {
                Operator op = binary.op();
                if (op == Operator.AND || op == Operator.OR) {
                    Label done = c.newLabel();
                    truth(binary.left()); c.dup();
                    if (op == Operator.AND) c.ifeq(done); else c.ifne(done);
                    c.pop(); truth(binary.right()); c.labelBinding(done); bool();
                    return;
                }
                if (binary.preservesValue()) {
                    Label done = c.newLabel();
                    expr(binary.left()); c.dup();
                    if (op == Operator.COALESCE) c.ifnonnull(done);
                    else c.invokestatic(CTX,"truth",sig(CD_boolean,CD_Object)).ifne(done);
                    c.pop(); expr(binary.right()); c.labelBinding(done);
                    return;
                }
                c.aload(0);
                if (binary.deferredLeft()) {
                    expr(binary.right());
                    expr(binary.left());
                    c.swap();
                } else {
                    expr(binary.left());
                    expr(binary.right());
                }
                String arithmetic = switch (op) {
                    case SUB -> "subtract"; case DIV -> "divide"; case SL -> "shiftLeft"; case SR -> "shiftRight";
                    case BW_AND -> "bitAnd"; case BW_OR -> "bitOr"; case BW_XOR -> "bitXor";
                    default -> null;
                };
                if (op == Operator.MOD || op == Operator.MUL) c.invokevirtual(CTX,op == Operator.MOD ? "mod" : "multiply",sig(CD_Object,CD_Object,CD_Object));
                else if (arithmetic != null) c.invokevirtual(CTX,arithmetic,sig(CD_Object,CD_Object,CD_Object));
                else if (op == Operator.IDENTICAL || op == Operator.NOT_IDENTICAL) {
                    c.loadConstant(op == Operator.IDENTICAL ? 1 : 0).invokestatic(CTX,"identical",sig(CD_Object,CD_Object,CD_Object,CD_boolean));
                    // the context loaded first is not needed by the static form
                    c.swap().pop();
                }
                else if (op != Operator.ADD && op != Operator.CONCAT) c.loadConstant(op.ordinal())
                        .invokevirtual(CTX,"compare",sig(CD_Object,CD_Object,CD_Object,CD_int));
                else c.invokevirtual(CTX, binary.op() == Operator.ADD ? "add" : "concat", sig(CD_Object, CD_Object, CD_Object));
                cleanup(binary.consumed(), false);
        }

        void place(Expr root) {
            if (root instanceof Prepared p) {
                for (StoreTemp t : p.operands()) { expr(t); c.pop(); }
                place(p.result());
            } else if (root instanceof StoreTemp t) place(t.value());
            else if (root instanceof StaticProperty p) {
                c.aload(0).ldc(lower(p.owner())).ldc(p.name()).invokevirtual(CTX,"staticProperty",sig(runtime("B1Ref"),CD_String,CD_String));
            } else if (root instanceof Read local && fast) {
                placeCv(local.local());
            } else if (root instanceof Read local) {
                frameCtx(); c.loadConstant(local.local()).invokevirtual(FRAME,"place",sig(runtime("B1Ref"),CTX,CD_int));
            } else if (root instanceof Dim dim) {
                place(dim.base()); c.aload(0); expr(dim.key()); c.iconst_0()
                        .invokevirtual(runtime("B1Ref"),"child",sig(runtime("B1Ref"),CTX,CD_Object,CD_boolean));
            } else {
                Property prop = (Property)root;
                expr(prop.receiver()); c.checkcast(OBJ).aload(0).ldc(prop.name()).loadConstant(sym("->" + prop.name()))
                        .invokevirtual(OBJ,"propertyAt",sig(runtime("B1Ref"),CTX,CD_String,CD_int));
            }
        }

        /** A place fetched for a reference (FETCH_*_W, ZEND_FETCH_REF). FE_RESET_RW reads a CV source, so an
         * undefined one warns (and stays undefined); a string offset cannot become a reference. */
        void refPlace(Expr root, boolean foreachSource) {
            if (root instanceof Prepared p) {
                for (StoreTemp t : p.operands()) { expr(t); c.pop(); }
                refPlace(p.result(),foreachSource);
            } else if (root instanceof StoreTemp t) refPlace(t.value(),foreachSource);
            else if (foreachSource && root instanceof Read local) {
                frameCtx(); c.loadConstant(local.local()).invokevirtual(FRAME,"placeRW",sig(REF,CTX,CD_int));
            } else if (root instanceof Dim dim) {
                place(dim.base()); c.aload(0); expr(dim.key());
                c.invokevirtual(REF,"childRef",sig(REF,CTX,CD_Object));
            } else place(root);
        }

        /** ZEND_ASSIGN_DIM_OP / OBJ_OP / STATIC_PROP_OP: early keys, the value, RW fetches, then the operator. */
        void updatePlace(UpdatePlace u) {
            List<Expr> keys = u.keys();
            int[] early = new int[keys.size()];
            for (int i = 0; i < keys.size(); i++) {
                if (u.early(i)) { expr(keys.get(i)); early[i] = ref(); c.astore(early[i]); }
            }
            expr(u.value()); int v = ref(); c.astore(v);
            if (u.root() instanceof Read local && fast) {
                c.aload(0); placeCv(local.local()); c.aload(frame).loadConstant(local.local())
                        .invokestatic(FRAME,"boxRW",sig(REF,CTX,REF,FRAME,CD_int));
            } else if (u.root() instanceof Read local) {
                frameCtx(); c.loadConstant(local.local()).invokevirtual(FRAME,"placeRW",sig(REF,CTX,CD_int));
            } else if (u.root() instanceof Property prop) {
                expr(prop.receiver()); c.checkcast(OBJ).aload(0).ldc(prop.name()).invokevirtual(OBJ,"propertyRW",sig(REF,CTX,CD_String));
            } else place(u.root());
            for (int i = 0; i < keys.size(); i++) {
                c.aload(0);
                if (u.early(i)) c.aload(early[i]); else expr(keys.get(i));
                c.invokevirtual(REF,"childRW",sig(REF,CTX,CD_Object));
            }
            c.aload(0).loadConstant(u.op().ordinal()).aload(v).invokevirtual(REF,"compound",sig(CD_Object,CTX,CD_int,CD_Object));
        }

        boolean nestedUnset(Expr root) {
            if (root instanceof Prepared p) return nestedUnset(p.result());
            if (root instanceof StoreTemp t) return nestedUnset(t.value());
            return root instanceof Dim;
        }
        /** FETCH_DIM_UNSET / FETCH_OBJ_UNSET: the containers on the path exist or end it (null). */
        void unsetPlace(Expr root) {
            if (root instanceof Prepared p) {
                for (StoreTemp t : p.operands()) { expr(t); c.pop(); }
                unsetPlace(p.result());
            } else if (root instanceof StoreTemp t) unsetPlace(t.value());
            else if (root instanceof Dim d) {
                unsetPlace(d.base()); c.aload(0); expr(d.key());
                c.invokestatic(REF,"unsetChild",sig(REF,REF,CTX,CD_Object));
            } else if (root instanceof Property prop) {
                expr(prop.receiver()); c.checkcast(OBJ).aload(0).ldc(prop.name()).invokevirtual(OBJ,"existingProperty",sig(REF,CTX,CD_String));
            } else place(root);
        }

        static boolean fiberStatic(Target t) {
            return t.receiver() instanceof Literal(var cls) && "fiber".equalsIgnoreCase((String)cls);
        }

        void sendFast(Argument arg, int i, int callee) {
            boolean stored = arg.value() instanceof StoreTemp || arg.value() instanceof LoadTemp t && t.id() == arg.slot();
            if (arg.bindVariable()) {
                Label byValue = c.newLabel(), done = c.newLabel();
                c.aload(callee).ifnull(byValue).aload(callee).loadConstant(i).invokevirtual(CALLABLE,"byRef",sig(CD_boolean,CD_int)).ifeq(byValue);
                c.aload(0);
                if (arg.value() instanceof Read local) placeCv(local.local()); else place(arg.value());
                c.invokestatic(FRAME,"reference",sig(REF,CTX,REF));
                c.goto_(done).labelBinding(byValue);
                expr(arg.value());
                c.labelBinding(done);
            } else {
                frameCtx(); c.aload(callee).loadConstant(i);
                expr(arg.value());
                c.loadConstant(stored ? 1 : 0)
                        .invokevirtual(FRAME, "sendValue", sig(CD_Object, CTX, CALLABLE, CD_int, CD_Object, CD_boolean));
            }
            if (!stored) storeTempFast(arg.slot(), false);
            c.pop();
        }
        void send(Argument arg, int i, int callee) {
            if (fast) { sendFast(arg,i,callee); return; }
            boolean stored = arg.value() instanceof StoreTemp || arg.value() instanceof LoadTemp t && t.id() == arg.slot();
            if (!stored) { frameCtx(); c.loadConstant(arg.slot()); }
            if (arg.bindVariable() && !(arg.value() instanceof Read)) {
                Label byValue = c.newLabel(), done = c.newLabel();
                c.aload(callee).ifnull(byValue).aload(callee).loadConstant(i).invokevirtual(CALLABLE,"byRef",sig(CD_boolean,CD_int)).ifeq(byValue);
                place(arg.value()); c.aload(0).invokevirtual(runtime("B1Ref"),"asReference",sig(runtime("B1Ref"),CTX)).goto_(done).labelBinding(byValue); expr(arg.value()); c.labelBinding(done);
            } else if (arg.bindVariable()) {
                frameCtx(); c.loadConstant(((Read)arg.value()).local()).aload(callee).loadConstant(i)
                        .invokevirtual(FRAME, "send", sig(CD_Object, CTX, CD_int, CALLABLE, CD_int));
            } else {
                frameCtx(); c.aload(callee).loadConstant(i);
                expr(arg.value());
                c.loadConstant(stored ? 1 : 0)
                        .invokevirtual(FRAME, "sendValue", sig(CD_Object, CTX, CALLABLE, CD_int, CD_Object, CD_boolean));
            }
            if (!stored) c.iconst_0()
                    .invokevirtual(FRAME, "storeTemp", sig(CD_Object, CTX, CD_int, CD_Object, CD_boolean));
            c.pop();
        }
        void resolve(Target t,int receiver,int dynamic) {
            if (t.kind() == Dispatch.DYNAMIC) {
                expr(t.receiver()); c.astore(dynamic);
                c.aload(0).aload(dynamic).invokevirtual(CTX,"receiver",sig(OBJ,CD_Object)).astore(receiver);
                c.aload(0).aload(dynamic).invokevirtual(CTX,"callable",sig(CALLABLE,CD_Object));
            } else if (t.kind() == Dispatch.STATIC && !fiberStatic(t)) {
                String cls = (String)((Literal)t.receiver()).value();
                int target = ref();
                c.aload(0).ldc(lower(cls)).ldc(lower(t.name())).ldc(cls).ldc(t.name())
                        .invokevirtual(CTX,"staticMethod",sig(CALLABLE,CD_String,CD_String,CD_String,CD_String));
                c.dup().astore(target).aload(0).aload(target).aload(1).invokevirtual(CTX,"staticReceiver",sig(OBJ,CALLABLE,OBJ)).astore(receiver);
            } else if (t.kind() == Dispatch.STATIC) {
                c.aconst_null().astore(receiver).aload(0).ldc(lower((String)((Literal)t.receiver()).value())).ldc(lower(t.name()))
                        .invokevirtual(CTX,"method",sig(CALLABLE,CD_String,CD_String));
            } else if (t.kind() == Dispatch.FUNCTION) {
                c.aconst_null().astore(receiver).aload(0).ldc(lower(t.name())).loadConstant(sym(lower(t.name())))
                        .invokestatic(CTX, "function", sig(CALLABLE, CTX, CD_String, CD_int));
            } else {
                expr(t.receiver());
                c.checkcast(OBJ).astore(receiver).aload(0).aload(receiver);
                if (t.kind() == Dispatch.CONSTRUCTOR) c.invokevirtual(CTX, "constructor", sig(CALLABLE, OBJ));
                else c.ldc(lower(t.name())).ldc(t.name()).loadConstant(sym(lower(t.name())))
                        .invokevirtual(CTX, "resolve", sig(CALLABLE, OBJ, CD_String, CD_String, CD_int));
            }
        }

        int callee(int slot) {
            int local = ref();
            c.aload(frame).loadConstant(slot).invokevirtual(FRAME,"temp",sig(CD_Object,CD_int)).checkcast(CALLABLE).astore(local);
            return local;
        }

        /** Global builtins called by name bind statically (PHP forbids redeclaring them): name -> arity, by-ref first. */
        static final Map<String,int[]> INTRINSICS = Map.ofEntries(
                Map.entry("is_array",new int[]{1,0}), Map.entry("is_string",new int[]{1,0}), Map.entry("is_int",new int[]{1,0}),
                Map.entry("is_null",new int[]{1,0}), Map.entry("is_object",new int[]{1,0}), Map.entry("count",new int[]{1,0}),
                Map.entry("sizeof",new int[]{1,0}), Map.entry("strlen",new int[]{1,0}), Map.entry("trim",new int[]{1,0}),
                Map.entry("ucfirst",new int[]{1,0}), Map.entry("current",new int[]{1,0}), Map.entry("key",new int[]{1,0}),
                Map.entry("get_object_vars",new int[]{1,0}), Map.entry("array_key_exists",new int[]{2,0}),
                Map.entry("str_replace",new int[]{3,0}), Map.entry("next",new int[]{1,1}), Map.entry("reset",new int[]{1,1}),
                Map.entry("array_pop",new int[]{1,1}), Map.entry("array_unshift",new int[]{2,1}));
        /** Operands PHP fetches lazily (delayed oplines): CVs, $this, literals and property/dimension chains of them. */
        static boolean delayable(Expr e) { return pl.codeloom.php.ir.PhpIrLowerer.delayable(e); }
        /** Evaluates the held keys of a delayed chain now and returns the fetch to emit later. */
        Expr held(Expr delayed) {
            if (!(delayed instanceof Prepared p)) return delayed;
            for (var temp : p.operands()) { expr(temp); c.pop(); }
            return p.result();
        }
        /** Operands whose evaluation cannot change a CV an earlier operand borrowed. */
        static boolean pure(Expr e) {
            return e instanceof Read || e instanceof QuietRead || e instanceof Literal || e instanceof This
                    || e instanceof LoadTemp || e instanceof StoreTemp t && pure(t.value());
        }
        /** A global builtin call bound statically (INTRINSICS). */
        static boolean intrinsicCall(Call call) {
            Target t = call.target();
            if (t.kind() != Dispatch.FUNCTION) return false;
            int[] spec = INTRINSICS.get(lower(t.name()));
            var args = call.args();
            if (spec == null || args.size() != spec[0] || spec[1] == 1 && !args.getFirst().bindVariable()) return false;
            for (int i = 1; i < args.size(); i++) if (!pure(args.get(i).value())) return false;
            return true;
        }
        boolean intrinsic(Call call) {
            if (!intrinsicCall(call)) return false;
            Target t = call.target();
            String name = lower(t.name());
            int[] spec = INTRINSICS.get(name);
            var args = call.args();
            line(call.at().line());
            c.aload(0);
            for (int i = 0; i < args.size(); i++) {
                Expr value = args.get(i).value();
                if (i == 0 && spec[1] == 1) {
                    c.aload(0);
                    if (value instanceof Read local && fast) placeCv(local.local());
                    else if (value instanceof Read local) { frameCtx(); c.loadConstant(local.local()).invokevirtual(FRAME,"place",sig(REF,CTX,CD_int)); }
                    else place(value);
                    c.invokestatic(FRAME,"reference",sig(REF,CTX,REF));
                } else expr(value);
            }
            var params = new ClassDesc[args.size() + 1];
            java.util.Arrays.fill(params, CD_Object);
            params[0] = CTX;
            c.invokestatic(runtime("B1Builtins"), name, sig(CD_Object, params));
            return true;
        }

        void call(Call call) {
            if (intrinsic(call)) return;
            line(call.at().line());
            int receiver = ref(), callee = ref(), dynamic = ref();
            resolve(call.target(),receiver,dynamic);
            c.astore(callee);
            int[] slots = call.args().stream().mapToInt(Argument::slot).toArray();
            if (fragment && slots.length > 2) {
                range(CALLABLE,callee,0,slots.length,1,(body,i,target) -> body.send(call.args().get(i),i,target));
            } else {
                for (int i = 0; i < slots.length; i++) send(call.args().get(i),i,callee);
            }
            invoke(call,callee,receiver,dynamic);
        }
        void invoke(Call call,int callee,int receiver,int dynamic) {
            Target t = call.target();
            int[] slots = call.args().stream().mapToInt(Argument::slot).toArray();
            line(call.at().line());
            if (fast) { /* the move protocol replaces outgoing registrations */ }
            else if (slots.length > 2) {
                c.aload(frame).aload(callee); indices(slots);
                c.invokevirtual(FRAME,"outgoingAll",sig(CD_void,CALLABLE,INTS));
            } else for (int i = 0; i < slots.length; i++) {
                c.aload(frame).aload(callee).loadConstant(i).loadConstant(slots[i])
                        .invokevirtual(FRAME, "outgoing", sig(CD_void, CALLABLE, CD_int, CD_int));
            }
            if (call.discardReturn()) c.aload(frame).aload(callee)
                    .invokevirtual(FRAME, "discardCallResult", sig(CD_void, CALLABLE));
            Label noCtor = c.newLabel(), done = c.newLabel();
            if (t.kind() == Dispatch.CONSTRUCTOR) {
                if (fast) c.aload(0).aload(receiver);
                else { frameCtx(); c.loadConstant(((StoreTemp)t.receiver()).id()).aload(receiver); }
                c.aload(callee).ifnull(noCtor);
            }
            if (t.kind() == Dispatch.DYNAMIC) {
                Label other = c.newLabel();
                c.aload(dynamic).instanceOf(CLOSURE).ifeq(other).aload(frame).aload(dynamic).checkcast(CLOSURE)
                        .putfield(FRAME,"nextClosure",CLOSURE).labelBinding(other);
            }
            c.aload(callee).aload(0).aload(receiver);
            if (slots.length <= 3) {
                for (int slot : slots) loadTemp(slot);
            } else if (fast) {
                c.loadConstant(slots.length).anewarray(CD_Object);
                for (int i = 0; i < slots.length; i++) { c.dup().loadConstant(i); loadTemp(slots[i]); c.aastore(); }
            } else {
                c.aload(frame); indices(slots);
                c.invokevirtual(FRAME,"arguments",sig(ARGS,INTS));
            }
            if (fast && slots.length > 0) {
                // Move: a generated callee owns the sent values from its entry on; a builtin borrows them.
                Label borrowed = c.newLabel();
                c.aload(callee).getfield(CALLABLE,"consumes",CD_boolean).ifeq(borrowed);
                for (int slot : slots) c.aconst_null().astore(temps[slot]);
                c.aload(0).aload(callee).putfield(CTX,"moved",CALLABLE);
                c.labelBinding(borrowed);
            }
            if (slots.length <= 3) {
                boolean frameless = t.kind() == Dispatch.FUNCTION && slots.length > 0 && slots.length < 3 && t.name().equalsIgnoreCase("implode");
                c.invokevirtual(CALLABLE, (frameless ? "invokeFrameless" : "invoke") + slots.length, fixed(slots.length));
            } else c.invokevirtual(CALLABLE, "invoke", sig(CD_Object, CTX, OBJ, ARGS));
            if (t.kind() == Dispatch.CONSTRUCTOR && fast) {
                c.goto_(done).labelBinding(noCtor).aconst_null().labelBinding(done)
                        .invokestatic(FRAME, "constructedFast", sig(CD_Object, CTX, OBJ, CD_Object));
                c.aconst_null().astore(temps[((StoreTemp)t.receiver()).id()]);
            } else if (t.kind() == Dispatch.CONSTRUCTOR) {
                c.goto_(done).labelBinding(noCtor).aconst_null().labelBinding(done)
                        .invokevirtual(FRAME, "constructed", sig(CD_Object, CTX, CD_int, OBJ, CD_Object));
            }
        }
    }
}
