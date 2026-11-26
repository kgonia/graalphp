package pl.codeloom.php.b1;

import java.lang.classfile.*;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.HashMap;
import java.util.Map;

import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;
import static pl.codeloom.php.ir.IrUnit.*;
import static pl.codeloom.php.ir.AsciiCase.lower;

/** Emits executable CFG edges and PHP operations. The generated class carries no reference to compiler data. */
final class B1ClassFileEmitter {
    private static ClassDesc runtime(String name) { return ClassDesc.of("pl.codeloom.php.b1.runtime." + name); }
    static final ClassDesc CALLABLE = runtime("B1Callable"), CTX = runtime("B1RequestContext");
    private static final ClassDesc FRAME = runtime("B1Frame"), OBJ = runtime("B1Object"), THROWN = runtime("B1Thrown");
    private static final ClassDesc ARGS = CD_Object.arrayType(), BOOLS = CD_boolean.arrayType(), NAMES = CD_String.arrayType();
    private static final MethodTypeDesc RUN = sig(CD_Object, CTX, OBJ, CALLABLE, ARGS, CD_Object, CD_Object);
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
        ClassDesc cls = ClassDesc.of("pl.codeloom.php.b1.Compiled" + cfg.function().id());
        return ClassFile.of().build(cls, cb -> {
            cb.withFlags(ACC_FINAL | ACC_SUPER).withSuperclass(CALLABLE);
            cb.withMethodBody("<init>", CTOR, ACC_PUBLIC, c ->
                    c.aload(0).aload(1).aload(2).invokespecial(CALLABLE, "<init>", CTOR).return_());
            cb.withMethodBody("invoke", sig(CD_Object, CTX, OBJ, ARGS), ACC_PUBLIC, c ->
                    c.aload(1).aload(2).aload(0).aload(3).aconst_null().aconst_null().invokestatic(cls, "run", RUN).areturn());
            int arity = cfg.function().params().size();
            if (arity <= 2) cb.withMethodBody("invoke" + arity, fixed(arity), ACC_PUBLIC, c -> {
                c.aload(1).aload(2).aload(0).aconst_null();
                for (int i = 0; i < 2; i++) { if (i < arity) c.aload(3 + i); else c.aconst_null(); }
                c.invokestatic(cls, "run", RUN).areturn();
            });
            cb.withMethodBody("run", RUN, ACC_PRIVATE | ACC_STATIC, c -> new Body(cfg, c).emit());
        });
    }

    private static final class Body {
        // run(ctx, self, callable, argv, arg0, arg1); fixed-arity entry points leave argv null.
        final B1Cfg cfg;
        final CodeBuilder c;
        final IrFunction f;
        final int frame, result, pending;
        final Label[] labels, handlers;
        final Map<Integer, Integer> longs = new HashMap<>();

        Body(B1Cfg cfg, CodeBuilder c) {
            this.cfg = cfg;
            this.c = c;
            f = cfg.function();
            frame = ref(); result = ref(); pending = ref();
            labels = new Label[cfg.blocks().size()];
            handlers = new Label[labels.length];
            for (int i = 0; i < labels.length; i++) labels[i] = c.newLabel();
        }
        int ref() { return c.allocateLocal(TypeKind.REFERENCE); }
        void frameCtx() { c.aload(frame).aload(0); }
        void go(int id) { c.goto_(labels[id]); }
        void line(int line) { c.aload(frame).loadConstant(line).putfield(FRAME, "line", CD_int); }

        void emit() {
            c.aload(0).aload(2).ldc(f.at().file()).ldc(f.owner() == null ? f.name() : f.owner() + "::" + f.name()).loadConstant(f.temps().size())
                    .loadConstant(f.fileBody() ? 1 : 0)
                    .invokevirtual(CTX, "enter", sig(FRAME, CALLABLE, CD_String, CD_String, CD_int, CD_boolean)).astore(frame);
            literal(f.fileBody() ? 1L : null);
            c.astore(result).aconst_null().astore(pending);
            for (int id : cfg.longLocals()) {
                int slot = c.allocateLocal(TypeKind.LONG);
                longs.put(id, slot);
                c.lconst_0().lstore(slot);
            }
            go(cfg.entry());
            for (var block : cfg.blocks().reversed()) {
                Label start = labels[block.label()];
                c.labelBinding(start);
                switch (block.op()) {
                    case B1Cfg.Leave ignored -> {
                        frameCtx();
                        c.aload(pending).aload(result).invokevirtual(FRAME, "leave", sig(CD_Object, CTX, CD_Throwable, CD_Object)).areturn();
                        continue;
                    }
                    case B1Cfg.Enter ignored -> bind();
                    case B1Cfg.Declare(var name, var at) -> {
                        line(at.line());
                        c.aload(0).ldc(f.at().file()).ldc(lower(name))
                                .invokevirtual(CTX, "declareClass", sig(CD_void, CD_String, CD_String));
                    }
                    case B1Cfg.Execute(var at, var action) -> { line(at.line()); action(action); }
                    case B1Cfg.Cleanup(var temps, var unwind) -> cleanup(temps, unwind);
                    case B1Cfg.Catch(var type, var id) -> {
                        c.aload(pending).ldc(lower(type)).invokestatic(THROWN, "matches", sig(CD_boolean, CD_Throwable, CD_String))
                                .ifeq(labels[block.exceptional()]);
                        frameCtx();
                        c.loadConstant(id).aload(pending).checkcast(THROWN)
                                .invokevirtual(FRAME, "takeException", sig(CD_void, CTX, CD_int, THROWN))
                                .aconst_null().astore(pending);
                        // A return cancelled by a throwing cleanup no longer owns its protected value.
                        c.aload(0).aload(result);
                        literal(f.fileBody() ? 1L : null);
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
        }

        void bind() {
            line(f.at().line());
            frameCtx();
            c.aload(2).aload(3).aload(4).aload(5).invokevirtual(FRAME, "bindArguments",
                    sig(CD_void, CTX, CALLABLE, ARGS, CD_Object, CD_Object));
            for (int i = 0; i < f.params().size(); i++) {
                Param p = f.params().get(i);
                line(p.at().line());
                frameCtx(); c.loadConstant(i).loadConstant(f.params().size()).loadConstant(p.integer() ? 1 : 0)
                        .invokevirtual(FRAME, "checkParameter", sig(CD_void, CTX, CD_int, CD_int, CD_boolean));
            }
        }

        void cleanup(ReleaseTemps temps, boolean unwind) {
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
                case WriteProperty(var recv, var name, var value) -> {
                    expr(value); int val = ref(); c.astore(val);
                    expr(recv); c.checkcast(OBJ).aload(0).ldc(name).aload(val)
                            .invokevirtual(OBJ, "write", sig(CD_void, CTX, CD_String, CD_Object));
                }
                case Return(var value) -> {
                    expr(value); c.invokestatic(OBJ, "retain", sig(CD_Object, CD_Object)).astore(result);
                }
                case Raise(var value) -> {
                    expr(value); int val = ref(); c.astore(val).new_(THROWN).dup().aload(val)
                            .invokespecial(THROWN, "<init>", sig(CD_void, CD_Object)).athrow();
                }
                case Evaluate(var value) -> { expr(value); c.pop(); }
            }
        }

        void boxLong() { c.invokestatic(CD_Long, "valueOf", sig(CD_Long, CD_long)); }
        void literal(Object value) {
            if (value == null) c.aconst_null();
            else if (value instanceof Long n) { c.loadConstant(n); boxLong(); }
            else c.ldc((String)value);
        }
        void expr(Expr expr) {
            switch (expr) {
                case Literal(var value) -> literal(value);
                case Read(var id) -> {
                    if (longs.containsKey(id)) { c.lload(longs.get(id)); boxLong(); }
                    else { frameCtx(); c.loadConstant(id).invokevirtual(FRAME, "read", sig(CD_Object, CTX, CD_int)); }
                }
                case This ignored -> c.aload(1);
                case Property(var recv, var name) -> {
                    expr(recv); c.aload(0).ldc(name).invokestatic(CTX, "readProperty", sig(CD_Object, CD_Object, CTX, CD_String));
                }
                case Binary binary -> {
                    c.aload(0);
                    if (binary.deferredLeft()) {
                        expr(binary.right());
                        expr(binary.left());
                        c.swap();
                    } else {
                        expr(binary.left());
                        expr(binary.right());
                    }
                    c.invokevirtual(CTX, binary.op() == Operator.ADD ? "add" : "concat", sig(CD_Object, CD_Object, CD_Object));
                    cleanup(binary.consumed(), false);
                }
                case StoreTemp(var id, var value) -> {
                    frameCtx(); c.loadConstant(id); expr(value);
                    c.loadConstant(value instanceof Call || value instanceof Allocate ? 1 : 0)
                            .invokevirtual(FRAME, "storeTemp", sig(CD_Object, CTX, CD_int, CD_Object, CD_boolean));
                    if (value instanceof Call call) cleanup(call.cleanup(), false);
                }
                case Allocate(var name, var at) -> {
                    line(at.line());
                    c.aload(0).ldc(lower(name)).ldc(name).invokevirtual(CTX, "allocate", sig(OBJ, CD_String, CD_String));
                }
                case Call call -> call(call);
            }
        }

        void call(Call call) {
            Target t = call.target();
            line(call.at().line());
            int receiver = ref(), callee = ref();
            if (t.kind() == Dispatch.FUNCTION) {
                c.aconst_null().astore(receiver).aload(0).ldc(lower(t.name())).invokestatic(CTX, "function", sig(CALLABLE, CTX, CD_String));
            } else {
                expr(t.receiver());
                c.checkcast(OBJ).astore(receiver).aload(0).aload(receiver);
                if (t.kind() == Dispatch.CONSTRUCTOR) c.invokevirtual(CTX, "constructor", sig(CALLABLE, OBJ));
                else c.ldc(lower(t.name())).ldc(t.name()).invokevirtual(CTX, "resolve", sig(CALLABLE, OBJ, CD_String, CD_String));
            }
            c.astore(callee);
            int[] operands = new int[call.args().size()];
            for (int i = 0; i < operands.length; i++) {
                Argument arg = call.args().get(i);
                if (!(arg.value() instanceof StoreTemp)) { frameCtx(); c.loadConstant(arg.slot()); }
                if (arg.bindVariable()) {
                    frameCtx(); c.loadConstant(((Read)arg.value()).local()).aload(callee).loadConstant(i)
                            .invokevirtual(FRAME, "send", sig(CD_Object, CTX, CD_int, CALLABLE, CD_int));
                } else {
                    frameCtx(); c.aload(callee).loadConstant(i);
                    expr(arg.value());
                    c.loadConstant(arg.value() instanceof StoreTemp ? 1 : 0)
                            .invokevirtual(FRAME, "sendValue", sig(CD_Object, CTX, CALLABLE, CD_int, CD_Object, CD_boolean));
                }
                if (!(arg.value() instanceof StoreTemp)) c.iconst_0()
                        .invokevirtual(FRAME, "storeTemp", sig(CD_Object, CTX, CD_int, CD_Object, CD_boolean));
                operands[i] = ref(); c.astore(operands[i]);
            }
            line(call.at().line());
            for (int i = 0; i < operands.length; i++) {
                c.aload(frame).aload(callee).loadConstant(i).loadConstant(call.args().get(i).slot())
                        .invokevirtual(FRAME, "outgoing", sig(CD_void, CALLABLE, CD_int, CD_int));
            }
            if (call.discardReturn()) c.aload(frame).aload(callee)
                    .invokevirtual(FRAME, "discardCallResult", sig(CD_void, CALLABLE));
            Label noCtor = c.newLabel(), done = c.newLabel();
            if (t.kind() == Dispatch.CONSTRUCTOR) {
                frameCtx(); c.loadConstant(((StoreTemp)t.receiver()).id()).aload(receiver);
                c.aload(callee).ifnull(noCtor);
            }
            c.aload(callee).aload(0).aload(receiver);
            if (operands.length <= 2) {
                for (int slot : operands) c.aload(slot);
                c.invokevirtual(CALLABLE, "invoke" + operands.length, fixed(operands.length));
            } else {
                c.loadConstant(operands.length).anewarray(CD_Object);
                for (int i = 0; i < operands.length; i++) c.dup().loadConstant(i).aload(operands[i]).aastore();
                c.invokevirtual(CALLABLE, "invoke", sig(CD_Object, CTX, OBJ, ARGS));
            }
            if (t.kind() == Dispatch.CONSTRUCTOR) {
                c.goto_(done).labelBinding(noCtor).aconst_null().labelBinding(done)
                        .invokevirtual(FRAME, "constructed", sig(CD_Object, CTX, CD_int, OBJ, CD_Object));
            }
        }
    }
}
