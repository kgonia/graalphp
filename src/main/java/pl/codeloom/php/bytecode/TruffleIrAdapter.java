package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeLocal;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.bytecode.StackValue;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.source.SourceSection;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.ir.IrSourceSpan;
import pl.codeloom.php.ir.IrUnit;
import pl.codeloom.php.nodes.vars.VariableVariableNode;
import pl.codeloom.php.runtime.*;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static pl.codeloom.php.ir.IrUnit.*;

/** Replayable Bytecode DSL parser for the structured seam. No AST or legacy body translator is involved. */
public final class TruffleIrAdapter {
    private static final Map<String, Boolean> translated = Collections.synchronizedMap(new WeakHashMap<>());
    public static boolean wasTranslatedViaIr(String file) { return translated.containsKey(file); }
    public static void usedLegacy(String file) { translated.remove(file); }

    private final PhpLanguage language;
    private final Source source;
    private final List<PhpFunction> functions = new ArrayList<>();
    private final List<ClassTemplate> classes = new ArrayList<>();
    private final Set<String> linkedClasses = new HashSet<>();

    private TruffleIrAdapter(PhpLanguage language, Source source) {
        this.language = language;
        this.source = source;
    }

    public static PhpBytecodeRootNode translate(PhpLanguage language, Source source, IrUnit unit,
                                                Map<String, PhpFunction> declarations, List<ClassTemplate> classes,
                                                Set<String> linkedClasses) {
        var adapter = new TruffleIrAdapter(language, source);
        adapter.functions.add(null);
        for (IrFunction f : unit.functions()) {
            if (f.fileBody()) continue;
            var fn = declarations.get(f.owner() == null ? f.name() : f.owner() + "::" + f.name());
            fn.setBodyBeforePublication(() -> adapter.new Body(f, fn.getParams()).build().getCallTarget());
            adapter.functions.add(fn);
        }
        adapter.classes.addAll(classes);
        adapter.linkedClasses.addAll(linkedClasses);
        var root = adapter.new Body(unit.functions().getFirst(), new PhpFunction.Param[0]).build();
        translated.put(unit.file(), true);
        return root;
    }

    private final class Body {
        final IrFunction f;
        final PhpFunction.Param[] params;
        // The DSL may replay with source information. Site identity must survive replay.
        final Map<Object, Object> constants = new HashMap<>();
        LocalAccessor[] variables, temporaries;
        LocalAccessor extra;
        int[] uncounted;

        Body(IrFunction f, PhpFunction.Param[] params) { this.f = f; this.params = params; }

        @SuppressWarnings("unchecked")
        <T> T constant(Object key, Supplier<T> make) {
            return (T) constants.computeIfAbsent(key, ignored -> make.get());
        }

        PhpBytecodeRootNode build() {
            var root = PhpBytecodeRootNodeGen.create(language, BytecodeConfig.DEFAULT, b -> new Run(b).emit()).getNode(0);
            SourceSection section = null;
            if (source != null) {
                int first = f.at().line();
                int last = Math.max(first, f.endLine());
                if (f.fileBody()) section = source.createSection(0, source.getLength());
                else if (first > 0 && last <= source.getLineCount()) {
                    int start = source.getLineStartOffset(first);
                    int end = source.getLineStartOffset(last) + source.getLineLength(last);
                    section = source.createSection(start, end - start);
                }
            }
            root.initialize(f.owner() == null ? f.name() : f.owner() + "::" + f.name(), f.at().file(), false,
                    section, params, IntStream.range(0, params.length).toArray(), null,
                    variables, new LocalAccessor[0], f.owner());
            boolean[] moved = new boolean[f.temps().size()];
            root.irTemps = f.temps().toArray(TempKind[]::new);
            root.initializeTemporaries(temporaries, moved, extra, uncounted);
            if (f.fileBody()) root.initializeFile(false);
            else root.initializeNames(f.locals().toArray(String[]::new), null);
            return root;
        }

        private final class Run {
            final PhpBytecodeRootNodeGen.Builder b;
            final BytecodeLocal[] locals = new BytecodeLocal[f.fileBody() ? 0 : f.locals().size()];
            final BytecodeLocal[] temps = new BytecodeLocal[f.temps().size()];
            final List<Integer> borrowedOffsets = new ArrayList<>();

            Run(PhpBytecodeRootNodeGen.Builder b) { this.b = b; }

            void emit() {
                b.beginRoot();
                for (int i = 0; i < locals.length; i++) locals[i] = b.createLocal(f.locals().get(i), null);
                for (int i = 0; i < temps.length; i++) temps[i] = b.createLocal();
                if (source != null) b.beginSource(source);
                if (f.fileBody()) {
                    if (!f.locals().isEmpty()) b.emitAttachGlobals(constant(f.locals(), () -> f.locals().toArray(String[]::new)));
                    for (PhpFunction fn : functions) {
                        if (fn != null && fn.getClassName() == null) b.emitDeclareFunction(fn, true);
                    }
                    for (ClassTemplate cls : classes) {
                        if (linkedClasses.contains(Str.lower(cls.getName()))) b.emitEarlyClass(cls, false);
                    }
                } else {
                    var extras = b.createLocal();
                    extra = LocalAccessor.constantOf(extras);
                    b.emitTakeExtraArguments(extras, params.length);
                    for (int i = 0; i < params.length; i++) {
                        b.beginIfThen(); b.emitArgumentGiven(i);
                        b.beginBindParameter(locals[i], i); b.emitArgument(i); b.endBindParameter();
                        b.endIfThen();
                    }
                    for (int i = 0; i < params.length; i++) {
                        beginLine(f.params().get(i).at());
                        b.emitVerifyIrParameter(locals[i], i);
                        endLine();
                    }
                }
                statements(f.body());
                b.beginReturn();
                b.emitLoadConstant(f.fileBody() ? (Object) 1L : PhpNull.NULL);
                b.endReturn();
                if (source != null) b.endSource();
                b.endRoot();
                if (variables == null) {
                    variables = Arrays.stream(locals).map(LocalAccessor::constantOf).toArray(LocalAccessor[]::new);
                    temporaries = Arrays.stream(temps).map(LocalAccessor::constantOf).toArray(LocalAccessor[]::new);
                    uncounted = borrowedOffsets.stream().mapToInt(Integer::intValue).toArray();
                }
            }

            void beginLine(IrSourceSpan at) {
                if (source != null) {
                    int line = at.line();
                    if (line < 1 || line > source.getLineCount()) b.beginSourceSection(0, 0);
                    else b.beginSourceSection(source.getLineStartOffset(line), source.getLineLength(line));
                }
            }
            void endLine() { if (source != null) b.endSourceSection(); }

            void statements(List<Statement> statements) {
                for (Statement statement : statements) {
                    if (statement instanceof ClassDeclaration decl) {
                        if (!linkedClasses.contains(Str.lower(decl.name()))) {
                            beginLine(decl.at());
                            b.emitDeclareClass(classes.get(decl.id()));
                            endLine();
                        }
                        continue;
                    }
                    if (statement instanceof TryCatch tc) { tryCatch(tc); continue; }
                    Region region = (Region) statement;
                    beginLine(region.at());
                    boolean guarded = !region.exceptional().slots().isEmpty();
                    if (guarded) { b.beginTryCatch(); b.beginBlock(); }
                    action(region.action(), region.normal());
                    if (!(region.action() instanceof Return)) release(region.normal());
                    if (guarded) {
                        b.endBlock();
                        b.beginUnwindTemps(indices(region.exceptional()));
                        b.emitLoadException();
                        b.endUnwindTemps();
                        b.endTryCatch();
                    }
                    endLine();
                }
            }

            void release(ReleaseTemps release) {
                for (ReleaseTemp t : release.slots()) b.emitReleaseTemp(temps[t.id()], t.id());
            }

            Temporaries indices(ReleaseTemps release) {
                return constant(release, () -> new Temporaries(release.slots().stream().mapToInt(ReleaseTemp::id).toArray()));
            }

            void tryCatch(TryCatch tc) {
                b.beginTryCatch();
                b.beginBlock();
                statements(tc.body());
                b.endBlock();
                b.beginBlock();
                var thrown = b.createLocal();
                borrowedOffsets.add(thrown.getLocalOffset());
                b.beginStoreLocal(thrown);
                b.beginCaughtThrowable();
                b.emitLoadException();
                b.endCaughtThrowable();
                b.endStoreLocal();
                b.beginIfThenElse();
                b.beginCatchMatches(constant(tc, () -> new String[]{Str.lower(tc.type())}));
                b.emitLoadLocal(thrown);
                b.endCatchMatches();
                b.beginBlock();
                var flight = b.createLocal();
                borrowedOffsets.add(flight.getLocalOffset());
                b.beginStoreLocal(flight); b.emitLoadException(); b.endStoreLocal();
                b.beginTryFinally(() -> {
                    b.beginReleaseInFlight(); b.emitLoadLocal(flight); b.endReleaseInFlight();
                });
                write(tc.variable(), () -> b.emitLoadLocal(thrown));
                b.endTryFinally();
                statements(tc.handler());
                b.endBlock();
                b.beginRethrow();
                b.emitLoadException();
                b.endRethrow();
                b.endIfThenElse();
                b.endBlock();
                b.endTryCatch();
            }

            void action(Action action, ReleaseTemps normal) {
                switch (action) {
                    case Echo(var value) -> { b.beginEcho(); expr(value); b.endEcho(); }
                    case Return(var value) -> {
                        b.beginReturn();
                        if (!normal.slots().isEmpty()) b.beginReleaseAfter();
                        b.beginOwn(); expr(value); b.endOwn(true);
                        if (!normal.slots().isEmpty()) b.endReleaseAfter(indices(normal), PhpBytecodeRootNode.ReleaseAfter.RETURNING);
                        b.endReturn();
                    }
                    case Raise(var value) -> { b.beginThrow(); expr(value); b.endThrow(); }
                    case Write(var id, var value) -> write(id, () -> expr(value));
                    case WriteProperty(var recv, var name, var value) -> {
                        b.beginWriteProp(prop(recv, name)); expr(value); expr(recv); b.endWriteProp();
                    }
                    case Evaluate(var value) -> expr(value);
                }
            }

            PropSite prop(Expr receiver, String name) {
                var key = new Property(receiver, name);
                return constant(key, () -> new PropSite(Str.sourceKey(name), f.owner() == null ? null : Str.lower(f.owner()),
                        false, receiver instanceof This, null));
            }

            void write(int id, Runnable value) {
                if (f.fileBody()) {
                    b.beginWriteRef(); value.run(); fileVariable(id, true); b.endWriteRef();
                } else {
                    b.beginWriteVarStatement(locals[id]); value.run(); b.endWriteVarStatement();
                }
            }

            void fileVariable(int id, boolean ref) {
                if (ref) b.beginVariableRef(VariableVariableNode.W); else b.beginVariableValue(VariableVariableNode.R);
                literal(f.locals().get(id));
                if (ref) b.endVariableRef(); else b.endVariableValue();
            }

            void literal(Object value) {
                b.emitLoadConstant(value instanceof String s ? constant(s, () -> Str.of(s))
                        : value == null ? PhpNull.NULL : value);
            }

            void expr(Expr expr) {
                switch (expr) {
                    case Literal(var value) -> literal(value);
                    case Read(var id) -> { if (f.fileBody()) fileVariable(id, false); else b.emitReadVar(locals[id]); }
                    case This ignored -> b.emitReadThis();
                    case Property(var recv, var name) -> { b.beginReadProp(prop(recv, name)); expr(recv); b.endReadProp(); }
                    case Binary binary -> {
                        var consumed = binary.consumed();
                        if (!consumed.slots().isEmpty()) b.beginReleaseAfter();
                        b.beginBlock();
                        BytecodeLocal right = null;
                        if (binary.deferredLeft()) {
                            right = b.createLocal();
                            borrowedOffsets.add(right.getLocalOffset());
                            b.beginStoreLocal(right); expr(binary.right()); b.endStoreLocal();
                        }
                        if (binary.op() == Operator.ADD) b.beginAdd(); else b.beginConcat();
                        expr(binary.left());
                        if (right == null) expr(binary.right()); else b.emitLoadLocal(right);
                        if (binary.op() == Operator.ADD) b.endAdd(); else b.endConcat();
                        b.endBlock();
                        if (!consumed.slots().isEmpty()) b.endReleaseAfter(indices(consumed), PhpBytecodeRootNode.ReleaseAfter.CONSUMES);
                    }
                    case StoreTemp temp -> store(temp, () -> expr(temp.value()));
                    case Allocate a -> {
                        b.beginInstantiate();
                        b.emitResolveClass(constant(a, () -> new ClassSite(a.className(), language.classId(Str.lower(a.className())))));
                        b.endInstantiate();
                    }
                    case Call call -> call(call, false);
                }
            }

            void store(StoreTemp temp, Runnable evaluate) {
                Expr value = temp.value();
                Call call = value instanceof Call c && !c.cleanup().slots().isEmpty() ? c : null;
                if (call != null) b.beginReleaseAfter();
                b.beginIrStore(temps[temp.id()], temp.id());
                evaluate.run();
                b.endIrStore(value instanceof Call || value instanceof Allocate);
                if (call != null) b.endReleaseAfter(indices(call.cleanup()), PhpBytecodeRootNode.ReleaseAfter.CONSUMES);
            }

            void call(Call call, boolean keepRef) {
                var t = call.target();
                beginLine(call.at());
                if (t.kind() == Dispatch.FUNCTION) {
                    CallInfo info = constant(call, () -> new CallInfo(t.name(), language.functionId(Str.lower(t.name())), -1, -1, false, call.discardReturn()));
                    b.beginCall(info);
                    b.beginBindStackValue();
                    b.emitResolveFunction(info);
                } else {
                    MethodSite site = null;
                    if (t.kind() == Dispatch.CONSTRUCTOR) {
                        // Transfer NEW's protected receiver to the expression result after a successful constructor.
                        b.beginFinishConstruction(temps[((StoreTemp)t.receiver()).id()]);
                        b.beginConstruct();
                    } else {
                        site = constant(call, () -> new MethodSite(t.name(), false,
                                MethodSite.UNCOUNTED, call.discardReturn()));
                        b.beginCallMethod(site, keepRef);
                    }
                    b.beginBindStackValue(); expr(t.receiver()); var recv = b.endBindStackValue();
                    b.beginBindStackValue();
                    if (site == null) b.beginResolveConstructor(); else b.beginResolveMethod(site);
                    b.emitLoadStackValue(recv);
                    if (site == null) b.endResolveConstructor(); else b.endResolveMethod();
                }
                var callee = b.endBindStackValue();
                int index = 0;
                for (Argument arg : call.args()) {
                    boolean last = index + 1 == call.args().size();
                    if (last) {
                        var slots = constant(call.args(), () -> new Temporaries(call.args().stream().mapToInt(Argument::slot).toArray()));
                        b.beginTransferSends(slots);
                        b.emitLoadStackValue(callee);
                    }
                    if (!(arg.value() instanceof StoreTemp)) b.beginIrStore(temps[arg.slot()], arg.slot());
                    send(arg, index++, callee);
                    if (!(arg.value() instanceof StoreTemp)) b.endIrStore(false);
                    if (last) b.endTransferSends();
                }
                switch (t.kind()) {
                    case FUNCTION -> b.endCall();
                    case METHOD -> b.endCallMethod();
                    case CONSTRUCTOR -> {
                        b.endConstruct();
                        b.endFinishConstruction();
                    }
                }
                endLine();
            }

            void send(Argument arg, int index, StackValue callee) {
                if (arg.bindVariable()) {
                    int id = ((Read)arg.value()).local();
                    if (f.fileBody()) {
                        b.beginConditional();
                        b.beginArgByRef(index); b.emitLoadStackValue(callee); b.endArgByRef();
                        fileVariable(id, true); fileVariable(id, false); b.endConditional();
                    } else {
                        b.beginSendVar(locals[id], index); b.emitLoadStackValue(callee); b.endSendVar();
                    }
                } else if (arg.value() instanceof StoreTemp result) {
                    b.beginSendCall(index);
                    b.emitLoadStackValue(callee);
                    if (result.value() instanceof Call inner && inner.target().kind() == Dispatch.METHOD) {
                        store(result, () -> {
                            b.beginIrCallArgument(index); b.emitLoadStackValue(callee);
                            call(inner, true);
                            b.endIrCallArgument();
                        });
                    } else expr(result);
                    b.endSendCall();
                } else {
                    b.beginSendValue(index); b.emitLoadStackValue(callee); expr(arg.value()); b.endSendValue();
                }
            }
        }
    }
}
