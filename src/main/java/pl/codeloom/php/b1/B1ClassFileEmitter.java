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
    private static final MethodTypeDesc STEP = sig(CD_int, CTX, OBJ, CALLABLE, ARGS, CD_Object, CD_Object, FRAME);
    private static final MethodTypeDesc VALUE = STEP.changeReturnType(CD_Object);
    private static final MethodTypeDesc CTOR = sig(CD_void, BOOLS, NAMES);

    static MethodTypeDesc sig(ClassDesc ret, ClassDesc... args) { return MethodTypeDesc.of(ret, args); }
    private static MethodTypeDesc fixed(int n) {
        var args = new ClassDesc[n + 2];
        java.util.Arrays.fill(args, CD_Object);
        args[0] = CTX;
        args[1] = OBJ;
        return sig(CD_Object, args);
    }

    static byte[] emit(B1Cfg cfg) {
        return emit(cfg, new ArrayList<>());
    }
    static byte[] emit(B1Cfg cfg, List<byte[]> blobs) {
        return build(cfg, blobs, List.of(), Set.of());
    }

    record Chunk(int first, int end) {}
    // A first-pass method above the classfile u2 limit has no parsable Code attribute.
    // JDK 25 reports the measured length and the failing method while writing Code.
    private static final java.util.regex.Pattern CODE_OVERFLOW = java.util.regex.Pattern.compile(
            "^Code length ([0-9]+) is outside the allowed range in (run|resumeBody|fragmentBlock([0-9]+))\\(");

    record Emission(byte[] bytes, List<byte[]> blobs, int helpers, int maxCode) {}
    static Emission measured(B1Cfg cfg, int limit) {
        if (limit <= 0 || limit > 7500) throw new IllegalArgumentException("Invalid bytecode budget " + limit);
        var chunks = new ArrayList<Chunk>();
        var fragmented = new HashSet<Integer>();
        while (true) {
            var blobs = new ArrayList<byte[]>();
            byte[] bytes;
            try { bytes = build(cfg, blobs, chunks, fragmented); }
            catch (IllegalArgumentException ex) {
                var match = CODE_OVERFLOW.matcher(String.valueOf(ex.getMessage()));
                if (!match.find()) throw ex;
                if (chunks.isEmpty()) chunks.add(new Chunk(1,cfg.blocks().size()));
                else if (match.group(3) != null) split(chunks, fragmented, Integer.parseInt(match.group(3)), Integer.parseInt(match.group(1)), limit);
                else throw ex;
                continue;
            }
            var next = new ArrayList<>(chunks);
            int max = 0, helpers = 0;
            for (var method : ClassFile.of().parse(bytes).methods()) {
                int length = method.findAttribute(Attributes.code()).orElseThrow().codeLength();
                if (method.methodName().stringValue().startsWith("fragment")) helpers++;
                max = Math.max(max,length);
                if (length <= limit) continue;
                String name = method.methodName().stringValue();
                if (chunks.isEmpty() && (name.equals("run") || name.equals("resumeBody"))) next.add(new Chunk(1,cfg.blocks().size()));
                else if (name.startsWith("fragmentBlock")) {
                    split(next,fragmented,Integer.parseInt(name.substring("fragmentBlock".length())),length,limit);
                } else throw new IllegalArgumentException(name + " requires " + length + " bytes (budget " + limit + ")");
            }
            if (max <= limit) return new Emission(bytes, blobs, helpers, max);
            chunks = next;
        }
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
    private static byte[] build(B1Cfg cfg, List<byte[]> blobs, List<Chunk> chunks, Set<Integer> fragmented) {
        ClassDesc cls = ClassDesc.of("pl.codeloom.php.b1.Compiled" + cfg.function().id());
        var fragments = new Fragments(cls,fragmented);
        return ClassFile.of().build(cls, cb -> {
            cb.withFlags(ACC_FINAL | ACC_SUPER).withSuperclass(CALLABLE);
            cb.withMethodBody("<init>", CTOR, ACC_PUBLIC, c ->
                    c.aload(0).aload(1).aload(2).invokespecial(CALLABLE, "<init>", CTOR).return_());
            cb.withMethodBody("invoke", sig(CD_Object, CTX, OBJ, ARGS), ACC_PUBLIC, c ->
                    c.aload(1).aload(2).aload(0).aload(3).aconst_null().aconst_null().invokestatic(cls, "run", RUN).areturn());
            for (int n = 0; n <= 2; n++) {
                int arity = n;
                cb.withMethodBody("invoke" + arity, fixed(arity), ACC_PUBLIC, c -> {
                c.aload(1).aload(2).aload(0).aconst_null();
                for (int i = 0; i < 2; i++) { if (i < arity) c.aload(3 + i); else c.getstatic(CALLABLE,"OMITTED",CD_Object); }
                c.invokestatic(cls, "run", RUN).areturn();
                });
            }
            String dispatch = chunks.isEmpty() ? (cfg.function().generator() ? "resumeBody" : null) : dispatcher(cb,cls,chunks);
            if (cfg.function().generator() && chunks.isEmpty()) {
                cb.withMethodBody(dispatch,STEP,ACC_PRIVATE | ACC_STATIC,c ->
                        new Body(cfg,c,blobs,new Chunk(1,cfg.blocks().size()),fragments).emit(cls,null));
            }
            if (cfg.function().generator()) {
                cb.withMethodBody("resumeFrame",sig(CD_int,CTX,OBJ,FRAME),ACC_PUBLIC,c ->
                    c.aload(1).aload(2).aload(0).aconst_null().aconst_null().aconst_null().aload(3)
                            .invokestatic(cls,dispatch,STEP).ireturn());
            }
            cb.withMethodBody("run", RUN, ACC_PRIVATE | ACC_STATIC, c -> new Body(cfg,c,blobs,null,fragments).emit(cls,dispatch));
            for (var chunk : chunks) cb.withMethodBody("fragmentBlock" + chunk.first, STEP, ACC_PRIVATE | ACC_STATIC,
                    c -> new Body(cfg,c,blobs,chunk,fragments).emit(cls,null));
            for (int i = 0; i < fragments.methods.size(); i++) {
                var method = fragments.methods.get(i);
                cb.withMethodBody(method.name(),method.signature(),ACC_PRIVATE | ACC_STATIC,c -> {
                    var body = new Body(cfg,c,blobs,new Chunk(0,0),fragments);
                    body.fragment = true;
                    method.emit().accept(body);
                });
            }
        });
    }

    /** Oversized single blocks factor their expression trees at value boundaries. All PHP
     * owners stay in the original frame, including sends in progress and protected receivers. */
    private static final class Fragments {
        final ClassDesc cls;
        final Set<Integer> blocks;
        record Helper(String name, MethodTypeDesc signature, java.util.function.Consumer<Body> emit) {}
        final List<Helper> methods = new ArrayList<>();
        final Map<Expr,String> ids = new IdentityHashMap<>();
        Fragments(ClassDesc cls, Set<Integer> blocks) { this.cls = cls; this.blocks = blocks; }
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
        Body(B1Cfg cfg, CodeBuilder c, List<byte[]> blobs, Chunk chunk, Fragments fragments) {
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
        }
        int ref() { return c.allocateLocal(TypeKind.REFERENCE); }
        void frameCtx() { c.aload(frame).aload(0); }
        Label target(int id) {
            if (chunk != null && (id < chunk.first || id >= chunk.end)) edges.add(id);
            return labels[id];
        }
        void go(int id) { c.goto_(target(id)); }
        void line(int line) { c.aload(frame).loadConstant(line).putfield(FRAME, "line", CD_int); }

        void emit(ClassDesc cls, String dispatcher) {
            if (chunk == null) {
                c.aload(0).aload(2).ldc(f.at().file()).ldc(f.owner() == null ? f.name() : f.owner() + "::" + f.name()).loadConstant(cfg.tempCount())
                        .loadConstant(f.fileBody() ? 1 : 0)
                        .invokevirtual(CTX, "enter", sig(FRAME, CALLABLE, CD_String, CD_String, CD_int, CD_boolean)).astore(frame);
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
                go(cfg.entry());
            } else {
                c.aload(frame).getfield(FRAME,"result",CD_Object).astore(result);
                c.aload(frame).getfield(FRAME,"pending",CD_Throwable).astore(pending);
                if (chunk.end - chunk.first > 1) {
                    var cases = new ArrayList<java.lang.classfile.instruction.SwitchCase>();
                    for (int i = chunk.first; i < chunk.end; i++) cases.add(java.lang.classfile.instruction.SwitchCase.of(i,labels[i]));
                    c.aload(frame).getfield(FRAME,"pc",CD_int).tableswitch(chunk.first,chunk.end-1,labels[chunk.first],cases);
                }
            }
            for (var block : cfg.blocks().reversed()) {
                if (chunk != null && (block.label() < chunk.first || block.label() >= chunk.end)) continue;
                fragment = fragments.blocks.contains(block.label());
                Label start = labels[block.label()];
                c.labelBinding(start);
                switch (block.op()) {
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
                    case B1Cfg.Pass ignored -> c.nop();
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
                        frameCtx();
                        c.loadConstant(id).aload(pending).checkcast(THROWN)
                                .invokevirtual(FRAME, "takeException", sig(CD_void, CTX, CD_int, THROWN))
                                .aconst_null().astore(pending);
                        // A return cancelled by a throwing cleanup no longer owns its protected value.
                        c.aload(0).aload(result);
                        fallthrough();
                        c.astore(result).invokestatic(OBJ, "release", sig(CD_void, CTX, CD_Object));
                    }
                }
                Label end = c.newBoundLabel();
                int target = block.exceptional();
                if (handlers[target] == null) handlers[target] = c.newLabel();
                c.exceptionCatchAll(start, end, handlers[target]);
                go(block.normal());
            }
            for (int i = 0; i < handlers.length; i++) {
                if (handlers[i] != null) {
                    c.labelBinding(handlers[i]).astore(pending);
                    go(i);
                }
            }
            if (chunk != null && !edges.isEmpty()) {
                Label save = c.newLabel();
                for (int id : edges) c.labelBinding(labels[id]).loadConstant(id).goto_(save);
                c.labelBinding(save).aload(frame).aload(result).putfield(FRAME,"result",CD_Object);
                c.aload(frame).aload(pending).putfield(FRAME,"pending",CD_Throwable).ireturn();
            }
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
                case Write(var id, var value) -> {
                    if (longs.containsKey(id)) {
                        c.loadConstant((Long)((Literal)value).value()).lstore(longs.get(id));
                    } else {
                        frameCtx(); c.loadConstant(id); expr(value);
                        c.invokevirtual(FRAME, "write", sig(CD_void, CTX, CD_int, CD_Object));
                    }
                }
                case Bind(var id,var source) -> {
                    frameCtx(); c.loadConstant(id); place(source);
                    c.invokevirtual(FRAME,"bind",sig(CD_void,CTX,CD_int,runtime("B1Ref")));
                }
                case WriteStatic(var p,var value) -> {
                    place(p); c.aload(0); expr(value);
                    c.invokevirtual(runtime("B1Ref"),"setProperty",sig(CD_void,CTX,CD_Object));
                }
                case Global(var id) -> { frameCtx(); c.loadConstant(id).invokevirtual(FRAME,"bindGlobal",sig(CD_void,CTX,CD_int)); }
                case StaticVar(var id,var init) -> {
                    Label bound = c.newLabel();
                    frameCtx(); c.loadConstant(id).aload(2).invokevirtual(FRAME,"bindStatic",sig(CD_boolean,CTX,CD_int,CALLABLE)).ifeq(bound);
                    if (init != null) { frameCtx(); c.loadConstant(id); expr(init); c.invokevirtual(FRAME,"write",sig(CD_void,CTX,CD_int,CD_Object)); }
                    c.labelBinding(bound);
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
                    expr(recv); c.checkcast(OBJ).aload(0).aload(0); expr(name);
                    c.invokevirtual(CTX,"string",sig(CD_String,CD_Object)); expr(value);
                    c.invokevirtual(OBJ,"write",sig(CD_void,CTX,CD_String,CD_Object));
                }
                case CoalesceDim(var local,var key,var value) -> {
                    int k = ref(), v = ref();
                    Label present = c.newLabel();
                    expr(key); c.astore(k).aload(0); frameCtx();
                    c.loadConstant(local).invokevirtual(FRAME,"quiet",sig(CD_Object,CTX,CD_int)).aload(k).iconst_1()
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
                            .invokevirtual(OBJ, "write", sig(CD_void, CTX, CD_String, CD_Object));
                }
                case Return(var value) -> {
                    expr(value); c.invokestatic(OBJ, "retain", sig(CD_Object, CD_Object)).astore(result);
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
        void blob(Expr e, ClassDesc type) {
            int index = blobs.size();
            blobs.add(B1LiteralBlob.encode(e));
            c.ldc(java.lang.constant.DynamicConstantDesc.ofNamed(LITERAL_BOOTSTRAP,"literal",type,index));
        }
        void indices(int[] slots) {
            var bytes = java.nio.ByteBuffer.allocate(slots.length * Integer.BYTES);
            bytes.asIntBuffer().put(slots);
            int index = blobs.size();
            blobs.add(bytes.array());
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
        void truth(Expr e) { expr(e); c.invokestatic(CTX,"truth",sig(CD_boolean,CD_Object)); }
        void bool() { c.invokestatic(CD_Boolean,"valueOf",sig(CD_Boolean,CD_boolean)); }
        void array(ArrayValue a, int slot) {
            if (B1LiteralBlob.useBlob(a)) {
                if (!B1LiteralBlob.validAppendKeys(a)) c.aload(0).invokevirtual(CTX,"occupiedAppend",sig(ARRAY));
                else blob(a,ARRAY);
                return;
            }
            c.new_(ARRAY).dup().loadConstant(a.items().size()).invokespecial(ARRAY,"<init>",sig(CD_void,CD_int));
            if (slot >= 0) {
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
            switch (expr) {
                case Yield ignored -> throw new IllegalStateException("yield outside resume block");
                case Literal(var value) -> literal(value);
                case Read(var id) -> {
                    if (longs.containsKey(id)) { c.lload(longs.get(id)); boxLong(); }
                    else { frameCtx(); c.loadConstant(id).invokevirtual(FRAME, "read", sig(CD_Object, CTX, CD_int)); }
                }
                case Prepared(var operands,var result) -> { for (var temp : operands) { expr(temp); c.pop(); } expr(result); }
                case LoadTemp(var id) -> c.aload(frame).loadConstant(id).invokevirtual(FRAME,"temp",sig(CD_Object,CD_int));
                case ArrayValue a -> array(a,-1);
                case QuietRead(var id) -> { frameCtx(); c.loadConstant(id).invokevirtual(FRAME,"quiet",sig(CD_Object,CTX,CD_int)); }
                case Dim(var base,var key,var quiet) -> {
                    c.aload(0); expr(base); expr(key);
                    c.loadConstant(quiet ? 1 : 0).invokevirtual(CTX,"dim",sig(CD_Object,CD_Object,CD_Object,CD_boolean));
                }
                case Update(var id,var value) -> {
                    frameCtx(); c.loadConstant(id); expr(value);
                    int saved = ref(); c.dup().astore(saved).invokevirtual(FRAME,"write",sig(CD_void,CTX,CD_int,CD_Object)).aload(saved);
                }
                case Increment(var id, var up, var prefix) -> {
                    frameCtx(); c.loadConstant(id).loadConstant(up ? 1 : 0).loadConstant(prefix ? 1 : 0)
                            .invokevirtual(FRAME,"increment",sig(CD_Object,CTX,CD_int,CD_boolean,CD_boolean));
                }
                case Cast(var value,var string) -> {
                    c.aload(0); expr(value); c.invokevirtual(CTX,string ? "string" : "castLong",sig(string ? CD_String : CD_Object,CD_Object));
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
                        body.c.aload(carrier).loadConstant(i); body.frameCtx(); body.c.loadConstant(cap.outer());
                        if (cap.byRef()) body.c.invokevirtual(FRAME,"reference",sig(runtime("B1Ref"),CTX,CD_int));
                        else body.c.invokevirtual(FRAME,"read",sig(CD_Object,CTX,CD_int));
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
                    expr(recv); c.aload(0).ldc(name).invokestatic(CTX,"quietProperty",sig(CD_Object,CD_Object,CTX,CD_String));
                }
                case Property(var recv, var name) -> {
                    expr(recv); c.aload(0).ldc(name).invokestatic(CTX, "readProperty", sig(CD_Object, CD_Object, CTX, CD_String));
                }
                case Binary binary -> {
                    Operator op = binary.op();
                    if (op == Operator.AND || op == Operator.OR) {
                        Label done = c.newLabel();
                        truth(binary.left()); c.dup();
                        if (op == Operator.AND) c.ifeq(done); else c.ifne(done);
                        c.pop(); truth(binary.right()); c.labelBinding(done); bool();
                        break;
                    }
                    if (binary.preservesValue()) {
                        Label done = c.newLabel();
                        expr(binary.left()); c.dup();
                        if (op == Operator.COALESCE) c.ifnonnull(done);
                        else c.invokestatic(CTX,"truth",sig(CD_boolean,CD_Object)).ifne(done);
                        c.pop(); expr(binary.right()); c.labelBinding(done);
                        break;
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
                    else if (op != Operator.ADD && op != Operator.CONCAT) c.loadConstant(op.ordinal())
                            .invokevirtual(CTX,"compare",sig(CD_Object,CD_Object,CD_Object,CD_int));
                    else c.invokevirtual(CTX, binary.op() == Operator.ADD ? "add" : "concat", sig(CD_Object, CD_Object, CD_Object));
                    cleanup(binary.consumed(), false);
                }
                case StoreTemp(var id, var value) -> {
                    if (value instanceof ArrayValue array && !B1LiteralBlob.useBlob(array)) {
                        array(array,id);
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

        void place(Expr root) {
            if (root instanceof Prepared p) {
                for (StoreTemp t : p.operands()) { expr(t); c.pop(); }
                place(p.result());
            } else if (root instanceof StoreTemp t) place(t.value());
            else if (root instanceof StaticProperty p) {
                c.aload(0).ldc(lower(p.owner())).ldc(p.name()).invokevirtual(CTX,"staticProperty",sig(runtime("B1Ref"),CD_String,CD_String));
            } else if (root instanceof Read local) {
                frameCtx(); c.loadConstant(local.local()).invokevirtual(FRAME,"place",sig(runtime("B1Ref"),CTX,CD_int));
            } else if (root instanceof Dim dim) {
                place(dim.base()); c.aload(0); expr(dim.key()); c.iconst_0()
                        .invokevirtual(runtime("B1Ref"),"child",sig(runtime("B1Ref"),CTX,CD_Object,CD_boolean));
            } else {
                Property prop = (Property)root;
                expr(prop.receiver()); c.checkcast(OBJ).aload(0).ldc(prop.name()).invokevirtual(OBJ,"property",sig(runtime("B1Ref"),CTX,CD_String));
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
            if (u.root() instanceof Read local) {
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

        void send(Argument arg, int i, int callee) {
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
                c.aconst_null().astore(receiver).aload(0).ldc(lower(t.name())).invokestatic(CTX, "function", sig(CALLABLE, CTX, CD_String));
            } else {
                expr(t.receiver());
                c.checkcast(OBJ).astore(receiver).aload(0).aload(receiver);
                if (t.kind() == Dispatch.CONSTRUCTOR) c.invokevirtual(CTX, "constructor", sig(CALLABLE, OBJ));
                else c.ldc(lower(t.name())).ldc(t.name()).invokevirtual(CTX, "resolve", sig(CALLABLE, OBJ, CD_String, CD_String));
            }
        }

        int callee(int slot) {
            int local = ref();
            c.aload(frame).loadConstant(slot).invokevirtual(FRAME,"temp",sig(CD_Object,CD_int)).checkcast(CALLABLE).astore(local);
            return local;
        }

        void call(Call call) {
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
            if (slots.length > 2) {
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
                frameCtx(); c.loadConstant(((StoreTemp)t.receiver()).id()).aload(receiver);
                c.aload(callee).ifnull(noCtor);
            }
            if (t.kind() == Dispatch.DYNAMIC) {
                Label other = c.newLabel();
                c.aload(dynamic).instanceOf(CLOSURE).ifeq(other).aload(frame).aload(dynamic).checkcast(CLOSURE)
                        .putfield(FRAME,"nextClosure",CLOSURE).labelBinding(other);
            }
            c.aload(callee).aload(0).aload(receiver);
            if (slots.length <= 2) {
                for (int slot : slots) c.aload(frame).loadConstant(slot).invokevirtual(FRAME,"temp",sig(CD_Object,CD_int));
                boolean frameless = t.kind() == Dispatch.FUNCTION && slots.length > 0 && t.name().equalsIgnoreCase("implode");
                c.invokevirtual(CALLABLE, (frameless ? "invokeFrameless" : "invoke") + slots.length, fixed(slots.length));
            } else {
                c.aload(frame); indices(slots);
                c.invokevirtual(FRAME,"arguments",sig(ARGS,INTS));
                c.invokevirtual(CALLABLE, "invoke", sig(CD_Object, CTX, OBJ, ARGS));
            }
            if (t.kind() == Dispatch.CONSTRUCTOR) {
                c.goto_(done).labelBinding(noCtor).aconst_null().labelBinding(done)
                        .invokevirtual(FRAME, "constructed", sig(CD_Object, CTX, CD_int, OBJ, CD_Object));
            }
        }
    }
}
