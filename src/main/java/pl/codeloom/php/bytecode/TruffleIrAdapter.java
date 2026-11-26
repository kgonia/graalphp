package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.bytecode.BytecodeConfig;
import com.oracle.truffle.api.bytecode.BytecodeLabel;
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
    public record TranslationCounts(int ir, int fallback) {}
    private static final Map<PhpLanguage,Map<String,TranslationCounts>> histories = new WeakHashMap<>();
    private static final Set<String> lastIr = Collections.newSetFromMap(new WeakHashMap<>());
    public static synchronized boolean wasTranslatedViaIr(String file) { return lastIr.contains(file); }
    public static synchronized TranslationCounts counts(PhpLanguage language,String file) {
        return histories.getOrDefault(language,Map.of()).getOrDefault(file,new TranslationCounts(0,0));
    }
    private static synchronized void record(PhpLanguage language,String file,boolean ir) {
        var counts = histories.computeIfAbsent(language,ignored -> new HashMap<>());
        TranslationCounts old = counts.getOrDefault(file,new TranslationCounts(0,0));
        counts.put(file,new TranslationCounts(old.ir() + (ir ? 1 : 0),old.fallback() + (ir ? 0 : 1)));
        if (ir) lastIr.add(file); else lastIr.remove(file);
    }
    public static void usedLegacy(PhpLanguage language,String file) { record(language,file,false); }

    private final PhpLanguage language;
    private final Source source;
    private final Set<PhpFunction> closures = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean eval;
    private final List<PhpFunction> functions = new ArrayList<>();
    private final List<ClassTemplate> classes = new ArrayList<>();
    private final Set<ClassTemplate> linkedClasses = new HashSet<>();

    private TruffleIrAdapter(PhpLanguage language, Source source) {
        this.language = language;
        this.source = source;
    }

    private static PhpType type(String name) {
        if (name == null) return null;
        int mask = switch (name) {
            case "string" -> PhpType.STRING;
            case "?string" -> PhpType.STRING | PhpType.NULL;
            case "int" -> PhpType.INT;
            case "void" -> PhpType.VOID;
            case "array" -> PhpType.ARRAY;
            case "callable" -> PhpType.CALLABLE;
            case "mixed" -> PhpType.MIXED;
            default -> throw new IllegalArgumentException(name);
        };
        return new PhpType(mask,new String[0],new String[0][],new String[0]);
    }

    public static PhpBytecodeRootNode translate(PhpLanguage language, Source source, IrUnit unit,
                                                Map<String, PhpFunction> declarations, List<ClassTemplate> classes,
                                                Set<ClassTemplate> linkedClasses, boolean eval) {
        if (unit.strictTypes()) language.declareStrict(unit.file());
        var adapter = new TruffleIrAdapter(language, source);
        adapter.eval = eval;
        adapter.functions.add(null);
        var methods = new HashMap<Integer, PhpFunction>();
        for (var cls : unit.classes()) {
            var declared = new HashMap<String, PhpFunction>();
            for (var member : classes.get(cls.id()).getMembers()) {
                if (member instanceof ClassTemplate.Method method) declared.put(method.function().getName(), method.function());
            }
            for (int id : cls.methods()) methods.put(id, declared.get(unit.functions().get(id).name()));
        }
        for (IrFunction f : unit.functions()) {
            if (f.fileBody()) continue;
            var fn = f.owner() == null ? declarations.get(f.name()) : methods.get(f.id());
            if (f.closure()) {
                var signature = f.params().stream().map(p -> new PhpFunction.Param(p.name(), p.byRef(), p.variadic(),
                        p.defaultValue(),type(p.type()),false,p.type())).toArray(PhpFunction.Param[]::new);
                fn = new PhpFunction(f.name(),signature,false,f.isStatic(),true,false,null);
                fn.setDeclaredAt(new SourceLocation(f.at().file(), f.at().line()));
                fn.setDeclaredReturn(type(f.returnType()));
                fn.setDeclaration(0,f.returnType(),null,f.endLine());
                fn.setClosureVariables(f.captures().stream().map(c -> f.locals().get(c.inner())).toArray(String[]::new), new Object[0]);
                adapter.closures.add(fn);
            }
            var signature = fn.getParams();
            fn.setBodyBeforePublication(() -> adapter.new Body(f, signature).build().getCallTarget());
            adapter.functions.add(fn);
        }
        adapter.classes.addAll(classes);
        adapter.linkedClasses.addAll(linkedClasses);
        var root = adapter.new Body(unit.functions().getFirst(), new PhpFunction.Param[0]).build();
        record(language,unit.file(),true);
        return root;
    }

    private record CacheKey(Class<?> type,Object site) {}

    private final class Body {
        final IrFunction f;
        final boolean compact;
        final PhpFunction.Param[] params;
        // The DSL may replay with source information. Site identity must survive replay.
        final Map<CacheKey, Object> constants = new HashMap<>();
        LocalAccessor[] variables, temporaries;
        LocalAccessor extra, scope;
        boolean loops;
        int[] uncounted;
        int namedExtras = -1;

        Body(IrFunction f, PhpFunction.Param[] params) {
            this.f = f;
            this.params = params;
            compact = f.body().size() >= IrUnit.LARGE_BODY_STATEMENTS && straightLine(f.body());
        }

        // Compact operations trade per-iteration speed for a smaller compilation graph.
        // Use them only for large acyclic bodies, whose sites execute at most once per call.
        private static boolean straightLine(List<Statement> code) {
            for (var statement : code) {
                if (statement instanceof Branch branch) {
                    if (!straightLine(branch.yes()) || !straightLine(branch.no())) return false;
                } else if (!(statement instanceof Region)) return false;
            }
            return true;
        }

        <T> T constant(Class<T> type,Object site,Supplier<T> make) {
            return type.cast(constants.computeIfAbsent(new CacheKey(type,site), ignored -> make.get()));
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
                    section, params, IntStream.range(0, params.length).toArray(),type(f.returnType()),
                    variables, new LocalAccessor[0], f.owner());
            boolean[] moved = new boolean[f.temps().size()];
            if (f.generator()) root.initializeGenerator(false,true);
            root.initializeVariadic(namedExtras);
            root.compactIr = compact;
            root.irTemps = f.temps().toArray(TempKind[]::new);
            root.initializeTemporaries(temporaries, moved, extra, uncounted);
            if (f.fileBody()) root.initializeFile(loops);
            else root.initializeNames(f.locals().toArray(String[]::new), scope);
            return root;
        }

        private final class Run {
            final PhpBytecodeRootNodeGen.Builder b;
            final BytecodeLocal[] locals = new BytecodeLocal[f.fileBody() ? 0 : f.locals().size()];
            final BytecodeLocal[] temps = new BytecodeLocal[f.temps().size()];
            final Map<Integer,BytecodeLocal> prepared = new HashMap<>();
            final List<Integer> borrowedOffsets = new ArrayList<>();

            Run(PhpBytecodeRootNodeGen.Builder b) { this.b = b; }

            BytecodeLocal activeCleanup, generator;

            void setCleanup(ReleaseTemps cleanup) {
                b.beginStoreLocal(activeCleanup); b.emitLoadConstant(indices(cleanup)); b.endStoreLocal();
            }

            void emit() {
                b.beginRoot();
                for (int i = 0; i < locals.length; i++) locals[i] = b.createLocal(f.locals().get(i), null);
                for (int i = 0; i < temps.length; i++) temps[i] = b.createLocal();
                if (source != null) b.beginSource(source);
                if (f.fileBody()) {
                    if (!f.locals().isEmpty()) b.emitAttachGlobals(constant(String[].class,f.locals(), () -> f.locals().toArray(String[]::new)));
                    for (PhpFunction fn : functions) {
                        if (fn != null && !closures.contains(fn) && fn.getClassName() == null) b.emitDeclareFunction(fn, true);
                    }
                    for (ClassTemplate cls : classes) {
                        if (linkedClasses.contains(cls)) b.emitEarlyClass(cls, false);
                    }
                } else {
                    scope = LocalAccessor.constantOf(b.createLocal());
                    var extras = b.createLocal();
                    extra = LocalAccessor.constantOf(extras);
                    boolean variadic = params.length > 0 && params[params.length-1].variadic();
                    BytecodeLocal named = null;
                    if (variadic) { named = b.createLocal(); namedExtras = named.getLocalOffset(); b.emitTakeNamedExtras(named); }
                    b.emitTakeExtraArguments(extras, params.length - (variadic ? 1 : 0));
                    for (int i = 0; i < params.length; i++) {
                        if (params[i].variadic()) { b.emitBindVariadic(locals[i],i); b.emitBindNamedExtras(locals[i],named,i); continue; }
                        b.beginIfThen(); b.emitArgumentGiven(i);
                        b.beginBindParameter(locals[i], i); b.emitArgument(i); b.endBindParameter();
                        b.endIfThen();
                    }
                    for (int i = 0; i < params.length; i++) {
                        if (params[i].variadic()) continue;
                        beginLine(f.params().get(i).at());
                        Expr fallback = f.params().get(i).defaultValue();
                        if (fallback == null) b.emitVerifyIrParameter(locals[i], i);
                        else {
                            b.beginIfThenElse(); b.emitArgumentGiven(i);
                            b.emitVerifyIrParameter(locals[i],i);
                            int id = i;
                            write(id, () -> expr(fallback));
                            b.endIfThenElse();
                        }
                        endLine();
                    }
                }
                for (int i = 0; i < f.captures().size(); i++) {
                    var cap = f.captures().get(i);
                    b.emitBindCapture(locals[cap.inner()], i, cap.byRef());
                }
                if (f.generator()) { generator = b.createLocal(); b.emitGeneratorStart(generator); }
                // One cold landing pad suffices when every exceptional edge leaves the function.
                if (compact) {
                    activeCleanup = b.createLocal();
                    setCleanup(new ReleaseTemps(List.of()));
                    b.beginTryCatch(); block(f.body());
                    b.beginUnwindRegion(); b.emitLoadLocal(activeCleanup); b.emitLoadException(); b.endUnwindRegion();
                    b.endTryCatch();
                } else statements(f.body());
                b.beginReturn();
                b.emitLoadConstant(f.fileBody() && !eval ? (Object) 1L : PhpNull.NULL);
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

            record Targets(BytecodeLabel stop, BytecodeLabel again) {}
            final List<Targets> activeLoops = new ArrayList<>();
            final Map<String,Runnable> gotos = new HashMap<>();

            void statements(List<Statement> statements) {
                var marks = statements.stream().filter(Mark.class::isInstance).map(Mark.class::cast).toList();
                if (marks.isEmpty()) { sequence(statements); return; }
                loops = true;
                b.beginBlock();
                var done = b.createLabel();
                var pc = b.createLocal();
                b.beginStoreLocal(pc); literal(-1L); b.endStoreLocal();
                b.beginWhile(); literal(true); b.beginBlock();
                var again = b.createLabel();
                var labels = new HashMap<String,BytecodeLabel>();
                var emitted = new HashSet<String>();
                for (int i = 0; i < marks.size(); i++) {
                    long id = i;
                    String name = marks.get(i).name();
                    var label = b.createLabel();
                    labels.put(name,label);
                    b.beginIfThen(); b.beginIdentical(); b.emitLoadLocal(pc); literal(id); b.endIdentical();
                    b.emitBranch(label); b.endIfThen();
                    gotos.put(name, () -> {
                        if (emitted.contains(name)) {
                            b.beginStoreLocal(pc); literal(id); b.endStoreLocal(); b.emitBranch(again);
                        } else b.emitBranch(label);
                    });
                }
                for (Statement stmt : statements) {
                    if (stmt instanceof Mark m) { b.emitLabel(labels.get(m.name())); emitted.add(m.name()); }
                    else sequence(List.of(stmt));
                }
                b.emitBranch(done); b.emitLabel(again); b.endBlock(); b.endWhile(); b.emitLabel(done); b.endBlock();
                marks.forEach(m -> gotos.remove(m.name()));
            }

            void sequence(List<Statement> statements) {
                for (Statement statement : statements) {
                    if (statement instanceof Branch branch) {
                        b.beginIfThenElse(); test(branch.test());
                        block(branch.yes()); block(branch.no()); b.endIfThenElse(); continue;
                    }
                    if (statement instanceof Loop loop) {
                        loops = true;
                        b.beginBlock();
                        var stop = b.createLabel();
                        statements(loop.init()); b.beginWhile(); test(loop.test());
                        loopBody(loop.body(),stop,loop.step());
                        b.endWhile(); b.emitLabel(stop); b.endBlock(); continue;
                    }
                    if (statement instanceof Each each) { each(each); continue; }
                    if (statement instanceof LoopExit jump) {
                        var targets = activeLoops.get(activeLoops.size() - jump.depth());
                        b.emitBranch(jump.again() ? targets.again() : targets.stop()); continue;
                    }
                    if (statement instanceof Jump jump) { gotos.get(jump.name()).run(); continue; }
                    if (statement instanceof Finally cleanup) { finallyBody(cleanup); continue; }
                    if (statement instanceof ClassDeclaration decl) {
                        if (!linkedClasses.contains(classes.get(decl.id()))) {
                            beginLine(decl.at());
                            b.emitDeclareClass(classes.get(decl.id()));
                            endLine();
                        }
                        continue;
                    }
                    if (statement instanceof TryCatch tc) { tryCatch(tc); continue; }
                    Region region = (Region) statement;
                    beginLine(region.at());
                    if (activeCleanup != null) setCleanup(region.exceptional());
                    boolean guarded = activeCleanup == null && !region.exceptional().slots().isEmpty();
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

            void block(List<Statement> code) { b.beginBlock(); statements(code); b.endBlock(); }

            void truth(Expr value) {
                if (compact) { b.beginCompactTruth(); expr(value); b.endCompactTruth(); }
                else { b.beginToBool(); expr(value); b.endToBool(); }
            }

            void test(Test test) {
                beginLine(test.at());
                if (activeCleanup != null) { b.beginBlock(); setCleanup(test.cleanup()); }
                if (test.cleanup().slots().isEmpty()) {
                    truth(test.value());
                } else {
                    b.beginBlock();
                    var condition = b.createLocal();
                    if (activeCleanup == null) { b.beginTryCatch(); b.beginBlock(); }
                    b.beginStoreLocal(condition); truth(test.value()); b.endStoreLocal();
                    release(test.cleanup());
                    if (activeCleanup == null) {
                        b.endBlock(); b.beginUnwindTemps(indices(test.cleanup())); b.emitLoadException(); b.endUnwindTemps();
                        b.endTryCatch();
                    }
                    b.emitLoadLocal(condition); b.endBlock();
                }
                if (activeCleanup != null) b.endBlock();
                endLine();
            }
            void loopBody(List<Statement> body, BytecodeLabel stop,List<Statement> step) {
                b.beginBlock();
                var again = b.createLabel();
                activeLoops.add(new Targets(stop,again)); statements(body); activeLoops.removeLast();
                b.emitLabel(again); statements(step); b.endBlock();
            }
            void each(Each e) {
                loops = true;
                b.beginBlock();
                var state = b.createLocal();
                var pos = b.createLocal();
                var stop = b.createLabel();
                var val = f.fileBody() ? b.createLocal() : locals[e.value()];
                var key = e.key() < 0 ? val : f.fileBody() ? b.createLocal() : locals[e.key()];
                b.beginTryCatch(); b.beginBlock();
                b.beginForeachStart(state,pos); expr(e.source().value()); b.endForeachStart();
                b.endBlock();
                b.beginUnwindTemps(indices(e.source().cleanup())); b.emitLoadException(); b.endUnwindTemps();
                b.endTryCatch();
                b.beginTryFinally(() -> {
                    b.beginBlock(); b.emitForeachEnd(state,pos);
                    if (f.fileBody()) { b.emitUnsetVar(val); if (e.key() >= 0) b.emitUnsetVar(key); }
                    b.endBlock();
                });
                b.beginBlock(); release(e.source().cleanup());
                b.beginWhile();
                b.beginForeachNext(pos,val,key,e.key() >= 0); b.emitLoadLocal(state); b.endForeachNext();
                b.beginBlock();
                if (f.fileBody()) {
                    write(e.value(), () -> b.emitReadVar(val)); b.emitUnsetVar(val);
                    if (e.key() >= 0) { write(e.key(), () -> b.emitReadVar(key)); b.emitUnsetVar(key); }
                }
                loopBody(e.body(),stop,List.of());
                b.endBlock(); b.endWhile(); b.endBlock(); b.endTryFinally(); b.emitLabel(stop); b.endBlock();
            }
            record ReturnTarget(int slot, BytecodeLocal value, BytecodeLabel deliver) {}
            ReturnTarget returnTarget;

            void finallyBody(Finally region) {
                ReturnTarget outer = returnTarget;
                int slot = region.savedReturn();
                b.beginBlock();
                var done = b.createLabel();
                b.beginTryCatchOtherwise(() -> b.emitReleaseTemp(temps[slot],slot));
                b.beginBlock();
                var deliver = b.createLabel();
                var value = b.createLocal();
                borrowedOffsets.add(value.getLocalOffset());
                var target = new ReturnTarget(slot,value,deliver);
                Runnable cleanup = () -> {
                    ReturnTarget previous = returnTarget;
                    returnTarget = outer;
                    block(region.cleanup());
                    returnTarget = previous;
                };
                returnTarget = target;
                b.beginTryCatchOtherwise(cleanup);
                block(region.body());
                b.beginBlock();
                var pending = b.createLocal();
                borrowedOffsets.add(pending.getLocalOffset());
                b.beginStoreLocal(pending); b.emitLoadException(); b.endStoreLocal();
                b.beginIfThen(); b.beginFinallyRuns(); b.emitLoadLocal(pending); b.endFinallyRuns();
                b.beginTryCatch(); cleanup.run();
                b.beginThrowFromFinally(); b.emitLoadException(); b.emitLoadLocal(pending); b.endThrowFromFinally();
                b.endTryCatch(); b.endIfThen();
                b.beginRethrow(); b.emitLoadLocal(pending); b.endRethrow();
                b.endBlock(); b.endTryCatchOtherwise();
                returnTarget = outer;
                b.emitBranch(done);
                b.emitLabel(deliver);
                returning(() -> b.emitLoadLocal(value),new ReleaseTemps(List.of(new ReleaseTemp(slot))));
                b.endBlock();
                b.beginUnwindTemps(indices(new ReleaseTemps(List.of(new ReleaseTemp(slot))))); b.emitLoadException(); b.endUnwindTemps();
                b.endTryCatchOtherwise(); b.emitLabel(done); b.endBlock();
            }

            void release(ReleaseTemps release) {
                for (ReleaseTemp t : release.slots()) b.emitReleaseTemp(temps[t.id()], t.id());
            }

            Temporaries indices(ReleaseTemps release) {
                return constant(Temporaries.class,release, () -> new Temporaries(release.slots().stream().mapToInt(ReleaseTemp::id).toArray()));
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
                b.beginCatchMatches(constant(String[].class,tc, () -> new String[]{Str.lower(tc.type())}));
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

            void returning(Runnable value, ReleaseTemps cleanup) {
                if (returnTarget != null) {
                    // Keep the pending zval addressable while a finally may replace it or throw.
                    var target = returnTarget;
                    b.beginStoreLocal(target.value());
                    b.beginIrStore(temps[target.slot()],target.slot()); value.run(); b.endIrStore(false);
                    b.endStoreLocal();
                    release(cleanup);
                    b.emitBranch(target.deliver());
                } else {
                    b.beginReturn();
                    if (!cleanup.slots().isEmpty()) b.beginReleaseAfter();
                    b.beginOwn(); value.run(); b.endOwn(true);
                    if (!cleanup.slots().isEmpty()) b.endReleaseAfter(indices(cleanup),PhpBytecodeRootNode.ReleaseAfter.RETURNING);
                    b.endReturn();
                }
            }

            void action(Action action, ReleaseTemps normal) {
                switch (action) {
                    case Echo(var value) -> { b.beginEcho(); expr(value); b.endEcho(); }
                    case Return(var value) -> returning(() -> expr(value),normal);
                    case Raise(var value) -> { b.beginThrow(); expr(value); b.endThrow(); }
                    case Write(var id, var value) -> write(id, () -> expr(value));
                    case Bind(var id,var source) -> {
                        if (f.fileBody()) {
                            b.beginBindVariable(); place(source); literal(f.locals().get(id)); b.endBindVariable();
                        } else { b.beginBindVar(locals[id]); place(source); b.endBindVar(); }
                    }
                    case WriteStatic(var p,var value) -> {
                        b.beginWriteRef(); expr(value); place(p); b.endWriteRef();
                    }
                    case Global(var id) -> { if (!f.fileBody()) b.emitGlobal(locals[id],f.locals().get(id)); }
                    case Unset(var root,var key) -> {
                        if (root instanceof Property p) {
                            b.beginUnsetPropNested(prop(p.receiver(),p.name())); expr(p.receiver()); expr(key); b.endUnsetPropNested(); break;
                        }
                        int id = ((Read)root).local();
                        if (key == null) {
                            if (f.fileBody()) { b.beginUnsetVariable(); literal(f.locals().get(id)); b.endUnsetVariable(); }
                            else b.emitUnsetVar(locals[id]);
                        } else if (f.fileBody()) {
                            b.beginUnsetRefNested(); b.beginVariableRef(VariableVariableNode.UNSET); literal(f.locals().get(id)); b.endVariableRef();
                            expr(key); b.endUnsetRefNested();
                        } else { b.beginUnsetNested(locals[id]); expr(key); b.endUnsetNested(); }
                    }
                    case WriteNamedProperty(var recv,var name,var value) -> {
                        b.beginWritePropNamed(prop(recv,null)); expr(value); b.beginPropName(); expr(name); b.endPropName(); expr(recv); b.endWritePropNamed();
                    }
                    case CoalesceDim(var local,var key,var value) -> {
                        b.beginBlock();
                        var k = b.createLocal();
                        borrowedOffsets.add(k.getLocalOffset());
                        b.beginStoreLocal(k); expr(key); b.endStoreLocal();
                        b.beginIfThen(); b.beginNot(); b.beginIsSetValue();
                        if (compact) b.beginCompactElement(true); else b.beginElementIfSet();
                        expr(new QuietRead(local)); b.emitLoadLocal(k);
                        if (compact) b.endCompactElement(); else b.endElementIfSet();
                        b.endIsSetValue(); b.endNot();
                        if (f.fileBody()) {
                            b.beginWriteRefNested(false);
                            b.beginFetchForWrite(); expr(value); fileVariable(local,true); b.endFetchForWrite();
                            b.emitLoadLocal(k); b.endWriteRefNested();
                        } else {
                            b.beginWriteNested(locals[local],false); expr(value); b.emitLoadLocal(k); b.endWriteNested();
                        }
                        b.endIfThen(); b.endBlock();
                    }
                    case WriteDim dim -> {
                        var keys = dim.keys();
                        boolean append = keys.getLast() == null;
                        b.beginBlock();
                        var early = new BytecodeLocal[keys.size()];
                        for (int i = 0; i < keys.size(); i++) if (dim.early(i)) {
                            early[i] = b.createLocal();
                            borrowedOffsets.add(early[i].getLocalOffset());
                            b.beginStoreLocal(early[i]); expr(keys.get(i)); b.endStoreLocal();
                        }
                        if (dim.root() instanceof Property p) {
                            b.beginWritePropNested(prop(p.receiver(),p.name()),append); expr(dim.value()); expr(p.receiver());
                        } else if (dim.root() instanceof StaticProperty p) {
                            b.beginWriteRefNested(append); b.beginFetchForWrite(); expr(dim.value()); place(p); b.endFetchForWrite();
                        } else {
                            int root = ((Read)dim.root()).local();
                            if (f.fileBody()) {
                                b.beginWriteRefNested(append);
                                b.beginFetchForWrite(); expr(dim.value()); fileVariable(root,true); b.endFetchForWrite();
                            } else { b.beginWriteNested(locals[root],append); expr(dim.value()); }
                        }
                        for (int i = 0; i < keys.size() - (append ? 1 : 0); i++) {
                            if (keys.get(i) == null) b.emitLoadConstant(PhpBytecodeRootNode.APPEND);
                            else if (early[i] != null) b.emitLoadLocal(early[i]); else expr(keys.get(i));
                        }
                        if (dim.root() instanceof Property) b.endWritePropNested();
                        else if (f.fileBody() || dim.root() instanceof StaticProperty) b.endWriteRefNested(); else b.endWriteNested();
                        b.endBlock();
                    }
                    case WriteProperty(var recv, var name, var value) -> {
                        b.beginWriteProp(prop(recv, name)); expr(value); expr(recv); b.endWriteProp();
                    }
                    case Evaluate(var value) -> expr(value);
                }
            }

            PropSite prop(Expr receiver, String name) {
                var key = new Property(receiver, name);
                return constant(PropSite.class,key, () -> new PropSite(name == null ? null : Str.sourceKey(name), f.owner() == null ? null : Str.lower(f.owner()),
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
                b.emitLoadConstant(value instanceof String s ? constant(com.oracle.truffle.api.strings.TruffleString.class,s, () -> Str.of(s))
                        : value instanceof byte[] bytes ? constant(com.oracle.truffle.api.strings.TruffleString.class,bytes, () -> Str.of(bytes)) : value == null ? PhpNull.NULL : value);
            }

            void expr(Expr expr) {
                switch (expr) {
                    case Yield(var key,var value,var delegate) -> {
                        b.beginResumed(generator);
                        if (delegate) {
                            b.beginYieldFrom(generator); expr(value); b.endYieldFrom();
                        } else {
                            b.beginYieldValue(generator);
                            if (key == null) b.emitLoadConstant(PhpGenerator.SUSPENDED); else expr(key);
                            expr(value); b.endYieldValue();
                        }
                        b.endResumed();
                    }
                    case Literal(var value) -> literal(value);
                    case Read(var id) -> { if (f.fileBody()) fileVariable(id, false); else b.emitReadVar(locals[id]); }
                    case QuietRead(var id) -> {
                        if (!f.fileBody()) b.emitReadVarIfSet(locals[id]);
                        else { b.beginVariableValue(VariableVariableNode.IS); literal(f.locals().get(id)); b.endVariableValue(); }
                    }
                    case Dim(var base,var key,var quiet) -> {
                        if (compact) { b.beginCompactElement(quiet); expr(base); expr(key); b.endCompactElement(); break; }
                        if (quiet) b.beginElementIfSet(); else b.beginReadElement();
                        expr(base); expr(key);
                        if (quiet) b.endElementIfSet(); else b.endReadElement();
                    }
                    case Prepared(var operands,var result) -> {
                        prepared(operands,result,false);
                    }
                    case LoadTemp(var id) -> b.emitLoadLocal(prepared.get(id));
                    case ArrayValue a -> literalArray(a,-1);
                    case CheckedReturn(var value,var missing) -> {
                        if (missing) b.emitNoneReturned();
                        else { b.beginCheckReturn(); expr(value); b.endCheckReturn(); }
                    }
                    case PassedArguments ignored -> b.emitFuncGetArgs();
                    case Cast(var value,var string) -> {
                        if (string) { b.beginCastString(); expr(value); b.endCastString(); }
                        else { b.beginCastLong(); expr(value); b.endCastLong(); }
                    }
                    case IncrementPlace(var p,var up,var prefix) -> {
                        b.beginIncDecRef(up,prefix); place(p); b.endIncDecRef();
                    }
                    case IsSet(var e) -> { b.beginIsSetValue(); expr(e); b.endIsSetValue(); }
                    case Not(var e) -> { b.beginNot(); truth(e); b.endNot(); }
                    case Choose(var cond,var yes,var no) -> {
                        b.beginConditional(); truth(cond); expr(yes); expr(no); b.endConditional();
                    }
                    case Update(var id,var value) -> {
                        b.beginBlock(); write(id, () -> expr(value)); expr(new Read(id)); b.endBlock();
                    }
                    case Increment(var id,var up,var prefix) -> {
                        if (f.fileBody()) { b.beginIncDecRef(up,prefix); fileVariable(id,true); b.endIncDecRef(); }
                        else b.emitIncDecVar(locals[id],up,prefix);
                    }
                    case Closure closure -> {
                        var refs = constant(boolean[].class,closure, () -> {
                            boolean[] flags = new boolean[closure.captures().size()];
                            for (int i = 0; i < flags.length; i++) flags[i] = closure.captures().get(i).byRef();
                            return flags;
                        });
                        var function = functions.get(closure.id());
                        b.beginMakeClosure(function,refs,function.isStatic());
                        if (f.owner() == null) literal(null);
                        else b.emitResolveClass(constant(ClassSite.class,f.owner(), () -> new ClassSite(f.owner(), language.classId(Str.lower(f.owner())))));
                        for (var cap : closure.captures()) {
                            if (f.fileBody()) fileVariable(cap.outer(),cap.byRef());
                            else if (cap.byRef()) b.emitRefVar(locals[cap.outer()]); else b.emitReadVar(locals[cap.outer()]);
                        }
                        b.endMakeClosure();
                    }
                    case Forward forward -> {
                        b.beginCallUserFuncArray(false); expr(forward.callable());
                        if (forward.offset() == null) expr(forward.args());
                        else {
                            CallInfo info = constant(CallInfo.class,forward, () -> new CallInfo("array_slice",language.functionId("array_slice"),-1,-1,false,false));
                            b.beginCall(info); b.emitResolveFunction(info); expr(forward.args()); expr(forward.offset());
                            if (forward.length() != null) expr(forward.length());
                            b.endCall();
                        }
                        b.endCallUserFuncArray();
                    }
                    case Eval e -> { b.beginEval(constant(IncludeSite.class,e, () -> new IncludeSite("eval"))); expr(e.code()); b.endEval(); }
                    case DynamicAllocate a -> { b.beginInstantiate(); b.beginClassOf(); expr(a.name()); b.endClassOf(); b.endInstantiate(); }
                    case This ignored -> b.emitReadThis();
                    case QuietProperty(var recv,var name) -> { b.beginReadPropIfSet(prop(recv,name)); expr(recv); b.endReadPropIfSet(); }
                    case StaticProperty p -> staticProperty(p,false);
                    case Property(var recv, var name) -> { b.beginReadProp(prop(recv, name)); expr(recv); b.endReadProp(); }
                    case Binary binary -> {
                        var consumed = binary.consumed();
                        if (!consumed.slots().isEmpty()) b.beginReleaseAfter();
                        b.beginBlock();
                        BytecodeLocal right = null;
                        if (binary.deferredLeft() && binary.op() != Operator.OR && binary.op() != Operator.AND && !binary.preservesValue()) {
                            right = b.createLocal();
                            borrowedOffsets.add(right.getLocalOffset());
                            b.beginStoreLocal(right); expr(binary.right()); b.endStoreLocal();
                        }
                        switch (binary.op()) {
                            case ADD -> b.beginAdd();
                            case MOD -> b.beginMod();
                            case MUL -> b.beginMul();
                            case CONCAT -> { if (compact) b.beginCompactConcat(); else b.beginConcat(); }
                            case IDENTICAL -> { if (compact) b.beginCompactIdentity(true); else b.beginIdentical(); }
                            case NOT_IDENTICAL -> { if (compact) b.beginCompactIdentity(false); else b.beginNotIdentical(); }
                            case LT -> b.beginLess();
                            case LE -> b.beginLessEqual();
                            case GT -> b.beginGreater();
                            case GE -> b.beginGreaterEqual();
                            case EQ -> b.beginEqual();
                            case NE -> b.beginNotEqual();
                            case AND -> b.beginAnd();
                            case OR -> b.beginOr();
                            case COALESCE -> b.beginCoalesce();
                            case ELVIS -> b.beginElvis();
                        }
                        expr(binary.left());
                        if (right == null) expr(binary.right()); else b.emitLoadLocal(right);
                        switch (binary.op()) {
                            case ADD -> b.endAdd();
                            case MOD -> b.endMod();
                            case MUL -> b.endMul();
                            case CONCAT -> { if (compact) b.endCompactConcat(); else b.endConcat(); }
                            case IDENTICAL -> { if (compact) b.endCompactIdentity(); else b.endIdentical(); }
                            case NOT_IDENTICAL -> { if (compact) b.endCompactIdentity(); else b.endNotIdentical(); }
                            case LT -> b.endLess();
                            case LE -> b.endLessEqual();
                            case GT -> b.endGreater();
                            case GE -> b.endGreaterEqual();
                            case EQ -> b.endEqual();
                            case NE -> b.endNotEqual();
                            case AND -> b.endAnd();
                            case OR -> b.endOr();
                            case COALESCE -> b.endCoalesce();
                            case ELVIS -> b.endElvis();
                        }
                        b.endBlock();
                        if (!consumed.slots().isEmpty()) b.endReleaseAfter(indices(consumed), binary.preservesValue() ? PhpBytecodeRootNode.ReleaseAfter.PASSES : PhpBytecodeRootNode.ReleaseAfter.CONSUMES);
                    }
                    case StoreTemp temp -> store(temp, () -> expr(temp.value()));
                    case Allocate a -> {
                        b.beginInstantiate();
                        b.emitResolveClass(constant(ClassSite.class,a, () -> new ClassSite(a.className(), language.classId(Str.lower(a.className())))));
                        b.endInstantiate();
                    }
                    case Call call -> call(call, false);
                }
            }

            void literalArray(ArrayValue a, int slot) {
                var site = constant(LiteralSite.class,a, () -> new LiteralSite(a.items().size(),false));
                b.beginLiteralEnd(site);
                for (var item : a.items().reversed()) {
                    if (item.value() instanceof StoreTemp) b.beginReleaseAfter();
                    if (item.key() == null) b.beginLiteralAppend(); else b.beginLiteralPut();
                }
                if (slot >= 0) b.beginIrStore(temps[slot],slot);
                b.emitLiteralStart(site);
                if (slot >= 0) b.endIrStore(false);
                for (var item : a.items()) {
                    if (item.key() != null) expr(item.key());
                    expr(item.value());
                    if (item.key() == null) b.endLiteralAppend(); else b.endLiteralPut();
                    if (item.value() instanceof StoreTemp t) b.endReleaseAfter(indices(new ReleaseTemps(List.of(new ReleaseTemp(t.id())))), PhpBytecodeRootNode.ReleaseAfter.CONSUMES);
                }
                b.endLiteralEnd();
            }

            void store(StoreTemp temp, Runnable evaluate) {
                Expr value = temp.value();
                if (value instanceof ArrayValue array) { literalArray(array,temp.id()); return; }
                Call call = value instanceof Call c && !c.cleanup().slots().isEmpty() ? c : null;
                ReleaseTemps selected = value instanceof Binary binary && binary.preservesValue() ? binary.consumed() : null;
                if (call != null || selected != null) b.beginReleaseAfter();
                b.beginIrStore(temps[temp.id()], temp.id());
                evaluate.run();
                b.endIrStore(value instanceof Call || value instanceof Allocate || value instanceof DynamicAllocate || value instanceof Forward || value instanceof Eval || value instanceof Closure);
                if (call != null || selected != null) b.endReleaseAfter(indices(call != null ? call.cleanup() : selected), PhpBytecodeRootNode.ReleaseAfter.CONSUMES);
            }

            void call(Call call, boolean keepRef) {
                var t = call.target();
                beginLine(call.at());
                if (t.kind() == Dispatch.FUNCTION && call.args().size() == 1) {
                    int intrinsic = switch (Str.lower(t.name())) {
                        case "is_array" -> PhpBytecodeRootNode.TypeTest.ARRAY;
                        case "is_int" -> PhpBytecodeRootNode.TypeTest.INT;
                        case "is_string" -> PhpBytecodeRootNode.TypeTest.STRING;
                        case "is_object" -> PhpBytecodeRootNode.TypeTest.OBJECT;
                        default -> -1;
                    };
                    if (intrinsic >= 0) {
                        b.beginTypeTest(intrinsic); expr(call.args().getFirst().value()); b.endTypeTest(); endLine(); return;
                    }
                }
                if (t.kind() == Dispatch.DYNAMIC) {
                    b.beginCallValue(keepRef);
                    b.beginBindStackValue(); b.beginResolveCallable(); expr(t.receiver()); b.endResolveCallable();
                    var target = b.endBindStackValue();
                    b.beginBindStackValue(); b.beginTargetFunction(); b.emitLoadStackValue(target); b.endTargetFunction();
                } else if (t.kind() == Dispatch.STATIC) {
                    String owner = (String)((Literal)t.receiver()).value();
                    var site = constant(MethodSite.class,call, () -> new MethodSite(t.name(),false));
                    b.beginCallStatic(site,keepRef);
                    b.beginBindStackValue();
                    b.emitResolveClass(constant(ClassSite.class,owner, () -> new ClassSite(owner,language.classId(Str.lower(owner)))));
                    var cls = b.endBindStackValue();
                    b.beginBindStackValue();
                    b.beginResolveStaticMethod(site); b.emitLoadStackValue(cls); b.endResolveStaticMethod();
                } else if (t.kind() == Dispatch.FUNCTION) {
                    CallInfo info = constant(CallInfo.class,call, () -> new CallInfo(t.name(), language.functionId(Str.lower(t.name())), -1, -1, false, call.discardReturn()));
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
                        site = constant(MethodSite.class,call, () -> new MethodSite(t.name(), false,
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
                        var slots = constant(Temporaries.class,call.args(), () -> new Temporaries(call.args().stream().mapToInt(Argument::slot).toArray()));
                        b.beginTransferSends(slots);
                        b.emitLoadStackValue(callee);
                    }
                    if (!(arg.value() instanceof StoreTemp)) b.beginIrStore(temps[arg.slot()], arg.slot());
                    send(arg, index++, callee);
                    if (!(arg.value() instanceof StoreTemp)) b.endIrStore(false);
                    if (last) b.endTransferSends();
                }
                switch (t.kind()) {
                    case DYNAMIC -> b.endCallValue();
                    case FUNCTION -> b.endCall();
                    case STATIC -> b.endCallStatic();
                    case METHOD -> b.endCallMethod();
                    case CONSTRUCTOR -> {
                        b.endConstruct();
                        b.endFinishConstruction();
                    }
                }
                endLine();
            }

            void prepared(List<StoreTemp> operands,Expr result,boolean reference) {
                        b.beginBlock();
                        for (var tmp : operands) {
                            var local = b.createLocal();
                            prepared.put(tmp.id(),local); borrowedOffsets.add(local.getLocalOffset());
                            b.beginStoreLocal(local); expr(tmp); b.endStoreLocal();
                        }
                        if (reference) place(result); else expr(result); b.endBlock();
            }

            void staticProperty(StaticProperty p, boolean reference) {
                var site = constant(StaticPropSite.class,p, () -> new StaticPropSite(Str.sourceKey(p.name()),
                        f.owner() == null ? null : Str.lower(f.owner()),false));
                if (reference) b.beginStaticPropRef(site); else b.beginReadStaticProp(site);
                b.emitResolveClass(constant(ClassSite.class,p.owner(), () -> new ClassSite(p.owner(),language.classId(Str.lower(p.owner())))));
                if (reference) b.endStaticPropRef(); else b.endReadStaticProp();
            }

            void place(Expr p) {
                if (p instanceof StaticProperty prop) { staticProperty(prop,true); return; }
                if (p instanceof Prepared setup) { prepared(setup.operands(),setup.result(),true); return; }
                if (p instanceof StoreTemp t) { place(t.value()); return; }
                if (p instanceof Property prop) {
                    b.beginRefProp(prop(prop.receiver(),prop.name())); expr(prop.receiver()); b.endRefProp();
                } else if (p instanceof Read r) {
                    if (f.fileBody()) fileVariable(r.local(),true); else b.emitRefVar(locals[r.local()]);
                } else {
                    Dim d = (Dim)p;
                    b.beginRefRefNested(false); place(d.base()); expr(d.key()); b.endRefRefNested();
                }
            }

            void send(Argument arg, int index, StackValue callee) {
                if (arg.bindVariable() && !(arg.value() instanceof Read)) {
                    b.beginConditional(); b.beginArgByRef(index); b.emitLoadStackValue(callee); b.endArgByRef();
                    place(arg.value()); expr(arg.value()); b.endConditional();
                } else if (arg.bindVariable()) {
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
