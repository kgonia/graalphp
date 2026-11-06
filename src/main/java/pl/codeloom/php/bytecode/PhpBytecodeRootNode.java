package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.ConstantOperand;
import com.oracle.truffle.api.bytecode.ContinuationRootNode;
import com.oracle.truffle.api.bytecode.EpilogExceptional;
import com.oracle.truffle.api.bytecode.EpilogReturn;
import com.oracle.truffle.api.bytecode.ForceQuickening;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.bytecode.ShortCircuitOperation;
import com.oracle.truffle.api.bytecode.Variadic;
import com.oracle.truffle.api.bytecode.Yield;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.strings.InternalByteArray;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.SourceFiles;
import pl.codeloom.php.builtins.Args;
import pl.codeloom.php.builtins.ArrayFunctions;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.builtins.StringFunctions;
import pl.codeloom.php.nodes.PhpTypes;
import pl.codeloom.php.nodes.LargeCode;
import pl.codeloom.php.nodes.ToStrNode;
import pl.codeloom.php.nodes.SubclassTestNode;
import pl.codeloom.php.nodes.call.ArgumentsNode;
import pl.codeloom.php.nodes.call.CallableCallNode;
import pl.codeloom.php.nodes.call.CallableConvertNodes;
import pl.codeloom.php.nodes.call.CallNode;
import pl.codeloom.php.nodes.call.CallableDispatchNode;
import pl.codeloom.php.nodes.call.FrameArgNode;
import pl.codeloom.php.nodes.call.FunctionRootNode;
import pl.codeloom.php.nodes.call.InstantiateNode;
import pl.codeloom.php.nodes.call.MethodCallNode;
import pl.codeloom.php.nodes.call.NewNode;
import pl.codeloom.php.nodes.call.StaticCallNode;
import pl.codeloom.php.nodes.expr.ArrayLiteralNode;
import pl.codeloom.php.nodes.expr.AssignNodes;
import pl.codeloom.php.nodes.expr.Comparisons;
import pl.codeloom.php.nodes.expr.ConstantNodes;
import pl.codeloom.php.nodes.expr.IncludeNode;
import pl.codeloom.php.nodes.expr.InterpolationNode;
import pl.codeloom.php.nodes.expr.MatchNode;
import pl.codeloom.php.nodes.expr.TestNodes;
import pl.codeloom.php.nodes.expr.UnaryNodes;
import pl.codeloom.php.nodes.intrinsics.BuiltinNodes;
import pl.codeloom.php.nodes.stmt.BasicStmts;
import pl.codeloom.php.nodes.stmt.DeclareNodes;
import pl.codeloom.php.nodes.stmt.ForeachNode;
import pl.codeloom.php.nodes.stmt.GeneratorRefIteration;
import pl.codeloom.php.nodes.stmt.TryNode;
import pl.codeloom.php.nodes.vars.ClassRefNode;
import pl.codeloom.php.nodes.vars.DimNode;
import pl.codeloom.php.nodes.vars.ElementNodes;
import pl.codeloom.php.nodes.vars.ExtractNode;
import pl.codeloom.php.nodes.vars.GlobalRefNode;
import pl.codeloom.php.nodes.vars.GlobalsDimNode;
import pl.codeloom.php.nodes.vars.IncludeScope;
import pl.codeloom.php.nodes.vars.ListAssignNode;
import pl.codeloom.php.nodes.vars.ObjectVarsNode;
import pl.codeloom.php.nodes.vars.Places;
import pl.codeloom.php.nodes.vars.PropNode;
import pl.codeloom.php.nodes.vars.RefWrites;
import pl.codeloom.php.nodes.vars.StaticPropNode;
import pl.codeloom.php.nodes.vars.ThisNode;
import pl.codeloom.php.nodes.vars.VariableVariableNode;
import pl.codeloom.php.nodes.vars.Variables;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.ClassKey;
import pl.codeloom.php.runtime.ClassTemplate;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.Operators;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpException;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpGenerator;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.PhpThrowable;
import pl.codeloom.php.runtime.PhpType;
import pl.codeloom.php.runtime.RefIteration;
import pl.codeloom.php.runtime.Refcount;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * A PHP function body as Truffle Bytecode DSL bytecode ({@link BytecodeTranslator} builds it), beside the tree's
 * {@link FunctionRootNode}: the same calling convention ({@link PhpFunction}), so either calls the other.
 * Operators, truthiness and element reads are the tree's own nodes ({@link OperationProxy}, through
 * {@link Proxies}), comparisons {@link Comparisons}' (one class per operator, over the tree's rules). PHP variables
 * are bytecode locals, read and written by this class's operations through {@link LocalAccessor}s by the tree's
 * rules ({@link Variables}); they come first, so the epilogs release them by offset. Control flow is the builder's
 * (blocks, loops, branches to labels, Return).
 *
 * <p>Booleans are not unboxed: a short circuit's result (`&&`) reached a consumer unboxed on one path and boxed on
 * the other, which deoptimized compiled code at every change of path; Boolean values are shared instances anyway.
 *
 * <p>Tail-call handlers: on native image the handlers jump to one another instead of returning to a dispatch loop,
 * which makes the interpreter 1.6 times as fast there; on the JVM they change nothing.
 */
@GenerateBytecode(languageClass = PhpLanguage.class, enableUncachedInterpreter = true, enableYield = true,
        enableBlockScoping = false, boxingEliminationTypes = {long.class, double.class},
        defaultUncachedThreshold = "UNCACHED_THRESHOLD", enableTailCallHandlers = true)
@TypeSystemReference(PhpTypes.class)
@OperationProxy(value = Proxies.Add.class, name = "Add")
@OperationProxy(value = Proxies.Sub.class, name = "Sub")
@OperationProxy(value = Proxies.Mul.class, name = "Mul")
@OperationProxy(value = Proxies.Div.class, name = "Div")
@OperationProxy(value = Proxies.Mod.class, name = "Mod")
@OperationProxy(value = Proxies.Pow.class, name = "Pow")
@OperationProxy(value = Proxies.BitAnd.class, name = "BitAnd")
@OperationProxy(value = Proxies.BitOr.class, name = "BitOr")
@OperationProxy(value = Proxies.BitXor.class, name = "BitXor")
@OperationProxy(value = Proxies.ShiftLeft.class, name = "ShiftLeft")
@OperationProxy(value = Proxies.ShiftRight.class, name = "ShiftRight")
@OperationProxy(value = Proxies.Concat.class, name = "Concat")
@OperationProxy(value = Comparisons.Identical.class, name = "Identical")
@OperationProxy(value = Comparisons.NotIdentical.class, name = "NotIdentical")
@OperationProxy(value = Comparisons.Equal.class, name = "Equal")
@OperationProxy(value = Comparisons.NotEqual.class, name = "NotEqual")
@OperationProxy(value = Comparisons.Less.class, name = "Less")
@OperationProxy(value = Comparisons.LessEqual.class, name = "LessEqual")
@OperationProxy(value = Comparisons.Greater.class, name = "Greater")
@OperationProxy(value = Comparisons.GreaterEqual.class, name = "GreaterEqual")
@OperationProxy(value = Proxies.Spaceship.class, name = "Spaceship")
@OperationProxy(value = Proxies.Negate.class, name = "Negate")
@OperationProxy(value = Proxies.Plus.class, name = "Plus")
@OperationProxy(value = Proxies.BitNot.class, name = "BitNot")
@OperationProxy(value = Proxies.ToBool.class, name = "ToBool")
@OperationProxy(value = Proxies.ReadElement.class, name = "ReadElement")
@OperationProxy(value = InterpolationNode.PartNode.class, name = "InterpolationPart")
@OperationProxy(value = Proxies.CastLong.class, name = "CastLong")
@OperationProxy(value = Proxies.CastDouble.class, name = "CastDouble")
@OperationProxy(value = Proxies.CastString.class, name = "CastString")
@ShortCircuitOperation(name = "And", operator = ShortCircuitOperation.Operator.AND_RETURN_CONVERTED,
        booleanConverter = Proxies.ToBool.class)
@ShortCircuitOperation(name = "Or", operator = ShortCircuitOperation.Operator.OR_RETURN_CONVERTED,
        booleanConverter = Proxies.ToBool.class)
@ShortCircuitOperation(name = "Elvis", operator = ShortCircuitOperation.Operator.OR_RETURN_VALUE,
        booleanConverter = Proxies.ToBool.class)
@ShortCircuitOperation(name = "Coalesce", operator = ShortCircuitOperation.Operator.OR_RETURN_VALUE,
        booleanConverter = PhpBytecodeRootNode.IsSetValue.class)
public abstract class PhpBytecodeRootNode extends RootNode implements BytecodeRootNode, SourceLocation.Code,
        Backtrace.Callee, LargeCode {
    /**
     * Calls and back-edges a function runs on the uncached interpreter before it specializes (php.bytecode.uncached).
     * The default, 0, specializes from the first call: Truffle 25.0.4 moves to the cached interpreter at a back-edge
     * by re-storing every local, and a local not assigned yet (cleared) takes the Object kind for good, so the ints
     * and floats it holds later stay boxed and its reads and writes generic. Upstream Truffle no longer re-stores;
     * then a threshold spares the cached nodes of functions that run only a few times.
     */
    static final int UNCACHED_THRESHOLD = Integer.getInteger("php.bytecode.uncached", 0);

    @CompilationFinal private String name;
    @CompilationFinal private String path;
    @CompilationFinal private boolean internal;
    @CompilationFinal private SourceSection sourceSection;
    @CompilationFinal(dimensions = 1) private PhpFunction.Param[] params;
    /** The local offset of each parameter's variable. */
    @CompilationFinal(dimensions = 1) private int[] parameterLocals;
    /** The type returned values are checked against (CheckReturn), or null. */
    @CompilationFinal private PhpType returnType;
    /** The PHP variables' locals (offsets 0 to their count - 1; hidden locals follow). */
    @CompilationFinal(dimensions = 1) private LocalAccessor[] variables;
    /** What each variable has held at the function's end so far (FunctionRootNode.releasedSlots). */
    @CompilationFinal(dimensions = 1) private byte[] releasedVariables;
    private static final byte RELEASED_ARRAY = 1;
    private static final byte RELEASED_REF = 2;
    private static final byte RELEASED_OBJECT = 4;
    /**
     * The hidden locals keeping owning temporaries (the result of `new` or a call, used where it isn't stored) until
     * their statement ends (StoreTemp, ReleaseTemp); released here too when the call ends first.
     */
    @CompilationFinal(dimensions = 1) private LocalAccessor[] temporaries = new LocalAccessor[0];
    /**
     * Which hidden locals have held an owning temporary so far: the others are neither read at a statement's end nor
     * at the function's, in compiled code (most of a function's calls return strings, arrays and scalars).
     */
    @CompilationFinal(dimensions = 1) private boolean[] temporariesHeld = new boolean[0];
    /** The hidden locals in which foreach loops keep what they iterate (an array they hold), cleared at the end. */
    @CompilationFinal(dimensions = 1) private LocalAccessor[] foreachStates;
    /** The class the function is declared in (a method's, a trait's user), or null; and its lowercase name. */
    @CompilationFinal private String className;
    @CompilationFinal private String scopeName;
    /** A closure's body, whose calling scope is the class the closure is bound to (in its frame). */
    @CompilationFinal private boolean closure;
    /** A generator function: `function &gen()` yields references; whether its body has a finally block. */
    @CompilationFinal private boolean generatorByRef;
    @CompilationFinal private boolean generatorHasFinally;
    /** `function &f()` (no generator): every return gives a PhpRef. */
    @CompilationFinal private boolean returnsRef;
    /** The PHP variables' names in their locals' order (get_defined_vars). */
    private String[] variableNames = new String[0];
    /** The PHP variables' names, by the offsets of {@link #variables}, for access by name (BytecodeScope). */
    private Map<String, Integer> variableOffsets = Map.of();
    /**
     * The hidden local keeping the call's variables by name ({@link BytecodeScope}) once an include or extract()
     * has made them; null when the body has neither.
     */
    @CompilationFinal private LocalAccessor scopeLocal;
    /** A generator function's frame in a `yield from` chain ({@link PhpGenerator.LinkedFrame}), made at first use. */
    private CallTarget linkedFrame;
    /**
     * A file's top level (or eval'd code): its variables are the scope's by name (the including call's
     * {@link IncludeScope} in the frame, else the globals), its calling scope the frame's.
     */
    @CompilationFinal private boolean file;
    /** A constant expression's root (BytecodeTranslator.Thunk): its scope is the class it is bound to. */
    @CompilationFinal private boolean thunk;
    /**
     * A function's defaults target (BytecodeTranslator.defaults): it runs on the callee's frame arguments and shows
     * as the callee in traces, a left-out argument as NULL (php-src's fake frame).
     */
    @CompilationFinal private boolean defaults;
    /**
     * A variadic function's: no call has passed it named extras yet ({@link NamedExtras}), so TakeNamedExtras and
     * BindNamedExtras do nothing; a defaults target's is never valid. Null for any other root. A clone (splitting)
     * shares it.
     */
    @CompilationFinal private Assumption noNamedExtras;
    /** The local offset of the hidden local TakeNamedExtras keeps the extras in, for traces; -1 for none. */
    @CompilationFinal private int namedExtrasLocal = -1;
    /** A file's top level has a loop, a foreach or a goto label (prepareForCompilation). */
    private boolean fileLoops;
    /** {@link #prepareForCompilation}: 0 not asked yet, 1 compiled as any code, 2 large and without a loop. */
    private volatile byte runOnce;
    /** An include site ran the file twice in one context: it runs a number of times a request that depends on data. */
    private volatile boolean includedAgain;

    protected PhpBytecodeRootNode(PhpLanguage language, FrameDescriptor.Builder frameDescriptor) {
        super(language, frameDescriptor.build());
    }

    /** The hidden locals of the owning temporaries (see {@link #temporaries}). */
    public void initializeTemporaries(LocalAccessor[] temporaryLocals) {
        this.temporaries = temporaryLocals;
        this.temporariesHeld = new boolean[temporaryLocals.length];
    }

    private void temporaryHeld(int index) {
        if (!temporariesHeld[index]) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            temporariesHeld[index] = true;
        }
    }

    /** What the translator knows of the function, set once its bytecode is built (a reparse keeps it). */
    public void initialize(String functionName, String file, boolean internalCode, SourceSection section,
                    PhpFunction.Param[] parameters, int[] parameterOffsets, PhpType returns,
                    LocalAccessor[] variableLocals, LocalAccessor[] foreachLocals, String declaringClass) {
        this.name = functionName;
        this.path = file;
        this.internal = internalCode;
        this.sourceSection = section;
        this.params = parameters;
        this.parameterLocals = parameterOffsets;
        this.returnType = returns;
        this.variables = variableLocals;
        this.releasedVariables = new byte[variableLocals.length];
        this.foreachStates = foreachLocals;
        this.className = declaringClass;
        this.scopeName = declaringClass == null ? null : Str.lower(declaringClass);
        this.closure = functionName.startsWith("{closure");
    }

    /**
     * php-src's executed scope here (zend_get_executed_scope; lowercase, or null): a closure's bound class, else the
     * class the function is declared in.
     */
    public String callingScope(VirtualFrame frame) {
        if (thunk) {
            // a constant expression is evaluated in its class's scope, the class it was bound to
            return frame.getArguments()[PhpFunction.ARG_STATIC] instanceof PhpClass owner ? owner.getLowerName() : null;
        }
        return closure || file ? PropNode.callingScope(frame, scopeName) : scopeName;
    }

    /** Makes this a constant expression's root (BytecodeTranslator.Thunk). */
    public void initializeThunk() {
        this.thunk = true;
    }

    /** Makes this a function's defaults target (BytecodeTranslator.defaults). */
    public void initializeDefaults() {
        this.defaults = true;
    }

    /**
     * A variadic function's root, or its defaults target's: the named extras a call may pass (NamedExtras) are kept
     * in the hidden local at extrasOffset. A function's take them only once a call with extras is bound for it.
     */
    public void initializeVariadic(int extrasOffset) {
        this.namedExtrasLocal = extrasOffset;
        this.noNamedExtras = defaults ? Assumption.NEVER_VALID
                : com.oracle.truffle.api.Truffle.getRuntime().createAssumption("no named extras");
    }

    /**
     * A call with named extras is bound for this function (NamedExtras.expect): its TakeNamedExtras looks for them
     * from now on. False for a root that can't take them.
     */
    boolean expectNamedExtras() {
        Assumption none = noNamedExtras;
        if (none == null) {
            return false;
        }
        if (none.isValid()) {
            none.invalidate("named extras");
        }
        return true;
    }

    /** Makes this a file's top level (FileRootNode); loops: whether it has a loop, a foreach or a goto label. */
    public void initializeFile(boolean loops) {
        this.file = true;
        this.fileLoops = loops;
    }

    public boolean isFile() {
        return file;
    }

    /** An include site ran the file again in a context where it had run it (IncludeNode.resolve). */
    public void includedAgain() {
        if (!includedAgain) {
            includedAgain = true;
        }
    }

    /**
     * Code that runs a few times a request at most, a number bounded by the program's text, and that compilation can't
     * pay back, isn't compiled: a file's top level that no include site has run twice in one context (each site runs
     * it at most once a request: Jetpack's class map is required by two sites, the autoloader's version check and its
     * manifest reader), and a constant expression's initializer (evaluated once per class and context), when it has no
     * loop and is {@link #isLarge large}. Declining it here makes the compilation a retryable bailout, so it is tried
     * again (and asked again) after Truffle's threshold.
     * <p>
     * Without a loop every instruction runs at most once per run, so compiling the code saves the interpreter's cost
     * a few times a request, against a compilation of about a millisecond per AST node: it would pay back after
     * thousands of requests. And large straight-line code is what doesn't compile at all: WooCommerce's Jetpack class
     * map, a property default of a thousand class constants and its country list failed after 40-360 s each, holding a
     * compiler thread and gigabytes of libgraal's heap (wiki/pe-round4-diagnosis.md). Code with a loop, or run twice
     * by one include site in one request (a template part in a loop), runs a number of times that depends on the
     * data, and is compiled as any function.
     */
    @Override
    protected boolean prepareForCompilation(boolean rootCompilation, int compilationTier, boolean lastTier) {
        if (thunk) {
            // an initializer runs once per class and context
            return !isLarge();
        }
        if (!file || includedAgain) {
            return true;
        }
        byte kind = runOnce;
        if (kind == 0) {
            kind = !fileLoops && isLarge() ? (byte) 2 : (byte) 1;
            runOnce = kind;
        }
        return kind != 2;
    }

    public boolean isClosure() {
        return closure;
    }

    /** Whether the scope a cache entry was made for is still the calling one. */
    public boolean sameScope(VirtualFrame frame, String scope) {
        return java.util.Objects.equals(callingScope(frame), scope);
    }

    /** Makes this the root of a function returning by reference. */
    public void initializeReturnsRef() {
        this.returnsRef = true;
    }

    /** Makes this a generator function's root (see {@link GeneratorStart}). */
    public void initializeGenerator(boolean byRef, boolean hasFinally) {
        this.generatorByRef = byRef;
        this.generatorHasFinally = hasFinally;
    }

    /**
     * The variables' names (by offset, as the variables were given to initialize), and the local keeping the call's
     * {@link BytecodeScope} (null if the body neither includes nor extracts).
     */
    public void initializeNames(String[] variableNames, LocalAccessor callScopeLocal) {
        this.variableNames = variableNames;
        Map<String, Integer> offsets = new HashMap<>();
        for (int i = 0; i < variableNames.length; i++) {
            offsets.put(variableNames[i], i);
        }
        this.variableOffsets = offsets;
        this.scopeLocal = callScopeLocal;
    }

    /** The local of the function's variable of this name, or null if the function names no such variable. */
    LocalAccessor variableNamed(String variableName) {
        Integer offset = variableOffsets.get(variableName);
        return offset == null ? null : variables[offset];
    }

    /**
     * The call's variables by name: made at the call's first include or extract() (IncludeScope.forCall), with the
     * calling scope the included code has (a closure's bound class, else the function's).
     */
    IncludeScope callScope(VirtualFrame frame, BytecodeNode bytecode) {
        if (file) {
            return IncludeScope.of(frame);
        }
        BytecodeScope scope = existingScope(frame, bytecode);
        if (scope != null) {
            return scope.at(bytecode);
        }
        scope = newScope(frame.materialize(), bytecode, PropNode.callingScope(frame, scopeName));
        scopeLocal.setObject(bytecode, frame, scope);
        return scope;
    }

    @TruffleBoundary
    private BytecodeScope newScope(MaterializedFrame frame, BytecodeNode bytecode, String classScope) {
        return new BytecodeScope(this, frame, bytecode, classScope);
    }

    /**
     * get_defined_vars() (zend_rebuild_symbol_table, then zend_array_dup): the defined variables, the function's in
     * their order then the call's others; a reference held elsewhere too stays one. A file's: the including call's,
     * or a copy of the globals.
     */
    @TruffleBoundary
    PhpArray definedVariables(MaterializedFrame frame, BytecodeNode bytecode, IncludeScope scope, PhpContext context) {
        if (file) {
            return scope == null ? context.globalsArray() : scope.definedVariables();
        }
        PhpArray result = new PhpArray();
        for (int i = 0; i < variables.length; i++) {
            Object stored = Locals.stored(frame, variables[i], bytecode);
            if (stored == null) {
                continue;
            }
            Object key = ArrayKey.of(Str.of(variableNames[i]));
            if (stored instanceof PhpRef ref && ref.isShared()) {
                result.putRef(key, ref);
            } else {
                result.put(key, Variables.value(stored));
            }
        }
        if (scope != null) {
            scope.addOthers(result);
        }
        return result;
    }

    /** get_defined_vars() (see definedVariables). */
    @Operation
    public static final class DefinedVars {
        @Specialization
        public static PhpArray vars(VirtualFrame frame, @Bind PhpBytecodeRootNode root, @Bind BytecodeNode bytecode,
                                    @Bind Node node) {
            return root.definedVariables(frame.materialize(), bytecode, root.namedScope(frame, bytecode),
                    PhpContext.get(node));
        }
    }

    /** The variables by name compact() reads: a file's scope, or the call's if an include or extract() made it. */
    IncludeScope namedScope(VirtualFrame frame, BytecodeNode bytecode) {
        return file ? IncludeScope.of(frame) : existingScope(frame, bytecode);
    }

    /** The call's variables by name if an include or extract() has made them, else null. */
    BytecodeScope existingScope(VirtualFrame frame, BytecodeNode bytecode) {
        if (scopeLocal == null || scopeLocal.isCleared(bytecode, frame)) {
            return null;
        }
        return scopeLocal.getObject(bytecode, frame) instanceof BytecodeScope scope ? scope.at(bytecode) : null;
    }

    /**
     * Prints the bytecode as it is when the process exits (php.bytecode.dump), for debugging: after the run, the
     * instructions show what the interpreter has quickened them to.
     */
    public void dumpAtExit() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.err.println(getBytecodeNode().dump())));
    }

    /**
     * On the way out, however the call ends: the variables give up their arrays, objects and references, and the
     * hidden locals their owning temporaries. An unset one is
     * skipped first: compiled code reads a local by the kind it has had (getLocalValue switches on it), and a
     * cleared local of kind Long or Object has no such value, which would deoptimize every call that leaves it
     * unset, and recompiling wouldn't change the kind. Only what each variable has held at an end so far is
     * released in compiled code, as FunctionRootNode.releaseLocals does; anything else deoptimizes once.
     */
    void releaseVariables(VirtualFrame frame, BytecodeNode bytecode) {
        releaseVariables(frame, bytecode, null);
    }

    /** releaseVariables on a return: the returned array, on its way to the caller, keeps its elements' counts. */
    @ExplodeLoop
    void releaseVariables(VirtualFrame frame, BytecodeNode bytecode, Object returned) {
        for (int i = 0; i < variables.length; i++) {
            LocalAccessor variable = variables[i];
            if (!variable.isCleared(bytecode, frame)) {
                Object value = variable.getObject(bytecode, frame);
                if (value instanceof PhpArray array) {
                    released(i, RELEASED_ARRAY);
                    if (array == returned) {
                        array.removeReturnedHolder();
                    } else {
                        array.removeHolder();
                    }
                } else if (value instanceof PhpObject object) {
                    released(i, RELEASED_OBJECT);
                    object.release();
                } else if (value instanceof PhpRef ref) {
                    released(i, RELEASED_REF);
                    ref.unbind();
                }
            }
        }
        for (int i = 0; i < temporaries.length; i++) {
            ReleaseTemp.release(frame, temporaries[i], i, this, bytecode);
        }
        if (scopeLocal != null) {
            BytecodeScope scope = existingScope(frame, bytecode);
            if (scope != null) {
                // the variables only the included code or extract() named
                scope.release();
                scopeLocal.clear(bytecode, frame);
            }
        }
    }

    private void released(int variable, byte kind) {
        if ((releasedVariables[variable] & kind) == 0) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            releasedVariables[variable] |= kind;
        }
    }

    /** A runtime error leaving this function surfaced at the instruction it left (the tree's statement). */
    @Override
    public AbstractTruffleException interceptTruffleException(AbstractTruffleException ex, VirtualFrame frame,
                                                              BytecodeNode bytecodeNode, int bytecodeIndex) {
        if (ex instanceof PhpError error) {
            error.surfacedAt(bytecodeNode, bytecodeIndex);
        }
        return ex;
    }

    /**
     * Any other exception leaves the function too, past the epilogs and the finally handlers, which see only PHP's
     * own: the variables are released here, and so are the arrays the foreach loops still running hold
     * (ForeachEnd's work).
     */
    @Override
    @ExplodeLoop
    public Throwable interceptInternalException(Throwable t, VirtualFrame frame, BytecodeNode bytecodeNode,
                                                int bytecodeIndex) {
        releaseVariables(frame, bytecodeNode);
        for (LocalAccessor state : foreachStates) {
            if (state.isCleared(bytecodeNode, frame)) {
                continue;
            }
            Object held = state.getObject(bytecodeNode, frame);
            if (held instanceof PhpArray array) {
                array.removeHolder();
                state.clear(bytecodeNode, frame);
            } else if (held instanceof RefIteration iteration) {
                iteration.end(PhpContext.get(bytecodeNode));
                state.clear(bytecodeNode, frame);
            }
        }
        return t;
    }

    // ---- function entry and exit ----

    /** The value the function returns: the variables are released. */
    @EpilogReturn
    public static final class ReturnValue {
        @Specialization
        public static Object doReturn(VirtualFrame frame, Object value, @Bind PhpBytecodeRootNode root,
                                      @Bind BytecodeNode bytecode) {
            root.releaseVariables(frame, bytecode, value);
            return value;
        }
    }

    static PhpClass calledClass(VirtualFrame frame) {
        return frame.getArguments()[PhpFunction.ARG_STATIC] instanceof PhpClass phpClass ? phpClass : null;
    }

    /**
     * A typed parameter's argument or default (ZEND_RECV's type check), emitted only for typed parameters: a value
     * of the type as it is passes, an object by its class's key; anything else is converted or fails out of line
     * (FunctionRootNode.argumentSlow). A reference is checked and converted in place.
     */
    @Operation
    @ConstantOperand(type = int.class)
    public static final class CheckParameter {
        @Specialization
        public static Object object(VirtualFrame frame, int index, PhpObject value, @Bind PhpBytecodeRootNode root,
                                    @Bind Node node, @Bind BytecodeNode bytecode, @Cached SubclassTestNode test) {
            return root.params[index].declared().acceptsObject(value.getPhpClass(), test) ? value
                    : slow(frame, index, value, root, node, bytecode);
        }

        @Specialization(guards = "!isObject(value)")
        public static Object other(VirtualFrame frame, int index, Object value, @Bind PhpBytecodeRootNode root,
                                   @Bind Node node, @Bind BytecodeNode bytecode) {
            if (value instanceof PhpRef) {
                return FunctionRootNode.checkArgument(root.name, root.params[index], index, value,
                        calledClass(frame), node);
            }
            Object accepted = root.params[index].declared().fast(value);
            return accepted != null ? accepted : slow(frame, index, value, root, node, bytecode);
        }

        /**
         * The argument converted or the TypeError: the parameter's variable holds the argument meanwhile, as
         * php-src's call slot does, so the error's trace shows it (an array, whose holders the binding counts,
         * stays out).
         */
        private static Object slow(VirtualFrame frame, int index, Object value, PhpBytecodeRootNode root, Node node,
                                   BytecodeNode bytecode) {
            if (!(value instanceof PhpArray)) {
                root.variables[root.parameterLocals[index]].setObject(bytecode, frame, value);
            }
            return FunctionRootNode.argumentSlow(root.name, root.params[index], index, value, calledClass(frame),
                    node);
        }

        static boolean isObject(Object value) {
            return value instanceof PhpObject;
        }
    }

    /**
     * `return expr;` in a function with a return type (ZEND_VERIFY_RETURN_TYPE), before any finally block runs:
     * the value, converted in the mode of the function's file, or the TypeError. A returned reference's content.
     */
    @Operation
    public static final class CheckReturn {
        @Specialization
        public static Object object(VirtualFrame frame, PhpObject value, @Bind PhpBytecodeRootNode root,
                                    @Bind Node node, @Cached SubclassTestNode test) {
            PhpType type = root.returnType;
            PhpClass phpClass = value.getPhpClass();
            // static: an object of the called class itself ($this, new static) needs no subclass test
            if (type.acceptsObject(phpClass, test) || type.has(PhpType.STATIC) && phpClass == calledClass(frame)) {
                return value;
            }
            return FunctionRootNode.returnSlow(root.name, type, value, calledClass(frame), root.path, node);
        }

        @Specialization(guards = "!isObject(value)")
        public static Object other(VirtualFrame frame, Object value, @Bind PhpBytecodeRootNode root,
                                   @Bind Node node) {
            // only a function returning by reference returns one: the others compile one check, as the tree's
            if (root.returnsRef && value instanceof PhpRef ref) {
                ref.set(FunctionRootNode.checkReturn(root.name, root.returnType, ref.get(), calledClass(frame),
                        root.path, node));
                return ref;
            }
            return FunctionRootNode.checkReturn(root.name, root.returnType, value, calledClass(frame), root.path,
                    node);
        }

        static boolean isObject(Object value) {
            return value instanceof PhpObject;
        }
    }

    /** The end of a function with a return type: "none returned", or a never-returning function's TypeError. */
    @Operation
    public static final class NoneReturned {
        @Specialization
        public static Object fail(VirtualFrame frame, @Bind PhpBytecodeRootNode root) {
            throw FunctionRootNode.noneReturned(root.name, root.returnType, calledClass(frame));
        }
    }

    @EpilogExceptional
    public static final class Unwind {
        @Specialization
        public static void unwind(VirtualFrame frame, AbstractTruffleException exception,
                                  @Bind PhpBytecodeRootNode root, @Bind BytecodeNode bytecode) {
            root.releaseVariables(frame, bytecode);
        }
    }

    /**
     * A prelude function's argument count, checked as php-src checks an internal function's (BuiltinRootNode):
     * too many arguments are an error too.
     */
    @Operation
    @ConstantOperand(type = String.class)
    @ConstantOperand(type = int.class)
    @ConstantOperand(type = int.class)
    public static final class CheckArity {
        @Specialization
        public static void check(VirtualFrame frame, String name, int min, int max) {
            int count = frame.getArguments().length - PhpFunction.ARG_FIRST;
            if (count < min || count > max) {
                CompilerDirectives.transferToInterpreter();
                throw Args.wrongCount(name, min, max, count);
            }
        }
    }

    /** The argument for parameter index; the call is an error when it passed fewer and there is no default. */
    @Operation
    @ConstantOperand(type = int.class)
    public static final class Argument {
        @Specialization
        public static Object argument(VirtualFrame frame, int index, @Bind PhpBytecodeRootNode root) {
            Object[] arguments = frame.getArguments();
            if (PhpFunction.ARG_FIRST + index < arguments.length) {
                return arguments[PhpFunction.ARG_FIRST + index];
            }
            throw FunctionRootNode.tooFewArguments(root.name, root.params,
                    arguments.length - PhpFunction.ARG_FIRST);
        }
    }

    @Operation
    @ConstantOperand(type = int.class)
    public static final class ArgumentGiven {
        @Specialization
        public static boolean given(VirtualFrame frame, int index) {
            return PhpFunction.ARG_FIRST + index < frame.getArguments().length;
        }
    }

    /**
     * Binds parameter index's variable to its argument or default value (FunctionRootNode.bindParameters): a
     * by-reference parameter to the argument's reference, else the value, converted to a declared scalar type.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = int.class)
    public static final class BindParameter {
        @Specialization
        public static void bind(VirtualFrame frame, LocalAccessor variable, int index, Object argument,
                                @Bind PhpBytecodeRootNode root, @Bind BytecodeNode bytecode) {
            PhpFunction.Param param = root.params[index];
            if (param.byRef()) {
                PhpRef ref = argument instanceof PhpRef passed ? passed : new PhpRef(argument);
                ref.bind();
                variable.setObject(bytecode, frame, ref);
                return;
            }
            Object value = PhpRef.deref(argument);
            if (value instanceof PhpArray array) {
                array.addHolder();
            } else if (value instanceof PhpObject object) {
                // SEND_VAR's copy, released with the variables
                object.addRef();
            }
            Locals.initialize(frame, variable, bytecode, value);
        }
    }

    /** `...$rest`: the arguments from parameter index on, collected into an array. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = int.class)
    public static final class BindVariadic {
        @Specialization
        public static void bind(VirtualFrame frame, LocalAccessor variable, int index,
                                @Bind PhpBytecodeRootNode root, @Bind BytecodeNode bytecode) {
            Object[] arguments = frame.getArguments();
            PhpFunction.Param param = root.params[index];
            if (param.declared() != null) {
                for (int i = PhpFunction.ARG_FIRST + index; i < arguments.length; i++) {
                    arguments[i] = FunctionRootNode.checkArgument(root.name, param, i - PhpFunction.ARG_FIRST,
                            arguments[i], calledClass(frame), bytecode);
                }
            }
            variable.setObject(bytecode, frame, FunctionRootNode.collectVariadic(arguments, index,
                    arguments.length - PhpFunction.ARG_FIRST, param.byRef()));
        }
    }

    /** A closure's captured variable (FunctionRootNode.bindCaptures): by value it holds the array, else binds. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = int.class)
    @ConstantOperand(type = boolean.class)
    public static final class BindCapture {
        @Specialization
        public static void bind(VirtualFrame frame, LocalAccessor variable, int index, boolean byRef,
                                @Bind BytecodeNode bytecode) {
            Object captured = ((PhpClosure) frame.getArguments()[PhpFunction.ARG_CLOSURE]).getCaptured(index);
            if (captured == null) {
                // an arrow function's capture of an unset variable (ReadVarIfDefined): left unset
                return;
            }
            if (!byRef && captured instanceof PhpArray array) {
                array.addHolder();
            } else if (!byRef && captured instanceof PhpObject object) {
                object.addRef();
            } else if (captured instanceof PhpRef ref) {
                ref.bind();
            }
            variable.setObject(bytecode, frame, captured);
        }
    }

    // ---- variables ----

    /**
     * `$x`: the variable's value (a reference's content), ints and floats unboxed. An unset variable reads as null
     * after php-src's "Undefined variable" warning (zval_undefined_cv), on a branch that a site which hasn't met it
     * compiles as a deoptimization.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    public static final class ReadVar {
        @Specialization(rewriteOn = Locals.KindMismatch.class)
        public static long readLong(VirtualFrame frame, LocalAccessor variable, @Bind BytecodeNode bytecode)
                throws Locals.KindMismatch {
            return Locals.readLong(frame, variable, bytecode);
        }

        @Specialization(rewriteOn = Locals.KindMismatch.class)
        public static double readDouble(VirtualFrame frame, LocalAccessor variable, @Bind BytecodeNode bytecode)
                throws Locals.KindMismatch {
            return Locals.readDouble(frame, variable, bytecode);
        }

        @Specialization(replaces = {"readLong", "readDouble"})
        public static Object read(VirtualFrame frame, LocalAccessor variable, @Bind BytecodeNode bytecode,
                                  @Bind Node node, @Cached InlinedBranchProfile undefined) {
            Object stored = Locals.stored(frame, variable, bytecode);
            if (stored == null) {
                undefined.enter(node);
                undefinedVariable(node, variable, bytecode);
                return PhpNull.NULL;
            }
            return Variables.value(stored);
        }
    }

    /** `$x` as isset(), `??` and `??=` read it (BP_VAR_IS): null when unset, quietly. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    public static final class ReadVarIfSet {
        @Specialization(rewriteOn = Locals.KindMismatch.class)
        public static long readLong(VirtualFrame frame, LocalAccessor variable, @Bind BytecodeNode bytecode)
                throws Locals.KindMismatch {
            return Locals.readLong(frame, variable, bytecode);
        }

        @Specialization(rewriteOn = Locals.KindMismatch.class)
        public static double readDouble(VirtualFrame frame, LocalAccessor variable, @Bind BytecodeNode bytecode)
                throws Locals.KindMismatch {
            return Locals.readDouble(frame, variable, bytecode);
        }

        @Specialization(replaces = {"readLong", "readDouble"})
        public static Object read(VirtualFrame frame, LocalAccessor variable, @Bind BytecodeNode bytecode) {
            return Variables.value(Locals.stored(frame, variable, bytecode));
        }
    }

    /**
     * An arrow function's capture of `$x` (BIND_LEXICAL with ZEND_BIND_IMPLICIT): quiet, and Java null when unset,
     * which leaves the arrow function's variable unset (BindCapture), so its own read warns.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    public static final class ReadVarIfDefined {
        @Specialization
        public static Object read(VirtualFrame frame, LocalAccessor variable, @Bind BytecodeNode bytecode) {
            Object stored = Locals.stored(frame, variable, bytecode);
            return stored == null ? null : Variables.value(stored);
        }
    }

    /**
     * A variable's value as an operation's operand reads it (BP_VAR_R), as ReadVar: null after the "Undefined
     * variable" warning when it is unset.
     */
    static Object readValue(VirtualFrame frame, LocalAccessor variable, BytecodeNode bytecode, Node node,
                            InlinedBranchProfile undefined) {
        Object stored = Locals.stored(frame, variable, bytecode);
        if (stored == null) {
            undefined.enter(node);
            undefinedVariable(node, variable, bytecode);
            return PhpNull.NULL;
        }
        return Variables.value(stored);
    }

    /** php-src's zval_undefined_cv: "Undefined variable $name" at the instruction. */
    @TruffleBoundary
    static void undefinedVariable(Node node, LocalAccessor variable, BytecodeNode bytecode) {
        ErrorFunctions.raise(PhpContext.get(node), ErrorFunctions.E_WARNING,
                Str.of("Undefined variable $" + variable.getLocalName(bytecode)), SourceLocation.of(node));
    }

    /**
     * The warning of a read-modify-write of an unset variable (BP_VAR_RW: `$x .= `, `$x++`): php-src's operation
     * goes on with null and stores its result before an exception the error handler threw leaves, so the exception
     * is returned, for the caller to throw after its store; null if there is none.
     */
    @TruffleBoundary
    static PhpThrowable undefinedForUpdate(Node node, LocalAccessor variable, BytecodeNode bytecode) {
        try {
            undefinedVariable(node, variable, bytecode);
            return null;
        } catch (PhpThrowable thrown) {
            return thrown;
        }
    }

    /** `$x = value` (AssignNode on a LocalVarNode): through the reference if the variable is one. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    public static final class WriteVar {
        @Specialization
        public static long writeLong(VirtualFrame frame, LocalAccessor variable, long value,
                                     @Bind BytecodeNode bytecode,
                @Shared("writes") @Cached(neverDefault = true) Locals.Writes writes) {
            writes.writeLong(frame, variable, bytecode, value);
            return value;
        }

        @Specialization
        public static double writeDouble(VirtualFrame frame, LocalAccessor variable, double value,
                                         @Bind BytecodeNode bytecode,
                @Shared("writes") @Cached(neverDefault = true) Locals.Writes writes) {
            writes.writeDouble(frame, variable, bytecode, value);
            return value;
        }

        @Specialization(replaces = {"writeLong", "writeDouble"})
        public static Object write(VirtualFrame frame, LocalAccessor variable, Object value,
                                   @Bind BytecodeNode bytecode,
                @Shared("writes") @Cached(neverDefault = true) Locals.Writes writes) {
            writes.write(frame, variable, bytecode, value);
            return value;
        }
    }

    /** `++$x`, `$x++`, `--$x`, `$x--` (IncDecNode): ints step unboxed until an overflow makes a float. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = boolean.class, name = "increment")
    @ConstantOperand(type = boolean.class, name = "prefix")
    public static final class IncDecVar {
        @Specialization(rewriteOn = {Locals.KindMismatch.class, ArithmeticException.class})
        public static long longs(VirtualFrame frame, LocalAccessor variable, boolean increment, boolean prefix,
                                 @Bind BytecodeNode bytecode,
                @Shared("writes") @Cached(neverDefault = true) Locals.Writes writes) throws Locals.KindMismatch {
            long old = Locals.readLong(frame, variable, bytecode);
            long updated = increment ? Math.addExact(old, 1) : Math.subtractExact(old, 1);
            writes.writeLong(frame, variable, bytecode, updated);
            return prefix ? updated : old;
        }

        @Specialization(replaces = "longs")
        public static Object generic(VirtualFrame frame, LocalAccessor variable, boolean increment, boolean prefix,
                                     @Bind BytecodeNode bytecode, @Bind Node node,
                                     @Shared("undefined") @Cached InlinedBranchProfile undefined,
                @Shared("writes") @Cached(neverDefault = true) Locals.Writes writes) {
            Object stored = Locals.stored(frame, variable, bytecode);
            PhpThrowable pending = null;
            if (stored == null) {
                undefined.enter(node);
                pending = undefinedForUpdate(node, variable, bytecode);
            }
            Object old = Variables.value(stored);
            Object updated = AssignNodes.IncDecNode.stepped(node, old, increment);
            writes.write(frame, variable, bytecode, updated);
            if (pending != null) {
                throw pending;
            }
            return prefix ? updated : old;
        }
    }

    /**
     * `$x op= value` (CompoundAssignNode on a LocalVarNode): the value is evaluated first, then the variable read.
     * An int variable with an int operand and a float variable with a float operand have unboxed specializations,
     * which compute what {@link AssignNodes.CompoundAssignNode#apply} does for them (mixed kinds go generic);
     * `.=` converts the variable's content first, then the value.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = int.class, name = "op")
    public static final class CompoundVar {
        @Specialization(guards = "op == 8")
        public static TruffleString concat(VirtualFrame frame, LocalAccessor variable, int op, Object operand,
                                           @Bind BytecodeNode bytecode, @Bind Node node,
                                           @Cached InlinedBranchProfile undefined,
                                           @Cached ToStrNode currentToStr, @Cached ToStrNode operandToStr,
                                           @Cached TruffleString.ConcatNode concat,
                @Shared("writes") @Cached(neverDefault = true) Locals.Writes writes) {
            Object stored = Locals.stored(frame, variable, bytecode);
            PhpThrowable pending = null;
            if (stored == null) {
                undefined.enter(node);
                pending = undefinedForUpdate(node, variable, bytecode);
            }
            TruffleString left = currentToStr.execute(Variables.value(stored));
            TruffleString result = concat.execute(left, operandToStr.execute(operand), Str.ENC, true);
            writes.writeObject(frame, variable, bytecode, result);
            if (pending != null) {
                throw pending;
            }
            return result;
        }

        @Specialization(guards = "isIntOperator(op)", rewriteOn = {Locals.KindMismatch.class,
                ArithmeticException.class})
        public static long longs(VirtualFrame frame, LocalAccessor variable, int op, long operand,
                                 @Bind BytecodeNode bytecode,
                @Shared("writes") @Cached(neverDefault = true) Locals.Writes writes) throws Locals.KindMismatch {
            long current = Locals.readLong(frame, variable, bytecode);
            long result = switch (op) {
                case 1 -> Math.addExact(current, operand);
                case 2 -> Math.subtractExact(current, operand);
                case 3 -> Math.multiplyExact(current, operand);
                case 9 -> current | operand;
                case 10 -> current & operand;
                default -> current ^ operand;
            };
            writes.writeLong(frame, variable, bytecode, result);
            return result;
        }

        /** Declared before doubles, which the implicit int-to-float cast would otherwise let take an int. */
        @Specialization(guards = "op != 8")
        public static Object longOperand(VirtualFrame frame, LocalAccessor variable, int op, long operand,
                                         @Bind BytecodeNode bytecode, @Bind Node node,
                                         @Shared("undefined") @Cached InlinedBranchProfile undefined,
                @Shared("writes") @Cached(neverDefault = true) Locals.Writes writes) {
            return generic(frame, variable, op, operand, bytecode, node, undefined, writes);
        }

        @Specialization(guards = "isFloatOperator(op)", rewriteOn = Locals.KindMismatch.class)
        public static double doubles(VirtualFrame frame, LocalAccessor variable, int op, double operand,
                                     @Bind BytecodeNode bytecode, @Bind Node node,
                @Shared("writes") @Cached(neverDefault = true) Locals.Writes writes) throws Locals.KindMismatch {
            double current = Locals.readDouble(frame, variable, bytecode);
            if (op == 4 && operand == 0) {
                Operators.div(node, current, operand); // throws DivisionByZeroError
            }
            double result = switch (op) {
                case 1 -> current + operand;
                case 2 -> current - operand;
                case 3 -> current * operand;
                default -> current / operand;
            };
            writes.writeDouble(frame, variable, bytecode, result);
            return result;
        }

        @Specialization(guards = "op != 8", replaces = {"longs", "longOperand", "doubles"})
        public static Object generic(VirtualFrame frame, LocalAccessor variable, int op, Object operand,
                                     @Bind BytecodeNode bytecode, @Bind Node node,
                                     @Shared("undefined") @Cached InlinedBranchProfile undefined,
                @Shared("writes") @Cached(neverDefault = true) Locals.Writes writes) {
            Object stored = Locals.stored(frame, variable, bytecode);
            PhpThrowable pending = null;
            if (stored == null) {
                undefined.enter(node);
                pending = undefinedForUpdate(node, variable, bytecode);
            }
            Object result = AssignNodes.CompoundAssignNode.apply(node, op, Variables.value(stored), operand);
            writes.write(frame, variable, bytecode, result);
            if (pending != null) {
                throw pending;
            }
            return result;
        }

        public static boolean isIntOperator(int op) {
            return op == 1 || op == 2 || op == 3 || op == 9 || op == 10 || op == 11;
        }

        public static boolean isFloatOperator(int op) {
            return op == 1 || op == 2 || op == 3 || op == 4;
        }
    }

    /** `$x = value;` as a statement: WriteVar without the result. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    public static final class WriteVarStatement {
        @ForceQuickening
        @Specialization
        public static void writeLong(VirtualFrame frame, LocalAccessor variable, long value,
                                     @Bind BytecodeNode bytecode,
                @Shared("writes") @Cached(neverDefault = true) Locals.Writes writes) {
            writes.writeLong(frame, variable, bytecode, value);
        }

        @ForceQuickening
        @Specialization
        public static void writeDouble(VirtualFrame frame, LocalAccessor variable, double value,
                                       @Bind BytecodeNode bytecode,
                @Shared("writes") @Cached(neverDefault = true) Locals.Writes writes) {
            writes.writeDouble(frame, variable, bytecode, value);
        }

        @Specialization(replaces = {"writeLong", "writeDouble"})
        public static void write(VirtualFrame frame, LocalAccessor variable, Object value,
                                 @Bind BytecodeNode bytecode,
                @Shared("writes") @Cached(neverDefault = true) Locals.Writes writes) {
            writes.write(frame, variable, bytecode, value);
        }
    }

    /** `$x++;` as a statement: IncDecVar without the result. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = boolean.class, name = "increment")
    public static final class IncDecVarStatement {
        @ForceQuickening
        @Specialization(rewriteOn = {Locals.KindMismatch.class, ArithmeticException.class})
        public static void longs(VirtualFrame frame, LocalAccessor variable, boolean increment,
                                 @Bind BytecodeNode bytecode,
                @Shared("writes") @Cached(neverDefault = true) Locals.Writes writes) throws Locals.KindMismatch {
            IncDecVar.longs(frame, variable, increment, true, bytecode, writes);
        }

        @Specialization(replaces = "longs")
        public static void generic(VirtualFrame frame, LocalAccessor variable, boolean increment,
                                   @Bind BytecodeNode bytecode, @Bind Node node,
                                   @Shared("undefined") @Cached InlinedBranchProfile undefined,
                @Shared("writes") @Cached(neverDefault = true) Locals.Writes writes) {
            IncDecVar.generic(frame, variable, increment, true, bytecode, node, undefined, writes);
        }
    }

    /** `$x op= value;` as a statement: CompoundVar without the result. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = int.class, name = "op")
    public static final class CompoundVarStatement {
        @Specialization(guards = "op == 8")
        public static void concat(VirtualFrame frame, LocalAccessor variable, int op, Object operand,
                                  @Bind BytecodeNode bytecode, @Bind Node node,
                                  @Cached InlinedBranchProfile undefined, @Cached ToStrNode currentToStr,
                                  @Cached ToStrNode operandToStr, @Cached TruffleString.ConcatNode concat,
                @Shared("writes") @Cached(neverDefault = true) Locals.Writes writes) {
            CompoundVar.concat(frame, variable, op, operand, bytecode, node, undefined, currentToStr, operandToStr,
                    concat, writes);
        }

        @ForceQuickening
        @Specialization(guards = "isIntOperator(op)", rewriteOn = {Locals.KindMismatch.class,
                ArithmeticException.class})
        public static void longs(VirtualFrame frame, LocalAccessor variable, int op, long operand,
                                 @Bind BytecodeNode bytecode,
                @Shared("writes") @Cached(neverDefault = true) Locals.Writes writes) throws Locals.KindMismatch {
            CompoundVar.longs(frame, variable, op, operand, bytecode, writes);
        }

        @Specialization(guards = "op != 8")
        public static void longOperand(VirtualFrame frame, LocalAccessor variable, int op, long operand,
                                       @Bind BytecodeNode bytecode, @Bind Node node,
                                       @Shared("undefined") @Cached InlinedBranchProfile undefined,
                @Shared("writes") @Cached(neverDefault = true) Locals.Writes writes) {
            CompoundVar.generic(frame, variable, op, operand, bytecode, node, undefined, writes);
        }

        @ForceQuickening
        @Specialization(guards = "isFloatOperator(op)", rewriteOn = Locals.KindMismatch.class)
        public static void doubles(VirtualFrame frame, LocalAccessor variable, int op, double operand,
                                   @Bind BytecodeNode bytecode, @Bind Node node,
                @Shared("writes") @Cached(neverDefault = true) Locals.Writes writes) throws Locals.KindMismatch {
            CompoundVar.doubles(frame, variable, op, operand, bytecode, node, writes);
        }

        @Specialization(guards = "op != 8", replaces = {"longs", "longOperand", "doubles"})
        public static void generic(VirtualFrame frame, LocalAccessor variable, int op, Object operand,
                                   @Bind BytecodeNode bytecode, @Bind Node node,
                                   @Shared("undefined") @Cached InlinedBranchProfile undefined,
                @Shared("writes") @Cached(neverDefault = true) Locals.Writes writes) {
            CompoundVar.generic(frame, variable, op, operand, bytecode, node, undefined, writes);
        }

        public static boolean isIntOperator(int op) {
            return CompoundVar.isIntOperator(op);
        }

        public static boolean isFloatOperator(int op) {
            return CompoundVar.isFloatOperator(op);
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    public static final class UnsetVar {
        @Specialization
        public static void unset(VirtualFrame frame, LocalAccessor variable, @Bind BytecodeNode bytecode) {
            Locals.unset(frame, variable, bytecode);
        }
    }

    // ---- elements ----

    /**
     * `$x[key] = value` (DimNode.write on a variable): the value is held while the variable's array is made
     * writable, so `$a[k] = $a` copies $a first; an int is stored unboxed while the array holds only ints.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    public static final class WriteElement {
        @Specialization
        public static long writeLong(VirtualFrame frame, LocalAccessor variable, long value, Object key,
                                     @Bind BytecodeNode bytecode, @Bind Node node,
                                     @Cached ElementNodes.ElementWriteNode write,
                                     @Cached InlinedBranchProfile sharedArray,
                                     @Cached InlinedBranchProfile refArray,
                                     @Cached InlinedBranchProfile createdArray) {
            PhpArray array = Locals.arrayForWrite(frame, variable, bytecode, node, sharedArray, refArray,
                    createdArray);
            if (array == null) {
                DimNode.writeToObject(node, Variables.value(Locals.stored(frame, variable, bytecode)), key, value);
            } else {
                write.executeLong(array, key, value);
            }
            return value;
        }

        @Specialization(replaces = "writeLong")
        public static Object write(VirtualFrame frame, LocalAccessor variable, Object value, Object key,
                                   @Bind BytecodeNode bytecode, @Bind Node node,
                                   @Cached ElementNodes.ElementWriteNode write,
                                   @Cached InlinedBranchProfile sharedArray,
                                   @Cached InlinedBranchProfile refArray,
                                   @Cached InlinedBranchProfile createdArray,
                                   @Cached InlinedBranchProfile arrayWrite,
                                   @Cached InlinedBranchProfile objectWrite) {
            // held while the array is made writable, so `$a[k] = $a` copies $a first (DimNode.write)
            PhpArray held = null;
            if (value instanceof PhpArray array) {
                arrayWrite.enter(node);
                held = array;
                held.addHolder();
            }
            try {
                PhpArray array = Locals.arrayForWrite(frame, variable, bytecode, node, sharedArray, refArray,
                        createdArray);
                if (array == null) {
                    objectWrite.enter(node);
                    DimNode.writeToObject(node, Variables.value(Locals.stored(frame, variable, bytecode)), key,
                            value);
                } else {
                    write.execute(array, key, value);
                }
            } catch (Throwable t) {
                if (held != null) {
                    DimNode.letGo(held);
                }
                throw t;
            }
            if (held != null) {
                held.removeHolder();
            }
            return value;
        }
    }

    /** `$x[] = value`. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    public static final class AppendElement {
        @Specialization
        public static long appendLong(VirtualFrame frame, LocalAccessor variable, long value,
                                      @Bind BytecodeNode bytecode, @Bind Node node,
                                      @Cached InlinedBranchProfile sharedArray,
                                      @Cached InlinedBranchProfile refArray,
                                      @Cached InlinedBranchProfile createdArray) {
            PhpArray array = Locals.arrayForWrite(frame, variable, bytecode, node, sharedArray, refArray,
                    createdArray);
            if (array == null) {
                DimNode.writeToObject(node, Variables.value(Locals.stored(frame, variable, bytecode)),
                        PhpNull.NULL, value);
            } else {
                array.appendLong(value);
            }
            return value;
        }

        @Specialization(replaces = "appendLong")
        public static Object append(VirtualFrame frame, LocalAccessor variable, Object value,
                                    @Bind BytecodeNode bytecode, @Bind Node node,
                                    @Cached InlinedBranchProfile sharedArray,
                                    @Cached InlinedBranchProfile refArray,
                                    @Cached InlinedBranchProfile createdArray,
                                    @Cached InlinedBranchProfile arrayWrite,
                                    @Cached InlinedBranchProfile objectWrite) {
            // held while the array is made writable, so `$a[k] = $a` copies $a first (DimNode.write)
            PhpArray held = null;
            if (value instanceof PhpArray array) {
                arrayWrite.enter(node);
                held = array;
                held.addHolder();
            }
            try {
                PhpArray array = Locals.arrayForWrite(frame, variable, bytecode, node, sharedArray, refArray,
                        createdArray);
                if (array == null) {
                    objectWrite.enter(node);
                    DimNode.writeToObject(node, Variables.value(Locals.stored(frame, variable, bytecode)),
                            PhpNull.NULL, value);
                } else {
                    array.append(value);
                }
            } catch (Throwable t) {
                if (held != null) {
                    DimNode.letGo(held);
                }
                throw t;
            }
            if (held != null) {
                held.removeHolder();
            }
            return value;
        }
    }

    // ---- isset(), empty() and ?? (IssetNode, EmptyNode, CoalesceNode) ----

    /**
     * Whether a value read as isset() reads places is set: null stands for unset too. `a ?? b` is a short circuit
     * on it (CoalesceNode), `isset($x)` it on the variable's value.
     */
    @Operation
    public static final class IsSetValue {
        @Specialization
        public static boolean isSet(Object value) {
            return value != PhpNull.NULL;
        }
    }

    /**
     * `$container[key]` as isset(), empty() and `??` read it (DimNode.readIfSet, FETCH_DIM_IS): the element, null
     * when missing; a string's offset in range; an ArrayAccess object's offsetGet() once offsetExists() said yes;
     * null for anything else.
     */
    @Operation
    public static final class ElementIfSet {
        @Specialization
        public static Object array(PhpArray container, Object key,
                                   @Shared @Cached ElementNodes.ElementIfSetNode read) {
            return read.execute(container, key);
        }

        @Fallback
        public static Object other(Object container, Object key, @Shared @Cached ElementNodes.ElementIfSetNode read,
                                   @Bind Node node) {
            if (container == PhpNull.NULL || !DimNode.isSetIn(node, container, key)) {
                return PhpNull.NULL;
            }
            return read.execute(container, key);
        }
    }

    /**
     * An element as the object of a property written (FETCH_DIM_W): an array's quietly (`[]`'s new element null),
     * an ArrayAccess object's offsetGet() alone (of null for `[]`), a string's offset php-src's Error.
     */
    @Operation
    public static final class ElementForWrite {
        @Specialization
        public static Object array(PhpArray container, Object key,
                                   @Shared @Cached ElementNodes.ElementIfSetNode read) {
            return key == APPEND ? PhpNull.NULL : read.execute(container, key);
        }

        @Fallback
        public static Object other(Object container, Object key, @Shared @Cached ElementNodes.ElementIfSetNode read) {
            if (container instanceof TruffleString) {
                throw stringOffsetObject();
            }
            return read.execute(container, key == APPEND ? PhpNull.NULL : key);
        }
    }

    @TruffleBoundary
    private static PhpError stringOffsetObject() {
        return new PhpError("Error", "Cannot use string offset as an object");
    }

    /** `isset($container[key])` (DimNode.isSet): as ElementIfSet, but an ArrayAccess object's offsetExists() alone. */
    @Operation
    public static final class IssetElement {
        @Specialization
        public static boolean array(PhpArray container, Object key, @Cached ElementNodes.ElementIfSetNode read) {
            return read.execute(container, key) != PhpNull.NULL;
        }

        @Fallback
        public static boolean other(Object container, Object key, @Bind Node node) {
            return container != PhpNull.NULL && DimNode.isSetIn(node, container, key);
        }
    }

    // ---- nested elements (DimNode chains, ElementChain) ----

    /**
     * `$x[k1]...[kn] = value`, `$x[k1]...[] = value` (append): the value is held while the containers are made
     * writable from the variable down (Locals.arrayForWrite, then ElementChain.forWrite), so storing $x into
     * itself copies it first; then the element is written, or offsetSet() of an ArrayAccess object on the way.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = boolean.class, name = "append")
    public static final class WriteNested {
        @Specialization
        public static Object write(VirtualFrame frame, LocalAccessor variable, boolean append, Object value,
                                   @Variadic Object[] keys, @Bind BytecodeNode bytecode, @Bind Node node,
                                   @Cached ElementNodes.ArrayKeyNode toKey,
                                   @Cached ElementNodes.ElementWriteNode write,
                                   @Cached InlinedBranchProfile arrayWrite, @Cached InlinedBranchProfile objectWrite,
                                   @Cached InlinedBranchProfile sharedArray,
                                   @Cached InlinedBranchProfile refArray,
                                   @Cached InlinedBranchProfile createdArray) {
            // held while the containers are made writable, so `$a[k] = $a` copies $a first (DimNode.write)
            PhpArray held = null;
            if (value instanceof PhpArray array) {
                arrayWrite.enter(node);
                held = array;
                held.addHolder();
            }
            try {
                Object base = variableForWrite(frame, variable, bytecode, node, sharedArray, refArray, createdArray);
                int levels = append ? keys.length : keys.length - 1;
                Object container = ElementChain.forWrite(node, base, keys, levels, toKey);
                ElementChain.write(node, container, append ? ElementChain.APPEND : keys[levels], value, write,
                        objectWrite);
            } catch (Throwable t) {
                if (held != null) {
                    DimNode.letGo(held);
                }
                throw t;
            }
            if (held != null) {
                held.removeHolder();
            }
            return value;
        }
    }

    /** `$obj->name[k1]...[kn] = value` (and append): as WriteNested, from the property (PropNode.arrayForWrite). */
    @Operation
    @ConstantOperand(type = PropSite.class)
    @ConstantOperand(type = boolean.class, name = "append")
    public static final class WritePropNested {
        @Specialization
        public static Object write(VirtualFrame frame, PropSite site, boolean append, Object value, Object object,
                                   @Variadic Object[] keys,
                                   @Cached(value = "site.create()", uncached = "site.uncached()",
                                           neverDefault = true) PropNode access,
                                   @Bind Node node, @Cached ElementNodes.ArrayKeyNode toKey,
                                   @Cached ElementNodes.ElementWriteNode write,
                                   @Cached InlinedBranchProfile arrayWrite, @Cached InlinedBranchProfile objectWrite) {
            String name = placeName(site, keys);
            keys = placeKeys(site, keys);
            // held while the containers are made writable, so `$a[k] = $a` copies $a first (DimNode.write)
            PhpArray held = null;
            if (value instanceof PhpArray array) {
                arrayWrite.enter(node);
                held = array;
                held.addHolder();
            }
            try {
                Object base = propertyForWrite(frame, access, object, name);
                int levels = append ? keys.length : keys.length - 1;
                Object container = ElementChain.forWrite(node, base, keys, levels, toKey);
                ElementChain.write(node, container, append ? ElementChain.APPEND : keys[levels], value, write,
                        objectWrite);
            } catch (Throwable t) {
                if (held != null) {
                    DimNode.letGo(held);
                }
                throw t;
            }
            if (held != null) {
                held.removeHolder();
            }
            return value;
        }
    }

    /**
     * `$x[k1]...[kn] op= value` (CompoundAssignNode on a DimNode): the value first, then the containers made
     * writable, the element read (offsetGet() of an ArrayAccess object), combined as CompoundAssignNode.apply
     * does (`.=` converting the element first), and written.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = int.class, name = "op")
    public static final class CompoundNested {
        @Specialization
        public static Object compound(VirtualFrame frame, LocalAccessor variable, int op, Object operand,
                                      @Variadic Object[] keys, @Bind BytecodeNode bytecode, @Bind Node node,
                                      @Cached ElementNodes.ArrayKeyNode toKey,
                                      @Cached ElementNodes.ElementReadNode read,
                                      @Cached ElementNodes.ElementWriteNode write,
                                   @Cached InlinedBranchProfile objectWrite,
                                      @Cached InlinedBranchProfile sharedArray,
                                      @Cached InlinedBranchProfile refArray,
                                      @Cached InlinedBranchProfile createdArray) {
            Object base = variableForUpdate(frame, variable, bytecode, node, sharedArray, refArray, createdArray);
            int last = keys.length - 1;
            Object container = ElementChain.forUpdate(node, base, keys, last, toKey);
            Object result = compounded(node, op, ElementChain.readForUpdate(read, container, keys[last]), operand);
            ElementChain.write(node, container, keys[last], result, write, objectWrite);
            return result;
        }
    }

    @Operation
    @ConstantOperand(type = PropSite.class)
    @ConstantOperand(type = int.class, name = "op")
    public static final class CompoundPropNested {
        @Specialization
        public static Object compound(VirtualFrame frame, PropSite site, int op, Object operand, Object object,
                                      @Variadic Object[] keys,
                                      @Cached(value = "site.create()", uncached = "site.uncached()",
                                              neverDefault = true) PropNode access,
                                      @Bind Node node, @Cached ElementNodes.ArrayKeyNode toKey,
                                      @Cached ElementNodes.ElementReadNode read,
                                      @Cached ElementNodes.ElementWriteNode write,
                                   @Cached InlinedBranchProfile objectWrite) {
            String name = placeName(site, keys);
            keys = placeKeys(site, keys);
            Object base = propertyForWrite(frame, access, object, name);
            int last = keys.length - 1;
            Object container = ElementChain.forUpdate(node, base, keys, last, toKey);
            Object result = compounded(node, op, ElementChain.readForUpdate(read, container, keys[last]), operand);
            ElementChain.write(node, container, keys[last], result, write, objectWrite);
            return result;
        }
    }

    /**
     * An object's element stepped (FETCH_DIM_RW, then the increment): offsetGet()'s reference, else its value with
     * php-src's notice, which the step doesn't write back.
     */
    @TruffleBoundary
    private static Object stepOverloaded(Node node, Object object, Object key, boolean increment, boolean prefix) {
        PhpRef ref = DimNode.overloadedRef(node, object, key == ElementChain.APPEND ? PhpNull.NULL : key);
        Object old = ref.get();
        Object updated = AssignNodes.IncDecNode.stepped(node, old, increment);
        ref.set(updated);
        return prefix ? updated : old;
    }

    /** `++$x[k1]...[kn]`, `$x[k]--`... (IncDecNode on a DimNode): the element read, stepped and written. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = boolean.class, name = "increment")
    @ConstantOperand(type = boolean.class, name = "prefix")
    public static final class IncDecNested {
        @Specialization
        public static Object step(VirtualFrame frame, LocalAccessor variable, boolean increment, boolean prefix,
                                  @Variadic Object[] keys, @Bind BytecodeNode bytecode, @Bind Node node,
                                  @Cached ElementNodes.ArrayKeyNode toKey,
                                  @Cached ElementNodes.ElementReadNode read,
                                  @Cached ElementNodes.ElementWriteNode write, @Cached InlinedBranchProfile objectWrite,
                                  @Cached InlinedBranchProfile sharedArray,
                                  @Cached InlinedBranchProfile refArray,
                                  @Cached InlinedBranchProfile createdArray) {
            Object base = variableForUpdate(frame, variable, bytecode, node, sharedArray, refArray, createdArray);
            int last = keys.length - 1;
            Object container = ElementChain.forUpdate(node, base, keys, last, toKey);
            if (container instanceof PhpObject) {
                return stepOverloaded(node, container, keys[last], increment, prefix);
            }
            Object old = ElementChain.readForUpdate(read, container, keys[last]);
            Object updated = AssignNodes.IncDecNode.stepped(node, old, increment);
            ElementChain.write(node, container, keys[last], updated, write, objectWrite);
            return prefix ? updated : old;
        }
    }

    @Operation
    @ConstantOperand(type = PropSite.class)
    @ConstantOperand(type = boolean.class, name = "increment")
    @ConstantOperand(type = boolean.class, name = "prefix")
    public static final class IncDecPropNested {
        @Specialization
        public static Object step(VirtualFrame frame, PropSite site, boolean increment, boolean prefix, Object object,
                                  @Variadic Object[] keys,
                                  @Cached(value = "site.create()", uncached = "site.uncached()",
                                          neverDefault = true) PropNode access,
                                  @Bind Node node, @Cached ElementNodes.ArrayKeyNode toKey,
                                  @Cached ElementNodes.ElementReadNode read,
                                  @Cached ElementNodes.ElementWriteNode write,
                                  @Cached InlinedBranchProfile objectWrite) {
            String name = placeName(site, keys);
            keys = placeKeys(site, keys);
            Object base = propertyForWrite(frame, access, object, name);
            int last = keys.length - 1;
            Object container = ElementChain.forUpdate(node, base, keys, last, toKey);
            if (container instanceof PhpObject) {
                return stepOverloaded(node, container, keys[last], increment, prefix);
            }
            Object old = ElementChain.readForUpdate(read, container, keys[last]);
            Object updated = AssignNodes.IncDecNode.stepped(node, old, increment);
            ElementChain.write(node, container, keys[last], updated, write, objectWrite);
            return prefix ? updated : old;
        }
    }

    /**
     * `unset($x[k1]...[kn])` (DimNode.unset): the containers are read down to the last; when it is an array that
     * has the element, the chain is made writable from the variable and the element removed; an ArrayAccess
     * object's offsetUnset(); nothing for anything else.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    public static final class UnsetNested {
        @Specialization
        public static void unset(VirtualFrame frame, LocalAccessor variable, @Variadic Object[] keys,
                                 @Bind BytecodeNode bytecode, @Bind Node node,
                                 @Cached ElementNodes.ArrayKeyNode toKey,
                                 @Cached ElementNodes.ElementIfSetNode read,
                                 @Cached InlinedBranchProfile sharedArray,
                                 @Cached InlinedBranchProfile refArray,
                                 @Cached InlinedBranchProfile createdArray) {
            Object container = ElementChain.read(Variables.value(Locals.stored(frame, variable, bytecode)), keys,
                    read);
            Object key = ElementChain.removed(node, container, keys, toKey);
            if (key != null) {
                Object base = variableForWrite(frame, variable, bytecode, node, sharedArray, refArray,
                        createdArray);
                ElementChain.remove(node, base, keys, key, toKey);
            }
        }
    }

    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class UnsetPropNested {
        @Specialization
        public static void unset(VirtualFrame frame, PropSite site, Object object, @Variadic Object[] keys,
                                 @Cached(value = "site.create()", uncached = "site.uncached()",
                                         neverDefault = true) PropNode access,
                                 @Bind Node node, @Cached ElementNodes.ArrayKeyNode toKey,
                                 @Cached ElementNodes.ElementIfSetNode read) {
            String name = placeName(site, keys);
            keys = placeKeys(site, keys);
            Object container = ElementChain.read(access.readForUnsetOn(frame.getArguments(), object, name), keys, read);
            Object key = ElementChain.removed(node, container, keys, toKey);
            if (key != null) {
                ElementChain.remove(node, propertyForWrite(frame, access, object, name), keys, key, toKey);
            }
        }
    }

    /**
     * `Cls::$name[k1]...[kn] = value`, `$_POST[k]... = value`, `$GLOBALS['g'][k]... = value` (and append): as
     * WriteNested, from the reference the place is kept in (StaticPropNode, GlobalVarNode, GlobalsDimNode
     * arrayForWrite: Places.refArrayForWrite), which is found after the value.
     */
    @Operation
    @ConstantOperand(type = boolean.class, name = "append")
    public static final class WriteRefNested {
        @Specialization
        public static Object write(boolean append, Object[] fetched, @Variadic Object[] keys,
                                   @Bind Node node, @Cached ElementNodes.ArrayKeyNode toKey,
                                   @Cached ElementNodes.ElementWriteNode write,
                                   @Cached InlinedBranchProfile objectWrite) {
            Object value = fetched[0];
            // held since the containers were made writable, so `$a[k] = $a` copies $a first (DimNode.write)
            PhpArray held = value instanceof PhpArray array ? array : null;
            try {
                int levels = append ? keys.length : keys.length - 1;
                Object container = ElementChain.forWrite(node, fetched[1], keys, levels, toKey);
                ElementChain.write(node, container, append ? ElementChain.APPEND : keys[levels], value, write,
                        objectWrite);
            } catch (Throwable t) {
                if (held != null) {
                    DimNode.letGo(held);
                }
                throw t;
            }
            if (held != null) {
                held.removeHolder();
            }
            return value;
        }
    }

    /** `Cls::$name[k1]...[kn] op= value`, and of a global's element: as CompoundNested, from the reference. */
    @Operation
    @ConstantOperand(type = int.class, name = "op")
    public static final class CompoundRefNested {
        @Specialization
        public static Object compound(int op, Object[] fetched, @Variadic Object[] keys,
                                      @Bind Node node, @Cached ElementNodes.ArrayKeyNode toKey,
                                      @Cached ElementNodes.ElementReadNode read,
                                      @Cached ElementNodes.ElementWriteNode write,
                                      @Cached InlinedBranchProfile objectWrite) {
            Object operand = fetched[0];
            PhpArray held = operand instanceof PhpArray array ? array : null;
            try {
                int last = keys.length - 1;
                Object container = ElementChain.forUpdate(node, fetched[1], keys, last, toKey);
                Object result = compounded(node, op, ElementChain.readForUpdate(read, container, keys[last]),
                        operand);
                ElementChain.write(node, container, keys[last], result, write, objectWrite);
                return result;
            } finally {
                if (held != null) {
                    DimNode.letGo(held);
                }
            }
        }
    }

    /**
     * The value of a write into a place kept in a reference (WriteRefNested, CompoundRefNested; null for an
     * increment), and the place's container made writable, which the write fetches before the keys it reads where it
     * runs (ZEND_ASSIGN_DIM's container, then its CV key: an error handler the key's warning calls doesn't change
     * which array is written). The value's array is held from here to the write, so the container is copied from it.
     */
    @Operation
    public static final class FetchForWrite {
        @Specialization
        public static Object[] fetch(Object value, PhpRef ref, @Bind Node node,
                                     @Cached InlinedBranchProfile arrayWrite) {
            if (value instanceof PhpArray array) {
                arrayWrite.enter(node);
                array.addHolder();
                try {
                    return new Object[] {value, referenceForWrite(ref)};
                } catch (Throwable t) {
                    DimNode.letGo(array);
                    throw t;
                }
            }
            return new Object[] {value, referenceForWrite(ref)};
        }
    }

    /** `++Cls::$name[k1]...[kn]`... and of a global's element: as IncDecNested, from the reference. */
    @Operation
    @ConstantOperand(type = boolean.class, name = "increment")
    @ConstantOperand(type = boolean.class, name = "prefix")
    public static final class IncDecRefNested {
        @Specialization
        public static Object step(boolean increment, boolean prefix, Object[] fetched, @Variadic Object[] keys,
                                  @Bind Node node, @Cached ElementNodes.ArrayKeyNode toKey,
                                  @Cached ElementNodes.ElementReadNode read,
                                  @Cached ElementNodes.ElementWriteNode write,
                                  @Cached InlinedBranchProfile objectWrite) {
            int last = keys.length - 1;
            Object container = ElementChain.forUpdate(node, fetched[1], keys, last, toKey);
            if (container instanceof PhpObject) {
                return stepOverloaded(node, container, keys[last], increment, prefix);
            }
            Object old = ElementChain.readForUpdate(read, container, keys[last]);
            Object updated = AssignNodes.IncDecNode.stepped(node, old, increment);
            ElementChain.write(node, container, keys[last], updated, write, objectWrite);
            return prefix ? updated : old;
        }
    }

    /**
     * `unset(Cls::$name[k1]...[kn])`, `unset($_GET[k]...)` (DimNode.unset): as UnsetNested, from the reference;
     * a global that doesn't exist (null for the reference) has nothing to unset, and isn't created.
     */
    @Operation
    public static final class UnsetRefNested {
        @Specialization
        public static void unset(Object ref, @Variadic Object[] keys, @Bind Node node,
                                 @Cached ElementNodes.ArrayKeyNode toKey,
                                 @Cached ElementNodes.ElementIfSetNode read) {
            Object base = ref instanceof PhpRef found ? found.get() : PhpNull.NULL;
            Object key = ElementChain.removed(node, ElementChain.read(base, keys, read), keys, toKey);
            if (key != null) {
                ElementChain.remove(node, referenceForWrite((PhpRef) ref), keys, key, toKey);
            }
        }
    }

    /**
     * An element's value combined with a compound assignment's operand (CompoundAssignNode): `.=` converts the
     * element first, then the operand.
     */
    private static Object compounded(Node node, int op, Object current, Object operand) {
        if (op == AssignNodes.CompoundAssignNode.CONCAT) {
            return concatenated(node, current, operand);
        }
        return AssignNodes.CompoundAssignNode.apply(node, op, current, operand);
    }

    private static TruffleString concatenated(Node node, Object current, Object operand) {
        TruffleString left = Convert.toStr(node, current);
        return Str.concat(left, Convert.toStr(node, operand));
    }

    /** The key of an `[]` on the way to a nested element (`$a[][k] = v`), as the translator emits it. */
    public static final Object APPEND = ElementChain.APPEND;

    /** A variable as the base of an element chain written: its writable array, else the object it holds. */
    private static Object variableForWrite(VirtualFrame frame, LocalAccessor variable, BytecodeNode bytecode,
                                           Node node, InlinedBranchProfile sharedArray,
                                           InlinedBranchProfile refArray, InlinedBranchProfile createdArray) {
        PhpArray array = Locals.arrayForWrite(frame, variable, bytecode, node, sharedArray, refArray, createdArray);
        return array != null ? array : Variables.value(Locals.stored(frame, variable, bytecode));
    }

    /**
     * variableForWrite for a read-modify-write of an element (BP_VAR_RW: `$x[k] .= `, `$x[k]++`): an unset variable
     * warns first, and php-src makes it the array before an exception the error handler threw leaves.
     */
    private static Object variableForUpdate(VirtualFrame frame, LocalAccessor variable, BytecodeNode bytecode,
                                            Node node, InlinedBranchProfile sharedArray,
                                            InlinedBranchProfile refArray, InlinedBranchProfile createdArray) {
        PhpThrowable pending = null;
        if (variable.isCleared(bytecode, frame)) {
            // the variable becomes an array (createdArray's case)
            createdArray.enter(node);
            pending = undefinedForUpdate(node, variable, bytecode);
        }
        Object base = variableForWrite(frame, variable, bytecode, node, sharedArray, refArray, createdArray);
        if (pending != null) {
            throw pending;
        }
        return base;
    }

    /** A nested property place's name: the site's, else computed, the first of the keys (PropName). */
    private static String placeName(PropSite site, Object[] keys) {
        return site.name != null ? site.name : (String) keys[0];
    }

    /** A nested property place's keys after its name (placeName). */
    private static Object[] placeKeys(PropSite site, Object[] keys) {
        return site.name != null ? keys : Arrays.copyOfRange(keys, 1, keys.length);
    }

    /** A property as the base of an element chain written (PropNode.arrayForWrite), else the object it holds. */
    private static Object propertyForWrite(VirtualFrame frame, PropNode access, Object object, String name) {
        return access.containerForWriteOn(frame.getArguments(), object, name);
    }

    /**
     * A place kept in a reference as the base of an element chain written (Places.refArrayForWrite: an array made
     * exclusive, created for null or false; an error for another scalar), else the object it holds.
     */
    private static Object referenceForWrite(PhpRef ref) {
        PhpArray array = Places.refArrayForWrite(ref);
        return array != null ? array : ref.get();
    }

    // ---- $this and properties (ThisNode, PropNode) ----

    /** `$this`: outside an object's method an Error. */
    @Operation
    public static final class ReadThis {
        @Specialization
        public static Object read(VirtualFrame frame) {
            return ThisNode.self(frame);
        }
    }

    @Operation
    public static final class IssetThis {
        @Specialization
        public static boolean isSet(VirtualFrame frame) {
            return ThisNode.IsSetNode.isSet(frame);
        }
    }

    /** `$obj->$name`'s name, converted once it is evaluated (PropNode.dynamicName). */
    @Operation
    public static final class PropName {
        @Specialization
        public static String name(Object value) {
            return PropNode.dynamicName(value);
        }
    }

    /** `$obj->name` (PropNode.execute): the object, then the property; null for a value that isn't an object. */
    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class ReadProp {
        @Specialization
        public static Object read(VirtualFrame frame, PropSite site, Object object,
                                  @Cached(value = "site.create()", uncached = "site.uncached()",
                                          neverDefault = true) PropNode access) {
            return access.read(frame.getArguments(), object, site.name);
        }
    }

    /**
     * `$obj->name` as the object of a write to its properties or elements, rw for a compound assignment
     * (PropNode.readForWriteOn).
     */
    @Operation
    @ConstantOperand(type = PropSite.class)
    @ConstantOperand(type = boolean.class)
    public static final class ReadPropForWrite {
        @Specialization
        public static Object read(VirtualFrame frame, PropSite site, boolean rw, Object object,
                                  @Cached(value = "site.create()", uncached = "site.uncached()",
                                          neverDefault = true) PropNode access) {
            return access.readForWriteOn(frame.getArguments(), object, site.name, rw);
        }
    }

    /** The object of `->` or `[]` in a parameter's default (ConstantNodes.ConstantObjectNode.check). */
    @Operation
    @ConstantOperand(type = boolean.class)
    public static final class ConstantObject {
        @Specialization
        public static Object check(boolean element, Object value) {
            return ConstantNodes.ConstantObjectNode.check(value, element);
        }
    }

    /** `$obj->name` as the object of an unset() of its properties or elements (PropNode.readForUnsetOn). */
    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class ReadPropForUnset {
        @Specialization
        public static Object read(VirtualFrame frame, PropSite site, Object object,
                                  @Cached(value = "site.create()", uncached = "site.uncached()",
                                          neverDefault = true) PropNode access) {
            return access.readForUnsetOn(frame.getArguments(), object, site.name);
        }
    }

    /** `$obj->$name`: the object, then the name (PropName). */
    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class ReadPropNamed {
        @Specialization
        public static Object read(VirtualFrame frame, PropSite site, Object object, String name,
                                  @Cached(value = "site.create()", uncached = "site.uncached()",
                                          neverDefault = true) PropNode access) {
            return access.read(frame.getArguments(), object, name);
        }
    }

    /**
     * `$obj->name = value` (AssignNode on a PropNode): the value is evaluated first, then the object, which must be
     * one; the value is the result.
     */
    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class WriteProp {
        @Specialization
        public static Object write(VirtualFrame frame, PropSite site, Object value, Object object,
                                   @Cached(value = "site.create()", uncached = "site.uncached()",
                                           neverDefault = true) PropNode access) {
            return access.writeOn(frame.getArguments(), object, site.name, value);
        }
    }

    /** `$obj->$name = value`: the value, the name, then the object. */
    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class WritePropNamed {
        @Specialization
        public static Object write(VirtualFrame frame, PropSite site, Object value, String name, Object object,
                                   @Cached(value = "site.create()", uncached = "site.uncached()",
                                           neverDefault = true) PropNode access) {
            return access.writeOn(frame.getArguments(), object, name, value);
        }
    }

    /**
     * `$obj->name op= value` (ZEND_ASSIGN_OBJ_OP): the value, then the object, evaluated once; the property is read
     * and written through the tree's rules (__get then __set for one that doesn't exist or the scope may not
     * access), `.=` converting the property's value first, then the operand.
     */
    @Operation
    @ConstantOperand(type = PropSite.class)
    @ConstantOperand(type = int.class, name = "op")
    public static final class CompoundProp {
        @Specialization(guards = "op == 8")
        public static Object concat(VirtualFrame frame, PropSite site, int op, Object operand, Object object,
                                    @Cached(value = "site.create()", uncached = "site.uncached()",
                                            neverDefault = true) PropNode access,
                                    @Cached ToStrNode currentToStr, @Cached ToStrNode operandToStr,
                                    @Cached TruffleString.ConcatNode concat) {
            return concatOn(frame, access, site.name, operand, object, currentToStr, operandToStr, concat);
        }

        @Specialization(guards = "op != 8")
        public static Object generic(VirtualFrame frame, PropSite site, int op, Object operand, Object object,
                                     @Cached(value = "site.create()", uncached = "site.uncached()",
                                             neverDefault = true) PropNode access, @Bind Node node) {
            return compoundOn(frame, access, site.name, op, operand, object, node);
        }

        static Object concatOn(VirtualFrame frame, PropNode access, String name, Object operand, Object object,
                               ToStrNode currentToStr, ToStrNode operandToStr, TruffleString.ConcatNode concat) {
            PhpObject target = propertyTarget(object, name, "assign");
            TruffleString left = currentToStr.execute(access.readOn(frame.getArguments(), target, name));
            TruffleString result = concat.execute(left, operandToStr.execute(operand), Str.ENC, true);
            return access.writeOn(frame.getArguments(), target, name, result);
        }

        static Object compoundOn(VirtualFrame frame, PropNode access, String name, int op, Object operand,
                                 Object object, Node node) {
            PhpObject target = propertyTarget(object, name, "assign");
            Object result = AssignNodes.CompoundAssignNode.apply(node, op, access.readOn(frame.getArguments(), target,
                    name), operand);
            return access.writeOn(frame.getArguments(), target, name, result);
        }
    }

    /** `$obj->$name op= value`: as CompoundProp, the name computed (PropName). */
    @Operation
    @ConstantOperand(type = PropSite.class)
    @ConstantOperand(type = int.class, name = "op")
    public static final class CompoundPropNamed {
        @Specialization(guards = "op == 8")
        public static Object concat(VirtualFrame frame, PropSite site, int op, Object operand, Object object,
                                    String name,
                                    @Cached(value = "site.create()", uncached = "site.uncached()",
                                            neverDefault = true) PropNode access,
                                    @Cached ToStrNode currentToStr, @Cached ToStrNode operandToStr,
                                    @Cached TruffleString.ConcatNode concat) {
            return CompoundProp.concatOn(frame, access, name, operand, object, currentToStr, operandToStr, concat);
        }

        @Specialization(guards = "op != 8")
        public static Object generic(VirtualFrame frame, PropSite site, int op, Object operand, Object object,
                                     String name,
                                     @Cached(value = "site.create()", uncached = "site.uncached()",
                                             neverDefault = true) PropNode access, @Bind Node node) {
            return CompoundProp.compoundOn(frame, access, name, op, operand, object, node);
        }
    }

    /**
     * `++$obj->name`, `$obj->name--`... (ZEND_PRE_INC_OBJ...): the object evaluated once, the property read,
     * stepped and written.
     */
    @Operation
    @ConstantOperand(type = PropSite.class)
    @ConstantOperand(type = boolean.class, name = "increment")
    @ConstantOperand(type = boolean.class, name = "prefix")
    public static final class IncDecProp {
        @Specialization
        public static Object step(VirtualFrame frame, PropSite site, boolean increment, boolean prefix, Object object,
                                  @Cached(value = "site.create()", uncached = "site.uncached()",
                                          neverDefault = true) PropNode access, @Bind Node node) {
            return stepOn(frame, access, site.name, increment, prefix, object, node);
        }

        static Object stepOn(VirtualFrame frame, PropNode access, String name, boolean increment, boolean prefix,
                             Object object, Node node) {
            PhpObject target = propertyTarget(object, name, "increment/decrement");
            Object old = access.readOn(frame.getArguments(), target, name);
            Object updated;
            try {
                updated = AssignNodes.IncDecNode.stepped(node, old, increment);
            } catch (PhpError e) {
                // zend_pre_incdec_overloaded_property writes the value unchanged: a readonly property's error wins
                if (target.isReadonly(name)) {
                    access.writeOn(frame.getArguments(), target, name, old);
                }
                throw e;
            }
            if (old instanceof Long && updated instanceof Double) {
                PropNode.incDecOverflow(target, name, increment);
            }
            updated = access.writeOn(frame.getArguments(), target, name, updated);
            return prefix ? updated : old;
        }
    }

    /** `++$obj->$name`...: as IncDecProp, the name computed (PropName). */
    @Operation
    @ConstantOperand(type = PropSite.class)
    @ConstantOperand(type = boolean.class, name = "increment")
    @ConstantOperand(type = boolean.class, name = "prefix")
    public static final class IncDecPropNamed {
        @Specialization
        public static Object step(VirtualFrame frame, PropSite site, boolean increment, boolean prefix, Object object,
                                  String name,
                                  @Cached(value = "site.create()", uncached = "site.uncached()",
                                          neverDefault = true) PropNode access, @Bind Node node) {
            return IncDecProp.stepOn(frame, access, name, increment, prefix, object, node);
        }
    }

    /** The object whose property a compound write changes; a value that isn't one is php-src's error. */
    private static PhpObject propertyTarget(Object object, String name, String action) {
        if (object instanceof PhpObject target) {
            return target;
        }
        throw PropNode.notAnObject(action, name, object);
    }

    /** `unset($obj->name)` (PropNode.unset): nothing for a value that isn't an object. */
    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class UnsetProp {
        @Specialization
        public static void unset(VirtualFrame frame, PropSite site, Object object,
                                 @Cached(value = "site.create()", uncached = "site.uncached()",
                                         neverDefault = true) PropNode access) {
            access.unsetOn(frame.getArguments(), object, site.name);
        }
    }

    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class UnsetPropNamed {
        @Specialization
        public static void unset(VirtualFrame frame, PropSite site, Object object, String name,
                                 @Cached(value = "site.create()", uncached = "site.uncached()",
                                         neverDefault = true) PropNode access) {
            access.unsetOn(frame.getArguments(), object, name);
        }
    }

    /** `isset($obj->name)` (PropNode.isSet), the object as isset reads it (ReadPropIfSet for a property). */
    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class IssetProp {
        @Specialization
        public static boolean isSet(VirtualFrame frame, PropSite site, Object object,
                                    @Cached(value = "site.create()", uncached = "site.uncached()",
                                            neverDefault = true) PropNode access) {
            return access.isSetOn(frame.getArguments(), object, site.name);
        }
    }

    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class IssetPropNamed {
        @Specialization
        public static boolean isSet(VirtualFrame frame, PropSite site, Object object, String name,
                                    @Cached(value = "site.create()", uncached = "site.uncached()",
                                            neverDefault = true) PropNode access) {
            return access.isSetOn(frame.getArguments(), object, name);
        }
    }

    /**
     * A property as the object of an isset() (PropNode.readIfSet): __isset decides for one that doesn't exist, then
     * __get gives it; null when unset or null.
     */
    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class ReadPropIfSet {
        @Specialization
        public static Object read(VirtualFrame frame, PropSite site, Object object,
                                  @Cached(value = "site.create()", uncached = "site.uncached()",
                                          neverDefault = true) PropNode access) {
            Object value = access.readIfSetOn(frame.getArguments(), object, site.name);
            return value == null ? PhpNull.NULL : value;
        }
    }

    /** ReadPropIfSet by a computed name, evaluated after the object. */
    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class ReadPropIfSetNamed {
        @Specialization
        public static Object read(VirtualFrame frame, PropSite site, Object object, String name,
                                  @Cached(value = "site.create()", uncached = "site.uncached()",
                                          neverDefault = true) PropNode access) {
            Object value = access.readIfSetOn(frame.getArguments(), object, name);
            return value == null ? PhpNull.NULL : value;
        }
    }

    /**
     * `$obj->name[key] = value` (DimNode.write on a PropNode): the value is held while the property's array is made
     * writable (PropNode.arrayForWrite); a property holding an object takes the write as an ArrayAccess.
     */
    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class WritePropElement {
        @Specialization
        public static Object write(VirtualFrame frame, PropSite site, Object value, Object object, Object key,
                                   @Cached(value = "site.create()", uncached = "site.uncached()",
                                           neverDefault = true) PropNode access,
                                   @Cached ElementNodes.ElementWriteNode write, @Bind Node node) {
            return writePropertyElement(frame, access, object, site.name, key, value, write, node);
        }
    }

    /** `$obj->$name[key] = value`: as WritePropElement, the name computed (PropName) before the value. */
    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class WritePropElementNamed {
        @Specialization
        public static Object write(VirtualFrame frame, PropSite site, Object value, String name, Object object,
                                   Object key,
                                   @Cached(value = "site.create()", uncached = "site.uncached()",
                                           neverDefault = true) PropNode access,
                                   @Cached ElementNodes.ElementWriteNode write, @Bind Node node) {
            return writePropertyElement(frame, access, object, name, key, value, write, node);
        }
    }

    private static Object writePropertyElement(VirtualFrame frame, PropNode access, Object object, String name,
                                               Object key, Object value, ElementNodes.ElementWriteNode write,
                                               Node node) {
        PhpArray held = value instanceof PhpArray array ? array : null;
        if (held != null) {
            held.addHolder();
        }
        try {
            PhpArray array = access.arrayForWriteOn(frame.getArguments(), object, name);
            if (array == null) {
                DimNode.writeToObject(node, access.readOn(frame.getArguments(), object, name), key, value);
            } else {
                write.execute(array, key, value);
            }
        } finally {
            if (held != null) {
                held.removeHolder();
            }
        }
        return value;
    }

    /** `$obj->name[] = value`. */
    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class AppendPropElement {
        @Specialization
        public static Object append(VirtualFrame frame, PropSite site, Object value, Object object,
                                    @Cached(value = "site.create()", uncached = "site.uncached()",
                                            neverDefault = true) PropNode access, @Bind Node node) {
            return appendToProperty(frame, access, object, site.name, value, node);
        }
    }

    // ---- static properties (StaticPropNode) and the places kept in a reference ----

    /**
     * `Cls::$name` read (StaticPropNode.execute): the class's reference for the property, which the cached
     * interpreter's lookup keeps per class (StaticPropNode.Lookup); an Error when the class has none.
     */
    @Operation
    @ConstantOperand(type = StaticPropSite.class)
    public static final class ReadStaticProp {
        @Specialization(excludeForUncached = true)
        public static Object cached(VirtualFrame frame, StaticPropSite site, PhpClass phpClass,
                                    @Cached(value = "site.lookup()", neverDefault = true) StaticPropNode.Lookup lookup) {
            return StaticPropNode.declared(lookup.execute(frame, phpClass, false), phpClass, site.name).get();
        }

        @Specialization(replaces = "cached")
        public static Object uncached(VirtualFrame frame, StaticPropSite site, PhpClass phpClass) {
            return StaticPropNode.declared(site.find(frame, phpClass, false), phpClass, site.name).get();
        }
    }

    /** `Cls::$name` as a place written, by reference, or a container (StaticPropNode.makeRef): its reference. */
    @Operation
    @ConstantOperand(type = StaticPropSite.class)
    public static final class StaticPropRef {
        @Specialization(excludeForUncached = true)
        public static PhpRef cached(VirtualFrame frame, StaticPropSite site, PhpClass phpClass,
                                    @Cached(value = "site.lookup()", neverDefault = true) StaticPropNode.Lookup lookup) {
            return StaticPropNode.declared(lookup.execute(frame, phpClass, false), phpClass, site.name);
        }

        @Specialization(replaces = "cached")
        public static PhpRef uncached(VirtualFrame frame, StaticPropSite site, PhpClass phpClass) {
            return StaticPropNode.declared(site.find(frame, phpClass, false), phpClass, site.name);
        }
    }

    /**
     * `Cls::$name` as isset(), empty() and `??` read it (StaticPropNode.isSet, readIfSet): its value, null for a
     * property the class doesn't have or the calling scope may not access.
     */
    @Operation
    @ConstantOperand(type = StaticPropSite.class)
    public static final class StaticPropIfSet {
        @Specialization(excludeForUncached = true)
        public static Object cached(VirtualFrame frame, StaticPropSite site, PhpClass phpClass,
                                    @Cached(value = "site.lookup()", neverDefault = true) StaticPropNode.Lookup lookup) {
            PhpRef ref = lookup.execute(frame, phpClass, true);
            return ref == null ? PhpNull.NULL : ref.get();
        }

        @Specialization(replaces = "cached")
        public static Object uncached(VirtualFrame frame, StaticPropSite site, PhpClass phpClass) {
            PhpRef ref = site.find(frame, phpClass, true);
            return ref == null ? PhpNull.NULL : ref.get();
        }
    }

    /** `unset(Cls::$name)` (ZEND_UNSET_STATIC_PROP): the class, then the Error. */
    @Operation
    @ConstantOperand(type = StaticPropSite.class)
    public static final class UnsetStaticProp {
        @Specialization
        public static void unset(StaticPropSite site, PhpClass phpClass) {
            throw StaticPropNode.unsetError(phpClass, site.name);
        }
    }

    /** `Cls::$name = &place` (StaticPropNode.bindRef): the reference made, then the class's property bound to it. */
    @Operation
    @ConstantOperand(type = StaticPropSite.class)
    public static final class BindStaticProp {
        @Specialization
        public static Object bind(StaticPropSite site, PhpRef ref, PhpClass phpClass, @Bind Node node) {
            StaticPropNode.bind(node, phpClass, site.name, ref);
            return ref.get();
        }
    }

    /**
     * `Cls::$$name`, `Cls::${expr}` (ZEND_FETCH_STATIC_PROP_* of a computed name): the class, then the name, then as
     * ReadStaticProp (mode R), StaticPropRef (W), StaticPropIfSet (IS) or UnsetStaticProp (UNSET) by that name.
     */
    @Operation
    @ConstantOperand(type = StaticPropSite.class)
    @ConstantOperand(type = int.class, name = "mode")
    public static final class StaticPropNamed {
        @Specialization
        public static Object fetch(VirtualFrame frame, StaticPropSite site, int mode, PhpClass phpClass, String name) {
            if (mode == VariableVariableNode.UNSET) {
                throw StaticPropNode.unsetError(phpClass, name);
            }
            PhpRef ref = StaticPropNode.checked(frame, phpClass, phpClass.getStaticProperty(name), name,
                    site.lexicalClass, site.scopeInFrame, mode == VariableVariableNode.IS);
            if (mode == VariableVariableNode.IS) {
                return ref == null ? PhpNull.NULL : ref.get();
            }
            ref = StaticPropNode.declared(ref, phpClass, name);
            return mode == VariableVariableNode.W ? ref : ref.get();
        }
    }

    /** `Cls::$$name = &place`: as BindStaticProp, the property named at run time. */
    @Operation
    public static final class BindStaticPropNamed {
        @Specialization
        public static Object bind(PhpRef ref, PhpClass phpClass, String name, @Bind Node node) {
            StaticPropNode.bind(node, phpClass, name, ref);
            return ref.get();
        }
    }

    /**
     * `place = value` for a place kept in a reference (a static property, a global: StaticPropNode.write,
     * GlobalVarNode.write): the value, then the reference, which takes it (RefWrites, PhpRef.set's holders).
     */
    @Operation
    public static final class WriteRef {
        @Specialization
        public static Object write(Object value, PhpRef ref,
                                   @Cached(value = "refWrites()", uncached = "uncachedRefWrites()",
                                           neverDefault = true) RefWrites writes) {
            writes.set(ref, value);
            return value;
        }

        public static RefWrites refWrites() {
            return RefWrites.create();
        }

        public static RefWrites uncachedRefWrites() {
            return UNCACHED_REF_WRITES;
        }
    }

    /** `place op= value` for a place kept in a reference (CompoundAssignNode): the value first, then the place. */
    @Operation
    @ConstantOperand(type = int.class, name = "op")
    public static final class CompoundRef {
        @Specialization
        public static Object compound(int op, Object operand, PhpRef ref, @Bind Node node,
                                      @Cached(value = "refWrites()", uncached = "uncachedRefWrites()",
                                              neverDefault = true) RefWrites writes) {
            Object result = compounded(node, op, ref.get(), operand);
            writes.set(ref, result);
            return result;
        }

        public static RefWrites refWrites() {
            return RefWrites.create();
        }

        public static RefWrites uncachedRefWrites() {
            return UNCACHED_REF_WRITES;
        }
    }

    /** `++place`, `place--`... for a place kept in a reference (IncDecNode). */
    @Operation
    @ConstantOperand(type = boolean.class, name = "increment")
    @ConstantOperand(type = boolean.class, name = "prefix")
    public static final class IncDecRef {
        @Specialization
        public static Object step(boolean increment, boolean prefix, PhpRef ref, @Bind Node node,
                                  @Cached(value = "refWrites()", uncached = "uncachedRefWrites()",
                                          neverDefault = true) RefWrites writes) {
            Object old = ref.get();
            Object updated = AssignNodes.IncDecNode.stepped(node, old, increment);
            writes.set(ref, updated);
            return prefix ? updated : old;
        }

        public static RefWrites refWrites() {
            return RefWrites.create();
        }

        public static RefWrites uncachedRefWrites() {
            return UNCACHED_REF_WRITES;
        }
    }

    /** The uncached interpreter's profile of writes through a reference, which it never compiles: one for all. */
    private static final RefWrites UNCACHED_REF_WRITES = RefWrites.create();

    /** `$obj->$name[] = value`: as AppendPropElement, the name computed (PropName) before the value. */
    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class AppendPropElementNamed {
        @Specialization
        public static Object append(VirtualFrame frame, PropSite site, Object value, String name, Object object,
                                    @Cached(value = "site.create()", uncached = "site.uncached()",
                                            neverDefault = true) PropNode access, @Bind Node node) {
            return appendToProperty(frame, access, object, name, value, node);
        }
    }

    private static Object appendToProperty(VirtualFrame frame, PropNode access, Object object, String name,
                                           Object value, Node node) {
        PhpArray held = value instanceof PhpArray array ? array : null;
        if (held != null) {
            held.addHolder();
        }
        try {
            PhpArray array = access.arrayForWriteOn(frame.getArguments(), object, name);
            if (array == null) {
                DimNode.writeToObject(node, access.readOn(frame.getArguments(), object, name), PhpNull.NULL, value);
            } else {
                array.append(value);
            }
        } finally {
            if (held != null) {
                held.removeHolder();
            }
        }
        return value;
    }

    /**
     * An array literal (ArrayLiteralNode): LiteralStart makes the array, each LiteralAppend or LiteralPut inserts
     * an element as soon as it is evaluated (an array value gains the literal as a holder before the next element
     * runs, which may write the variable it came from), and LiteralEnd hands the array on. `[v1, v2]` is
     * LiteralEnd(LiteralAppend(LiteralAppend(LiteralStart, v1), v2)).
     */
    @Operation
    @ConstantOperand(type = LiteralSite.class)
    public static final class LiteralStart {
        @Specialization
        public static PhpArray start(LiteralSite site) {
            return site.newArray();
        }
    }

    /** An element without a key: appended. */
    @Operation
    public static final class LiteralAppend {
        @Specialization
        public static PhpArray append(PhpArray array, Object value) {
            array.append(value);
            return array;
        }
    }

    /** An element with a key, evaluated before its value. */
    @Operation
    public static final class LiteralPut {
        @Specialization
        public static PhpArray put(PhpArray array, Object key, Object value) {
            array.put(ArrayKey.of(key), value);
            return array;
        }
    }

    /** An element with an int or string literal key, normalized by the translator. */
    @Operation
    @ConstantOperand(type = Object.class)
    public static final class LiteralPutKey {
        @Specialization
        public static PhpArray put(Object key, PhpArray array, Object value) {
            array.put(key, value);
            return array;
        }
    }

    /** An element of a record (LiteralSite.newArray's hash): a key no other element has, and its hash. */
    @Operation
    @ConstantOperand(type = Object.class)
    @ConstantOperand(type = int.class)
    public static final class LiteralAdd {
        @Specialization
        public static PhpArray add(Object key, int hash, PhpArray array, Object value) {
            array.addEntry(key, hash, value);
            return array;
        }
    }

    /** `&$place` without a key: the reference at the next index. */
    @Operation
    public static final class LiteralAppendRef {
        @Specialization
        public static PhpArray append(PhpArray array, PhpRef ref) {
            array.putRef(array.nextIndex(), ref);
            return array;
        }
    }

    /** `key => &$place`: the key, then the reference. */
    @Operation
    public static final class LiteralPutRef {
        @Specialization
        public static PhpArray put(PhpArray array, Object key, PhpRef ref) {
            array.putRef(ArrayKey.of(key), ref);
            return array;
        }
    }

    /** `...$source` (ArrayLiteralNode.spread): an array's elements, int keys renumbered, string keys kept. */
    @Operation
    public static final class LiteralSpread {
        @Specialization
        public static PhpArray spread(PhpArray array, Object source, @Bind Node node) {
            ArrayLiteralNode.spread(array, source, node);
            return array;
        }
    }

    @Operation
    @ConstantOperand(type = LiteralSite.class)
    public static final class LiteralEnd {
        @Specialization
        public static PhpArray end(LiteralSite site, PhpArray array) {
            site.built(array);
            return array;
        }
    }

    // ---- list() (ListAssignNode) ----

    /**
     * An element of a list() assignment's value (FETCH_LIST_R): an array's element, null after "Undefined array key"
     * when it is missing; an ArrayAccess object's offsetGet(); null for anything else, after "Cannot use int as
     * array" for a scalar that isn't null (ListAssignNode).
     */
    @Operation
    public static final class ListElement {
        @Specialization
        public static Object array(PhpArray container, Object key,
                                   @Shared @Cached ElementNodes.ElementReadNode read) {
            return read.execute(container, key);
        }

        @Specialization
        public static Object object(PhpObject container, Object key,
                                    @Shared @Cached ElementNodes.ElementReadNode read) {
            return read.execute(container, key);
        }

        @Fallback
        public static Object other(Object container, Object key, @Bind Node node) {
            if (container != PhpNull.NULL) {
                ListAssignNode.notAnArray(node, container);
            }
            return PhpNull.NULL;
        }
    }

    /** A value held while a list() that writes the variable it came from is assigned (php-src's copy of it). */
    @Operation
    public static final class HoldValue {
        @Specialization
        public static Object hold(Object value) {
            if (value instanceof PhpArray array) {
                array.addHolder();
            }
            return value;
        }
    }

    @Operation
    public static final class ReleaseValue {
        @Specialization
        public static void release(Object value) {
            if (value instanceof PhpArray array) {
                array.removeHolder();
            }
        }
    }

    // ---- owning temporaries (wiki/destructors.md, P1): the result of `new` or a call holds a count ----

    /**
     * An owning temporary used where it isn't stored (an operand, an argument, a receiver): kept in a hidden local
     * until its statement ends (ReleaseTemp), or the call does (releaseVariables), as php-src's live ranges. A
     * temporary the local still holds from the statement's last run (a loop) is released first. owned: the
     * expression was a call or `new` (the translator knows only once it is emitted).
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = int.class, name = "index")
    @ConstantOperand(type = boolean.class, name = "owned", specifyAtEnd = true)
    public static final class StoreTemp {
        @Specialization
        public static Object store(VirtualFrame frame, LocalAccessor temporary, int index, Object value, boolean owned,
                                   @Bind PhpBytecodeRootNode root, @Bind BytecodeNode bytecode) {
            if (owned && value instanceof PhpObject object) {
                root.temporaryHeld(index);
                ReleaseTemp.release(frame, temporary, index, root, bytecode);
                temporary.setObject(bytecode, frame, object);
            }
            return value;
        }
    }

    /**
     * The end of a statement: the owning temporary its hidden local keeps is released. index: the local's place in
     * temporaries, whose bit says whether it has held one.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = int.class, name = "index")
    public static final class ReleaseTemp {
        @Specialization
        public static void release(VirtualFrame frame, LocalAccessor temporary, int index,
                                   @Bind PhpBytecodeRootNode root, @Bind BytecodeNode bytecode) {
            // cleared first: see releaseVariables
            if (root.temporariesHeld[index] && !temporary.isCleared(bytecode, frame)) {
                Object held = temporary.getObject(bytecode, frame);
                temporary.clear(bytecode, frame);
                Refcount.dec(held);
            }
        }
    }

    /** An owning temporary about to be stored: the store counts it, so its own count moves there (no event). */
    @Operation
    @ConstantOperand(type = boolean.class, name = "owned", specifyAtEnd = true)
    public static final class Disown {
        @Specialization
        public static Object disown(Object value, boolean owned) {
            if (owned && value instanceof PhpObject object) {
                object.disown();
            }
            return value;
        }
    }

    /**
     * A returned value that isn't an owning temporary (a variable, a property): it gains the count the caller's
     * temporary holds, before the epilogue releases the variables (php-src's RETURN copies a CV).
     */
    @Operation
    @ConstantOperand(type = boolean.class, name = "borrowed", specifyAtEnd = true)
    public static final class Own {
        @Specialization
        public static Object own(Object value, boolean borrowed) {
            if (borrowed && value instanceof PhpObject object) {
                object.addRef();
            }
            return value;
        }
    }

    /** An expression statement's value dropped: an owning temporary is released (php-src's FREE of a VAR). */
    @Operation
    @ConstantOperand(type = boolean.class, name = "owned", specifyAtEnd = true)
    public static final class ReleaseObject {
        @Specialization
        public static void release(Object value, boolean owned) {
            if (owned) {
                Refcount.dec(value);
            }
        }
    }

    /**
     * A call's result as its caller gets it: the value, owning its count (the callee's return counted it); a
     * reference a function returning by reference gave is read, and its object counted for the caller.
     */
    static Object callResult(Object returned) {
        if (returned instanceof PhpRef ref) {
            Object value = ref.get();
            Refcount.inc(value);
            return value;
        }
        return returned;
    }

    // ---- calls ----

    /** The function a call by name calls, looked up before its arguments as php-src's INIT_FCALL does. */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    public static final class ResolveFunction {
        /**
         * With one context, the function found first stays (functions can't be redeclared); with several, a
         * built-in (FunctionCallNode.Lookup). The uncached interpreter has no cache to keep it in: it looks the
         * function up each time (perContext).
         */
        @Specialization(assumptions = "singleContext()", excludeForUncached = true)
        public static PhpFunction single(CallInfo call, @Cached("call.find(context())") PhpFunction function) {
            return function;
        }

        @Specialization(guards = "builtin != null", replaces = "single", excludeForUncached = true)
        public static PhpFunction builtin(CallInfo call,
                                          @Cached("call.builtinOrNull(context())") PhpFunction builtin) {
            return builtin;
        }

        @Specialization(replaces = {"single", "builtin"})
        public static PhpFunction perContext(CallInfo call, @Bind Node node) {
            return call.find(PhpContext.get(node));
        }

        public static Assumption singleContext() {
            return PhpLanguage.get(null).singleContext();
        }

        public static PhpContext context() {
            return PhpContext.get(null);
        }
    }

    /**
     * Whether an unqualified call in a namespace finds the global function (CallInfo.fallsBack), which the
     * translator compiled in place, rather than the namespace's.
     */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    public static final class FallsBack {
        @Specialization
        public static boolean fallsBack(CallInfo call, @Bind Node node) {
            return call.fallsBack(PhpContext.get(node));
        }
    }

    /** A call: directly while the site sees one function (nearly always), else indirectly; the value it returns. */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    public static final class Call {
        @Specialization(guards = "function == cachedFunction", limit = "1")
        public static Object direct(CallInfo call, PhpFunction function,
                                    @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                    @Cached("function") PhpFunction cachedFunction,
                                    @Cached("create(cachedFunction.getCallTarget())") DirectCallNode callNode) {
            return callResult(callNode.call(arguments));
        }

        @Specialization(replaces = "direct")
        public static Object indirect(CallInfo call, PhpFunction function,
                                      @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                      @Cached IndirectCallNode callNode) {
            return callResult(callNode.call(function.getCallTarget(), arguments));
        }
    }

    /** A call whose result goes to a by-reference context: a reference when the callee returns one. */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    public static final class CallRef {
        @Specialization(guards = "function == cachedFunction", limit = "1")
        public static Object direct(CallInfo call, PhpFunction function,
                                    @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                    @Cached("function") PhpFunction cachedFunction,
                                    @Cached("create(cachedFunction.getCallTarget())") DirectCallNode callNode) {
            return callNode.call(arguments);
        }

        @Specialization(replaces = "direct")
        public static Object indirect(CallInfo call, PhpFunction function,
                                      @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                      @Cached IndirectCallNode callNode) {
            return callNode.call(function.getCallTarget(), arguments);
        }
    }

    /**
     * A variable as argument index (SEND_VAR_EX): its reference when the callee's parameter is by reference,
     * else its value.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = int.class)
    public static final class SendVar {
        /**
         * The callee the site sees (nearly always one): whether its parameter is by reference is a constant, as
         * the tree's direct call builds its arguments for the function it cached (FunctionCallNode.Dispatch).
         */
        @Specialization(guards = "function == cachedFunction", limit = "1", excludeForUncached = true)
        public static Object cached(VirtualFrame frame, LocalAccessor variable, int index, PhpFunction function,
                                    @Bind BytecodeNode bytecode, @Bind Node node,
                                    @Cached("function") PhpFunction cachedFunction,
                                    @Shared("undefined") @Cached InlinedBranchProfile undefined) {
            return send(frame, variable, index, cachedFunction, bytecode, node, undefined);
        }

        @Specialization(replaces = "cached")
        public static Object send(VirtualFrame frame, LocalAccessor variable, int index, PhpFunction function,
                                  @Bind BytecodeNode bytecode, @Bind Node node,
                                  @Shared("undefined") @Cached InlinedBranchProfile undefined) {
            if (function.isByRef(index)) {
                return Locals.makeRef(frame, variable, bytecode);
            }
            return readValue(frame, variable, bytecode, node, undefined);
        }
    }

    /**
     * An element of a variable as argument index: by reference (DimNode.makeRef: the element is created, null, if
     * missing) or its value.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = int.class)
    public static final class SendElement {
        /** The callee the site sees (nearly always one), a constant: see SendVar. */
        @Specialization(guards = "function == cachedFunction", limit = "1", excludeForUncached = true)
        public static Object cached(VirtualFrame frame, LocalAccessor variable, int index, PhpFunction function,
                                    Object key, @Bind BytecodeNode bytecode, @Bind Node node,
                                    @Shared @Cached ElementNodes.ElementReadNode read,
                                    @Shared @Cached ElementNodes.ArrayKeyNode toKey,
                                    @Shared @Cached InlinedBranchProfile undefined,
                                    @Shared @Cached InlinedBranchProfile byRef,
                                    @Shared @Cached InlinedBranchProfile sharedArray,
                                    @Shared @Cached InlinedBranchProfile refArray,
                                    @Shared @Cached InlinedBranchProfile createdArray,
                                    @Cached("function") PhpFunction cachedFunction) {
            return send(frame, variable, index, cachedFunction, key, bytecode, node, read, toKey, undefined, byRef,
                    sharedArray, refArray, createdArray);
        }

        @Specialization(replaces = "cached")
        public static Object send(VirtualFrame frame, LocalAccessor variable, int index, PhpFunction function,
                                  Object key, @Bind BytecodeNode bytecode, @Bind Node node,
                                  @Shared @Cached ElementNodes.ElementReadNode read,
                                  @Shared @Cached ElementNodes.ArrayKeyNode toKey,
                                  @Shared @Cached InlinedBranchProfile undefined,
                                  @Shared @Cached InlinedBranchProfile byRef,
                                  @Shared @Cached InlinedBranchProfile sharedArray,
                                  @Shared @Cached InlinedBranchProfile refArray,
                                  @Shared @Cached InlinedBranchProfile createdArray) {
            if (!function.isByRef(index)) {
                return read.execute(readValue(frame, variable, bytecode, node, undefined), key);
            }
            byRef.enter(node);
            PhpArray array = Locals.arrayForWrite(frame, variable, bytecode, node, sharedArray, refArray,
                    createdArray);
            if (array == null) {
                return DimNode.overloadedRef(node, Variables.value(Locals.stored(frame, variable, bytecode)), key);
            }
            return array.refAt(toKey.execute(key));
        }
    }

    /** A property of an object as argument index: its reference (PropNode.makeRef) or its value. */
    @Operation
    @ConstantOperand(type = PropSite.class)
    @ConstantOperand(type = int.class)
    public static final class SendProp {
        /** The callee the site sees (nearly always one), a constant: see SendVar. */
        @Specialization(guards = "function == cachedFunction", limit = "1", excludeForUncached = true)
        public static Object cached(VirtualFrame frame, PropSite site, int index, PhpFunction function, Object object,
                                    @Shared @Cached(value = "site.create()", uncached = "site.uncached()",
                                            neverDefault = true) PropNode access,
                                    @Cached("function") PhpFunction cachedFunction) {
            return send(frame, site, index, cachedFunction, object, access);
        }

        @Specialization(replaces = "cached")
        public static Object send(VirtualFrame frame, PropSite site, int index, PhpFunction function, Object object,
                                  @Shared @Cached(value = "site.create()", uncached = "site.uncached()",
                                          neverDefault = true) PropNode access) {
            if (function.isByRef(index)) {
                return access.makeRefOn(frame.getArguments(), object, site.name);
            }
            return access.read(frame.getArguments(), object, site.name);
        }
    }

    /** `$obj->$name` as argument index: as SendProp, the name computed (PropName). */
    @Operation
    @ConstantOperand(type = PropSite.class)
    @ConstantOperand(type = int.class)
    public static final class SendPropNamed {
        /** The callee the site sees (nearly always one), a constant: see SendVar. */
        @Specialization(guards = "function == cachedFunction", limit = "1", excludeForUncached = true)
        public static Object cached(VirtualFrame frame, PropSite site, int index, PhpFunction function, Object object,
                                    String name,
                                    @Shared @Cached(value = "site.create()", uncached = "site.uncached()",
                                            neverDefault = true) PropNode access,
                                    @Cached("function") PhpFunction cachedFunction) {
            return send(frame, site, index, cachedFunction, object, name, access);
        }

        @Specialization(replaces = "cached")
        public static Object send(VirtualFrame frame, PropSite site, int index, PhpFunction function, Object object,
                                  String name,
                                  @Shared @Cached(value = "site.create()", uncached = "site.uncached()",
                                          neverDefault = true) PropNode access) {
            if (function.isByRef(index)) {
                return access.makeRefOn(frame.getArguments(), object, name);
            }
            return access.read(frame.getArguments(), object, name);
        }
    }

    /**
     * A nested element `$x[k1]...[kn]` as argument index: its reference (RefNested) when the parameter is by
     * reference, else its value read down the chain (DimNode.execute). append: `$x[...][]`, whose reference is a
     * new element's; read, the tree's Error.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = int.class)
    @ConstantOperand(type = boolean.class, name = "append")
    public static final class SendNested {
        /** The callee the site sees (nearly always one), a constant: see SendVar. */
        @Specialization(guards = "function == cachedFunction", limit = "1", excludeForUncached = true)
        public static Object cached(VirtualFrame frame, LocalAccessor variable, int index, boolean append,
                                    PhpFunction function, @Variadic Object[] keys, @Bind BytecodeNode bytecode,
                                    @Bind Node node, @Shared @Cached ElementNodes.ArrayKeyNode toKey,
                                    @Shared @Cached ElementNodes.ElementReadNode read,
                                    @Shared @Cached InlinedBranchProfile undefined,
                                    @Shared @Cached InlinedBranchProfile sharedArray,
                                    @Shared @Cached InlinedBranchProfile refArray,
                                    @Shared @Cached InlinedBranchProfile createdArray,
                                    @Cached("function") PhpFunction cachedFunction) {
            return send(frame, variable, index, append, cachedFunction, keys, bytecode, node, toKey, read, undefined,
                    sharedArray, refArray, createdArray);
        }

        @Specialization(replaces = "cached")
        public static Object send(VirtualFrame frame, LocalAccessor variable, int index, boolean append,
                                  PhpFunction function, @Variadic Object[] keys, @Bind BytecodeNode bytecode,
                                  @Bind Node node, @Shared @Cached ElementNodes.ArrayKeyNode toKey,
                                  @Shared @Cached ElementNodes.ElementReadNode read,
                                  @Shared @Cached InlinedBranchProfile undefined,
                                  @Shared @Cached InlinedBranchProfile sharedArray,
                                  @Shared @Cached InlinedBranchProfile refArray,
                                  @Shared @Cached InlinedBranchProfile createdArray) {
            if (!function.isByRef(index)) {
                if (append) {
                    throw appendRead();
                }
                return readChain(readValue(frame, variable, bytecode, node, undefined), keys, read);
            }
            Object base = variableForWrite(frame, variable, bytecode, node, sharedArray, refArray, createdArray);
            return elementRef(node, base, keys, append, toKey);
        }
    }

    /** `$obj->name[k1]...[kn]` as argument index: as SendNested, from the property. */
    @Operation
    @ConstantOperand(type = PropSite.class)
    @ConstantOperand(type = int.class)
    @ConstantOperand(type = boolean.class, name = "append")
    public static final class SendPropNested {
        /** The callee the site sees (nearly always one), a constant: see SendVar. */
        @Specialization(guards = "function == cachedFunction", limit = "1", excludeForUncached = true)
        public static Object cached(VirtualFrame frame, PropSite site, int index, boolean append, PhpFunction function,
                                    Object object, @Variadic Object[] keys,
                                    @Shared @Cached(value = "site.create()", uncached = "site.uncached()",
                                            neverDefault = true) PropNode access,
                                    @Bind Node node, @Shared @Cached ElementNodes.ArrayKeyNode toKey,
                                    @Shared @Cached ElementNodes.ElementReadNode read,
                                    @Cached("function") PhpFunction cachedFunction) {
            return send(frame, site, index, append, cachedFunction, object, keys, access, node, toKey, read);
        }

        @Specialization(replaces = "cached")
        public static Object send(VirtualFrame frame, PropSite site, int index, boolean append, PhpFunction function,
                                  Object object, @Variadic Object[] keys,
                                  @Shared @Cached(value = "site.create()", uncached = "site.uncached()",
                                          neverDefault = true) PropNode access,
                                  @Bind Node node, @Shared @Cached ElementNodes.ArrayKeyNode toKey,
                                  @Shared @Cached ElementNodes.ElementReadNode read) {
            String name = placeName(site, keys);
            keys = placeKeys(site, keys);
            if (!function.isByRef(index)) {
                if (append) {
                    throw appendRead();
                }
                return readChain(access.read(frame.getArguments(), object, name), keys, read);
            }
            return elementRef(node, propertyForWrite(frame, access, object, name), keys, append, toKey);
        }
    }

    /**
     * An element of a temporary value as argument index, `f()[k1]...[kn]` (FETCH_DIM_FUNC_ARG on it): read down
     * the chain; for a by-reference parameter, a call's array is separated (ZEND_SEPARATE: a copy, null an empty
     * array; a returned reference stays one) and the element's reference taken in it; php-src's Error for writing
     * to any other temporary (a constant, a literal, `new`).
     */
    @Operation
    @ConstantOperand(type = int.class)
    @ConstantOperand(type = boolean.class, name = "append")
    @ConstantOperand(type = boolean.class, name = "call")
    public static final class SendTemporary {
        /** The callee the site sees (nearly always one), a constant: see SendVar. */
        @Specialization(guards = "function == cachedFunction", limit = "1", excludeForUncached = true)
        public static Object cached(int index, boolean append, boolean call, PhpFunction function, Object base,
                                    @Variadic Object[] keys, @Bind Node node,
                                    @Shared @Cached ElementNodes.ArrayKeyNode toKey,
                                    @Shared @Cached ElementNodes.ElementReadNode read,
                                    @Cached("function") PhpFunction cachedFunction) {
            return send(index, append, call, cachedFunction, base, keys, node, toKey, read);
        }

        @Specialization(replaces = "cached")
        public static Object send(int index, boolean append, boolean call, PhpFunction function, Object base,
                                  @Variadic Object[] keys, @Bind Node node,
                                  @Shared @Cached ElementNodes.ArrayKeyNode toKey,
                                  @Shared @Cached ElementNodes.ElementReadNode read) {
            if (!function.isByRef(index)) {
                if (append) {
                    throw appendRead();
                }
                return readChain(PhpRef.deref(base), keys, read);
            }
            if (!call) {
                throw temporaryWrite();
            }
            return elementRef(node, separated(base), keys, append, toKey);
        }
    }

    @TruffleBoundary
    private static Object separated(Object value) {
        if (value instanceof PhpRef ref) {
            // the reference a function returning by reference gave is written through: ZEND_SEPARATE drops only
            // a reference nothing else holds
            PhpArray array = Places.refArrayForWrite(ref);
            return array != null ? array : ref.get();
        }
        if (value instanceof PhpArray array) {
            return array.copy();
        }
        return value == PhpNull.NULL ? new PhpArray() : value;
    }

    @TruffleBoundary
    private static PhpError appendRead() {
        return new PhpError("Cannot use [] for reading");
    }

    @TruffleBoundary
    private static PhpError temporaryWrite() {
        return new PhpError("Cannot use temporary expression in write context");
    }

    /** base[k1]...[kn] read (DimNode.execute down a chain). */
    private static Object readChain(Object base, Object[] keys, ElementNodes.ElementReadNode read) {
        Object value = base;
        for (Object key : keys) {
            if (key == ElementChain.APPEND) {
                // a by-value argument's `[]` (FETCH_DIM_FUNC_ARG for read)
                throw appendRead();
            }
            value = read.execute(value, key);
        }
        return value;
    }

    /**
     * A call's result as argument index (SEND_VAR_NO_REF): for a by-reference parameter, the reference a function
     * returning by reference returned, else the value with php-src's notice.
     */
    /**
     * Whether the callee takes argument index by reference: a static property or a global as an argument is its
     * reference then (LValueNode.makeRef in ArgumentsNode), else its value (SEND_VAR_EX, FETCH_*_FUNC_ARG).
     */
    @Operation
    @ConstantOperand(type = int.class)
    public static final class ArgByRef {
        /** The callee the site sees (nearly always one), a constant: see SendVar. */
        @Specialization(guards = "function == cachedFunction", limit = "1", excludeForUncached = true)
        public static boolean cached(int index, PhpFunction function,
                                    @Cached("function") PhpFunction cachedFunction) {
            return byRef(index, cachedFunction);
        }

        @Specialization(replaces = "cached")
        public static boolean byRef(int index, PhpFunction function) {
            return function.isByRef(index);
        }
    }

    /**
     * A value that is neither a variable nor a call as argument index (SEND_VAL_EX): php-src's Error, once the
     * value is evaluated, when the callee's parameter is by reference.
     */
    @Operation
    @ConstantOperand(type = int.class)
    public static final class SendValue {
        /** The callee the site sees (nearly always one), a constant: see SendVar. */
        @Specialization(guards = "function == cachedFunction", limit = "1", excludeForUncached = true)
        public static Object cached(int index, PhpFunction function, Object value,
                                    @Cached("function") PhpFunction cachedFunction) {
            return send(index, cachedFunction, value);
        }

        @Specialization(replaces = "cached")
        public static Object send(int index, PhpFunction function, Object value) {
            if (function.isByRef(index) && !function.isPreferRef()) {
                throw CallNode.cannotPassByRef(function, index);
            }
            return value;
        }
    }

    @Operation
    @ConstantOperand(type = int.class)
    public static final class SendCall {
        /** The callee the site sees (nearly always one), a constant: see SendVar. */
        @Specialization(guards = "function == cachedFunction", limit = "1", excludeForUncached = true)
        public static Object cached(int index, PhpFunction function, Object result, @Bind Node node,
                                    @Cached("function") PhpFunction cachedFunction) {
            return send(index, cachedFunction, result, node);
        }

        @Specialization(replaces = "cached")
        public static Object send(int index, PhpFunction function, Object result, @Bind Node node) {
            if (!function.isByRef(index)) {
                return PhpRef.deref(result);
            }
            if (function.isPreferRef()) {
                return PhpRef.deref(result); // SEND_VAR_NO_REF_EX sends a value to a prefer-ref parameter
            }
            if (!(result instanceof PhpRef)) {
                CallNode.notice(node, CallNode.PASSED_VALUE);
            }
            return result;
        }
    }

    // ---- calls unpacking arguments (SpreadArguments) ----

    /**
     * The arguments of a call with `...`: SpreadStart makes the list (construct: a `new`'s), each SpreadAdd adds an
     * argument sent as any (php-src allows only unpacking and names after the first unpacked one, so their positions
     * are constants), each SpreadValue or SpreadVar the elements of an unpacked value, by position or by name
     * (SpreadArguments), each SpreadVarNamed or SpreadNamed a named argument after them; the call (CallSpread,
     * CallMethodSpread...) takes the list. `f($a, ...$b)` is CallSpread(f, SpreadVar(SpreadAdd(SpreadStart, $a), f)).
     */
    @Operation
    @ConstantOperand(type = boolean.class, name = "construct")
    public static final class SpreadStart {
        @Specialization
        public static SpreadArguments start(boolean construct) {
            return new SpreadArguments(construct);
        }
    }

    @Operation
    public static final class SpreadAdd {
        @Specialization
        public static SpreadArguments add(SpreadArguments list, Object value) {
            list.add(value);
            return list;
        }
    }

    /**
     * `...value` (ZEND_SEND_UNPACK): the elements of an array, by position or by their string keys' names, each by
     * reference where it lands on a by-reference parameter of the function (as it is: a temporary array's elements
     * aren't made references); a Traversable's; php-src's Error for anything else.
     */
    @Operation
    public static final class SpreadValue {
        @Specialization
        public static SpreadArguments spread(SpreadArguments list, PhpFunction function, Object value,
                                             @Bind Node node) {
            list.addSpread(value, function, node);
            return list;
        }
    }

    /**
     * `...$x`: as SpreadValue, but the variable's array is separated first when an element lands on a by-reference
     * parameter, and the elements passed by reference become references in it.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    public static final class SpreadVar {
        @Specialization
        public static SpreadArguments spread(VirtualFrame frame, LocalAccessor variable, SpreadArguments list,
                                             PhpFunction function, @Bind BytecodeNode bytecode, @Bind Node node,
                                             @Cached InlinedBranchProfile undefined,
                                             @Cached InlinedBranchProfile sharedArray,
                                             @Cached InlinedBranchProfile refArray,
                                             @Cached InlinedBranchProfile createdArray) {
            Object value = readValue(frame, variable, bytecode, node, undefined);
            if (value instanceof PhpArray array && SpreadArguments.landsOnRef(function, list.size(), array)) {
                list.addArray(Locals.arrayForWrite(frame, variable, bytecode, node, sharedArray, refArray,
                        createdArray), function, true);
            } else {
                list.addSpread(value, function, node);
            }
            return list;
        }
    }

    /** `...$x` of a variable kept in a reference (a file's variable, a superglobal): as SpreadVar. */
    @Operation
    public static final class SpreadRef {
        @Specialization
        public static SpreadArguments spread(SpreadArguments list, PhpFunction function, PhpRef ref,
                                             @Bind Node node) {
            Object value = ref.get();
            if (value instanceof PhpArray array && SpreadArguments.landsOnRef(function, list.size(), array)) {
                list.addArray(ref.arrayForWrite(), function, true);
            } else {
                list.addSpread(value, function, node);
            }
            return list;
        }
    }

    /**
     * A variable as a named argument after an unpacking (SEND_VAR_EX with a name): bound to the function first, so
     * an unknown name or a slot the unpacking filled throws without the "Undefined variable" warning; then its
     * reference when its slot's parameter is by reference, else its value.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = TruffleString.class, name = "name")
    public static final class SpreadVarNamed {
        @Specialization
        public static SpreadArguments send(VirtualFrame frame, LocalAccessor variable, TruffleString name,
                                           SpreadArguments list, PhpFunction function, @Bind BytecodeNode bytecode,
                                           @Bind Node node, @Cached InlinedBranchProfile undefined) {
            int slot = list.bind(name, function);
            list.set(slot, SpreadArguments.takesRef(function, slot) ? Locals.makeRef(frame, variable, bytecode)
                    : readValue(frame, variable, bytecode, node, undefined));
            return list;
        }
    }

    /** What a named argument after an unpacking is, for its SpreadNamed. */
    public static final int SPREAD_VALUE = 0;
    public static final int SPREAD_CALL = 1;
    public static final int SPREAD_PLACE = 2;

    /**
     * A named argument after an unpacking, once evaluated, bound to the function (zend_handle_named_arg at its
     * SEND): a value (SEND_VAL_EX: php-src's Error for a by-reference parameter), a call's result (SEND_VAR_NO_REF:
     * its reference, else the value with the notice), or a place fetched as SpreadTarget said (SEND_FUNC_ARG).
     */
    @Operation
    @ConstantOperand(type = TruffleString.class, name = "name")
    @ConstantOperand(type = int.class, name = "kind")
    public static final class SpreadNamed {
        @Specialization
        public static SpreadArguments send(TruffleString name, int kind, SpreadArguments list, PhpFunction function,
                                           Object value, @Bind Node node) {
            int slot = list.bind(name, function);
            boolean byRef = SpreadArguments.takesRef(function, slot) && !function.isPreferRef();
            if (kind == SPREAD_VALUE && byRef) {
                throw CallNode.cannotPassByRef(function, SpreadArguments.parameterOf(function, slot));
            }
            Object sent = value;
            if (kind == SPREAD_CALL && !byRef) {
                sent = PhpRef.deref(value);
            } else if (kind == SPREAD_CALL && !(value instanceof PhpRef)) {
                CallNode.notice(node, CallNode.PASSED_VALUE);
            }
            list.set(slot, sent);
            return list;
        }
    }

    /**
     * What a place as a named argument after an unpacking asks about its parameter (CHECK_FUNC_ARG with a name): a
     * stand-in taking it by reference or by value as the name's parameter does; an unknown name by value (its send
     * throws).
     */
    @Operation
    @ConstantOperand(type = TruffleString.class, name = "name")
    public static final class SpreadTarget {
        @Specialization
        public static PhpFunction target(TruffleString name, PhpFunction function) {
            return SpreadArguments.namedByRef(function, name) ? NamedSite.BY_REFERENCE : MethodSite.BY_VALUE;
        }
    }

    /** `name(...)` with unpacked arguments: as Call and CallRef (keepRef), the frame arguments from the list. */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    @ConstantOperand(type = boolean.class, name = "keepRef")
    public static final class CallSpread {
        @Specialization(guards = "function == cachedFunction", limit = "1")
        public static Object direct(CallInfo call, boolean keepRef, PhpFunction function, SpreadArguments arguments,
                                    @Bind Node node, @Cached("function") PhpFunction cachedFunction,
                                    @Cached("create(cachedFunction.getCallTarget())") DirectCallNode callNode) {
            Object[] frame = arguments.frameArguments();
            if (arguments.isNamed()) {
                arguments.enter(frame, cachedFunction, node);
            }
            return result(callNode.call(frame), keepRef);
        }

        @Specialization(replaces = "direct")
        public static Object indirect(CallInfo call, boolean keepRef, PhpFunction function,
                                      SpreadArguments arguments, @Bind Node node, @Cached IndirectCallNode callNode) {
            Object[] frame = arguments.frameArguments();
            if (arguments.isNamed()) {
                arguments.enter(frame, function, node);
            }
            return result(callNode.call(function.getCallTarget(), frame), keepRef);
        }
    }

    // ---- calls of callable values (CallableCallNode, DynamicMethodCallNode, CallableConvertNodes) ----

    /**
     * What `$f(args)` calls, found before its arguments (INIT_DYNAMIC_CALL, Callables.resolveCall): a closure, a
     * function's or "Cls::method"'s name, [$obj, 'method'], ['Cls', 'method']; php-src's Error for anything else.
     */
    @Operation
    public static final class ResolveCallable {
        @Specialization
        public static Callables.Target resolve(VirtualFrame frame, Object callable, @Bind Node node,
                                               @Bind PhpBytecodeRootNode root) {
            return Callables.resolveCall(PhpContext.get(node), callable, root.callingScope(frame));
        }
    }

    /**
     * What `$obj->$name(args)` calls (DynamicMethodCallNode): the callable [$obj, name]; ZEND_INIT_METHOD_CALL's
     * Errors for a name that isn't a string and a value that isn't an object.
     */
    @Operation
    public static final class ResolveDynamicMethod {
        @Specialization
        public static Callables.Target resolve(VirtualFrame frame, Object object, Object name, @Bind Node node,
                                               @Bind PhpBytecodeRootNode root) {
            if (!(name instanceof TruffleString method)) {
                throw MethodCallNode.nameNotString();
            }
            if (!(object instanceof PhpObject) && !(object instanceof PhpClosure)) {
                throw MethodCallNode.memberOnNonObject(Str.java(method), object);
            }
            return Callables.resolveCall(PhpContext.get(node), PhpArray.list(object, method),
                    root.callingScope(frame));
        }
    }

    /**
     * The function a callable's arguments ask about their parameters (SEND_VAR_EX): BY_VALUE when __call or
     * __callStatic stands in for a method, whose arguments go by value.
     */
    @Operation
    public static final class TargetFunction {
        @Specialization
        public static PhpFunction function(Callables.Target target) {
            if (target.magicName() == null) {
                return target.function();
            }
            return target.closure() instanceof PhpClosure closure && !closure.isMagicVariadic()
                    ? MethodSite.NO_PARAMETERS : MethodSite.BY_VALUE;
        }
    }

    /**
     * `$f(args)` once the arguments are sent: the target's function with its $this, scope and closure (as
     * CallableDispatchNode calls it); a method __call stands in for gets the name and the arguments in an array.
     * keepRef: the result goes where a returned reference is kept.
     */
    @Operation
    @ConstantOperand(type = boolean.class, name = "keepRef")
    public static final class CallValue {
        @Specialization(guards = "target.function() == cachedFunction", limit = "4")
        public static Object direct(boolean keepRef, Callables.Target target, PhpFunction function,
                                    @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                    @Cached("target.function()") PhpFunction cachedFunction,
                                    @Cached("create(cachedFunction.getCallTarget())") DirectCallNode callNode) {
            return result(callNode.call(targetArguments(target, arguments)), keepRef);
        }

        @Specialization(replaces = "direct")
        public static Object indirect(boolean keepRef, Callables.Target target, PhpFunction function,
                                      @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                      @Cached IndirectCallNode callNode) {
            return result(callNode.call(target.function().getCallTarget(), targetArguments(target, arguments)),
                    keepRef);
        }
    }

    /** `$f(...)` with unpacked arguments (SpreadStart): as CallValue, the frame arguments from the list. */
    @Operation
    @ConstantOperand(type = boolean.class, name = "keepRef")
    public static final class CallValueSpread {
        @Specialization(guards = "target.function() == cachedFunction", limit = "4")
        public static Object direct(boolean keepRef, Callables.Target target, PhpFunction function,
                                    SpreadArguments arguments, @Bind Node node,
                                    @Cached("target.function()") PhpFunction cachedFunction,
                                    @Cached("create(cachedFunction.getCallTarget())") DirectCallNode callNode) {
            return result(callNode.call(spreadTargetArguments(target, arguments, node)), keepRef);
        }

        @Specialization(replaces = "direct")
        public static Object indirect(boolean keepRef, Callables.Target target, PhpFunction function,
                                      SpreadArguments arguments, @Bind Node node, @Cached IndirectCallNode callNode) {
            return result(callNode.call(target.function().getCallTarget(), spreadTargetArguments(target,
                    arguments, node)), keepRef);
        }
    }

    /**
     * The frame arguments of a call of target from the list (as targetArguments): __call's two, the named ones in
     * its array; the holes and extras of the function's own call put in.
     */
    private static Object[] spreadTargetArguments(Callables.Target target, SpreadArguments arguments, Node node) {
        if (target.magicName() != null) {
            return target.boundArguments(arguments.magicValues(target.magicName()));
        }
        Object[] frame = targetArguments(target, arguments.frameArguments());
        if (arguments.isNamed()) {
            arguments.enter(frame, target.function(), node);
        }
        return frame;
    }

    /** The frame arguments of a call of target (Callables.Target.frameArguments) in the array the call built. */
    private static Object[] targetArguments(Callables.Target target, Object[] arguments) {
        if (target.magicName() != null) {
            return target.frameArguments(values(arguments));
        }
        arguments[PhpFunction.ARG_THIS] = target.thisObject();
        arguments[PhpFunction.ARG_STATIC] = target.staticClass();
        arguments[PhpFunction.ARG_CLOSURE] = target.closure();
        return arguments;
    }

    /**
     * call_user_func($f, args...) (CallableCallNode with the built-in's parameter): the arguments by value, the
     * callable resolved as a built-in's (its TypeError). keepRef: php-src compiles the call into the callee's
     * (zend_compile_func_cuf), whose result a returned reference may be.
     */
    @Operation
    @ConstantOperand(type = boolean.class, name = "keepRef")
    public static final class CallUserFunc {
        static final Callables.Parameter PARAMETER = new Callables.Parameter("call_user_func", 1, false);

        @Specialization(excludeForUncached = true)
        public static Object cached(boolean keepRef, Object callable, @Variadic Object[] values,
                                    @Cached("create(PARAMETER, keepRef)") CallableDispatchNode dispatch) {
            return dispatch.execute(callable, values);
        }

        @Specialization(replaces = "cached")
        public static Object uncached(boolean keepRef, Object callable, @Variadic Object[] values,
                                      @Bind Node node) {
            return callTarget(Callables.resolveOrThrow(PhpContext.get(node), callable, PARAMETER), values, keepRef);
        }
    }

    /**
     * call_user_func_array($f, $args): as CallUserFunc, the arguments the array's elements (references stay); with a
     * string key, ZEND_SEND_ARRAY's binding by name (SpreadArguments.sendArray).
     */
    @Operation
    @ConstantOperand(type = boolean.class, name = "keepRef")
    public static final class CallUserFuncArray {
        static final Callables.Parameter PARAMETER = new Callables.Parameter("call_user_func_array", 1, false);

        @Specialization(excludeForUncached = true)
        public static Object cached(boolean keepRef, Object callable, Object args, @Bind Node node,
                                    @Cached("create(PARAMETER, keepRef)") CallableDispatchNode dispatch) {
            Object[] values = CallableCallNode.arrayArguments(args);
            if (values == null) {
                return callNamed(callable, (PhpArray) args, keepRef, node);
            }
            return dispatch.execute(callable, values);
        }

        @Specialization(replaces = "cached")
        public static Object uncached(boolean keepRef, Object callable, Object args, @Bind Node node) {
            Object[] values = CallableCallNode.arrayArguments(args);
            if (values == null) {
                return callNamed(callable, (PhpArray) args, keepRef, node);
            }
            return callTarget(Callables.resolveOrThrow(PhpContext.get(node), callable, PARAMETER), values, keepRef);
        }

        @TruffleBoundary
        private static Object callNamed(Object callable, PhpArray args, boolean keepRef, Node node) {
            Callables.Target target = Callables.resolveOrThrow(PhpContext.get(node), callable, PARAMETER);
            Object[] frame = SpreadArguments.sendArray(target, args, node);
            return result(Backtrace.callFrom(node, () -> target.function().getCallTarget().call(frame)), keepRef);
        }
    }

    @TruffleBoundary
    private static Object callTarget(Callables.Target target, Object[] values, boolean keepRef) {
        return result(target.function().getCallTarget().call(target.frameArguments(values)), keepRef);
    }

    /**
     * func_get_args() (php-src's): the arguments passed, each declared parameter's as its variable holds it now
     * (null when unset), the extra ones as they were passed.
     */
    @Operation
    public static final class FuncGetArgs {
        @Specialization
        public static PhpArray args(VirtualFrame frame, @Bind PhpBytecodeRootNode root,
                                    @Bind BytecodeNode bytecode) {
            return root.passedArguments(frame, bytecode);
        }
    }

    /** func_get_args()'s array (see FuncGetArgs); the loop over the parameters is over constants. */
    @ExplodeLoop
    PhpArray passedArguments(VirtualFrame frame, BytecodeNode bytecode) {
        if (file) {
            throw globalScope("func_get_args() cannot be called from the global scope");
        }
        Object[] arguments = frame.getArguments();
        int count = arguments.length - PhpFunction.ARG_FIRST;
        PhpArray list = PhpArray.ofCapacity(count);
        // the variadic parameter's arguments are extra ones (php-src's num_args leaves it out)
        int declared = params.length > 0 && params[params.length - 1].variadic() ? params.length - 1 : params.length;
        int fromVariables = Math.min(count, declared);
        for (int i = 0; i < declared; i++) {
            if (i < fromVariables) {
                list.append(Variables.value(Locals.stored(frame, variables[parameterLocals[i]], bytecode)));
            }
        }
        for (int i = fromVariables; i < count; i++) {
            list.append(PhpRef.deref(arguments[PhpFunction.ARG_FIRST + i]));
        }
        return list;
    }

    /**
     * func_get_arg($position) (FrameArgNode): a declared parameter's variable as it holds it now (null when
     * unset), an extra argument as passed; php-src's ValueError for a position outside the arguments passed.
     */
    @Operation
    public static final class FuncGetArg {
        @Specialization
        public static Object arg(VirtualFrame frame, Object position, @Bind PhpBytecodeRootNode root,
                                 @Bind BytecodeNode bytecode) {
            return root.passedArgument(frame, bytecode, Convert.toLong(position));
        }
    }

    /** func_get_arg()'s value (see FuncGetArg); the loop over the parameters is over constants. */
    @ExplodeLoop
    Object passedArgument(VirtualFrame frame, BytecodeNode bytecode, long offset) {
        if (offset < 0) {
            throw FrameArgNode.negativeOffset();
        }
        if (file) {
            throw globalScope("func_get_arg() cannot be called from the global scope");
        }
        Object[] arguments = frame.getArguments();
        if (offset >= arguments.length - PhpFunction.ARG_FIRST) {
            throw FrameArgNode.offsetPastArguments();
        }
        // the variadic parameter's arguments are extra ones (php-src's num_args leaves it out)
        int declared = params.length > 0 && params[params.length - 1].variadic() ? params.length - 1 : params.length;
        for (int i = 0; i < declared; i++) {
            if (i == offset) {
                return Variables.value(Locals.stored(frame, variables[parameterLocals[i]], bytecode));
            }
        }
        return PhpRef.deref(arguments[PhpFunction.ARG_FIRST + (int) offset]);
    }

    /** php-src's E_DEPRECATED at this instruction's line. */
    @Operation
    @ConstantOperand(type = String.class, name = "message")
    public static final class Deprecation {
        @Specialization
        public static void deprecated(String message, @Bind Node node) {
            deprecation(node, message);
        }
    }

    /**
     * get_class() without arguments outside a class's code (ZEND_GET_CLASS): the scope the code runs in (a closure's
     * bound class, an included file's), else php-src's Error; deprecated in 8.5.
     */
    @Operation
    public static final class ScopeClassName {
        @Specialization
        public static TruffleString name(VirtualFrame frame, @Bind PhpBytecodeRootNode root, @Bind Node node) {
            return scopeClassName(node, root.callingScope(frame));
        }
    }

    @TruffleBoundary
    private static TruffleString scopeClassName(Node node, String scope) {
        if (scope == null) {
            throw new PhpError("Error", "get_class() without arguments must be called from within a class");
        }
        deprecation(node, "Calling get_class() without arguments is deprecated");
        return Str.of(PhpContext.get(node).findClass(scope).getName());
    }

    /**
     * get_called_class() outside a class's code (ZEND_GET_CALLED_CLASS): the called class the frame was passed, else
     * php-src's Error.
     */
    @Operation
    public static final class CalledClassName {
        @Specialization
        public static TruffleString name(VirtualFrame frame) {
            return calledClassName(frame.getArguments()[PhpFunction.ARG_STATIC]);
        }
    }

    @TruffleBoundary
    private static TruffleString calledClassName(Object called) {
        if (!(called instanceof PhpClass phpClass)) {
            throw new PhpError("Error", "get_called_class() must be called from within a class");
        }
        return Str.of(phpClass.getName());
    }

    @TruffleBoundary
    private static void deprecation(Node node, String message) {
        ErrorFunctions.raise(PhpContext.get(node), ErrorFunctions.E_DEPRECATED, Str.of(message),
                SourceLocation.of(node));
    }

    /**
     * `@expr`'s start (BEGIN_SILENCE, the tree's SilenceNode): error_reporting keeps only the fatal errors while
     * the expression runs; the value is error_reporting before, which SilenceEnd takes.
     */
    @Operation
    public static final class SilenceStart {
        @Specialization
        public static int start(@Bind Node node) {
            return UnaryNodes.SilenceNode.begin(PhpContext.get(node).ini());
        }
    }

    /** `@expr`'s end (END_SILENCE), however the expression is left: error_reporting as it was before. */
    @Operation
    public static final class SilenceEnd {
        @Specialization
        public static void end(int saved, @Bind Node node) {
            UnaryNodes.SilenceNode.end(PhpContext.get(node).ini(), saved);
        }
    }

    /** func_num_args(): how many arguments the call passed. */
    @Operation
    public static final class FuncNumArgs {
        @Specialization
        public static long count(VirtualFrame frame, @Bind PhpBytecodeRootNode root) {
            if (root.file) {
                throw globalScope("func_num_args() must be called from a function context");
            }
            return frame.getArguments().length - PhpFunction.ARG_FIRST;
        }
    }

    /** The func_*_arg*() family at a file's top level (ZEND_CALL_CODE frames): php-src's Error. */
    @TruffleBoundary
    private static PhpError globalScope(String message) {
        return new PhpError("Error", message);
    }

    // ---- the call's variables by name: include, extract(), compact(), get_object_vars() ----

    /**
     * include / require (_once) in a function (IncludeNode): the file runs in the call's variables (its
     * {@link BytecodeScope}), with the call's $this and static class. Out of line: the path's resolution and the
     * file's run are one call (IncludeNode.include). This instruction is the file's caller in traces.
     */
    @Operation
    @ConstantOperand(type = IncludeSite.class)
    public static final class Include {
        @Specialization
        public static Object include(VirtualFrame frame, IncludeSite site, Object path,
                                     @Bind PhpBytecodeRootNode root, @Bind BytecodeNode bytecode,
                                     @Bind Node node) {
            return IncludeNode.include(node, site, path, site.once(), frame.getArguments(),
                    root.callScope(frame, bytecode));
        }
    }

    /**
     * include / require (_once) in a file's top level: as Include, the file running in the scope the including
     * file has (its frame's), without the function's scope to look at; wp-settings.php has hundreds.
     */
    @Operation
    @ConstantOperand(type = IncludeSite.class)
    public static final class IncludeInFile {
        @Specialization
        public static Object include(VirtualFrame frame, IncludeSite site, Object path, @Bind Node node) {
            return IncludeNode.include(node, site, path, site.once(), frame.getArguments(), null);
        }
    }

    /**
     * eval(code) (zend_include_or_eval's ZEND_EVAL): the code compiled anew (SourceFiles.eval) and run in the
     * calling scope, as an include's file; this instruction is its caller in traces.
     */
    @Operation
    @ConstantOperand(type = IncludeSite.class)
    public static final class Eval {
        @Specialization
        public static Object eval(VirtualFrame frame, IncludeSite site, Object code, @Bind PhpBytecodeRootNode root,
                                  @Bind BytecodeNode bytecode, @Bind Node node) {
            CallTarget file = compileEval(node, code);
            if (file == null) {
                return Boolean.FALSE;
            }
            return IncludeNode.run(node, file, frame.getArguments(), root.callScope(frame, bytecode));
        }
    }

    @TruffleBoundary
    private static CallTarget compileEval(Node node, Object code) {
        RootNode file = SourceFiles.eval(PhpContext.get(node), SourceLocation.of(node),
                Convert.toStr(node, PhpRef.deref(code)));
        return file == null ? null : file.getCallTarget();
    }

    /**
     * extract($array, $flags, $prefix) (ExtractNode): the array's entries become the call's variables, a name
     * the function doesn't name one of the call's other variables (BytecodeScope).
     */
    @Operation
    public static final class Extract {
        @Specialization
        public static long extract(VirtualFrame frame, @Variadic Object[] arguments,
                                   @Bind PhpBytecodeRootNode root, @Bind BytecodeNode bytecode,
                                   @Bind Node node) {
            if (arguments.length == 0 || arguments.length > 3) {
                throw extractArity(arguments.length);
            }
            return ExtractNode.extract(arguments[0], arguments.length > 1 ? arguments[1] : null,
                    arguments.length > 2 ? arguments[2] : null, root.callScope(frame, bytecode),
                    PhpContext.get(node));
        }
    }

    @TruffleBoundary
    private static PhpError extractArity(int count) {
        return new PhpError("ArgumentCountError", "extract() expects " + (count == 0 ? "at least 1 argument"
                : "at most 3 arguments") + ", " + count + " given");
    }

    /** compact() with names known at translation time: the array CompactVar and CompactName fill in order. */
    @Operation
    public static final class CompactStart {
        @Specialization
        public static PhpArray start() {
            return new PhpArray();
        }
    }

    /**
     * A name of compact() (php_compact_var) the function has a variable of: its value under the name (a null
     * counts), or php-src's warning when it is undefined.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = TruffleString.class, name = "key")
    public static final class CompactVar {
        @Specialization
        public static PhpArray add(VirtualFrame frame, LocalAccessor variable, TruffleString key, PhpArray result,
                                   @Bind BytecodeNode bytecode, @Bind Node node) {
            Object stored = Locals.stored(frame, variable, bytecode);
            if (stored == null) {
                compactUndefined(node, key);
            } else {
                result.put(key, Variables.value(stored));
            }
            return result;
        }
    }

    /**
     * A name of compact() the function names no variable of: one of the call's other variables (an include or
     * extract() made it), $this for "this", else the warning.
     */
    @Operation
    @ConstantOperand(type = TruffleString.class, name = "key")
    public static final class CompactName {
        @Specialization
        public static PhpArray add(VirtualFrame frame, TruffleString key, PhpArray result,
                                   @Bind PhpBytecodeRootNode root, @Bind BytecodeNode bytecode, @Bind Node node) {
            compactEntries(result, new Object[] {key}, null, root, root.namedScope(frame, bytecode),
                    frame.getArguments()[PhpFunction.ARG_THIS], bytecode, node);
            return result;
        }
    }

    /** compact() with names computed at run time (DynamicCompactNode): each looked up by name. */
    @Operation
    public static final class CompactDynamic {
        @Specialization
        public static PhpArray compact(VirtualFrame frame, @Variadic Object[] names,
                                       @Bind PhpBytecodeRootNode root, @Bind BytecodeNode bytecode,
                                       @Bind Node node) {
            PhpArray result = new PhpArray();
            compactEntries(result, names, frame.materialize(), root, root.namedScope(frame, bytecode),
                    frame.getArguments()[PhpFunction.ARG_THIS], bytecode, node);
            return result;
        }
    }

    private static Object globalValue(Node node, String name) {
        PhpRef ref = PhpContext.get(node).findGlobal(name);
        return ref == null ? null : ref.get();
    }

    @TruffleBoundary
    private static void compactUndefined(Node node, TruffleString name) {
        ErrorFunctions.raise(PhpContext.get(node), ErrorFunctions.E_WARNING,
                Str.of("compact(): Undefined variable $" + Str.java(name)), SourceLocation.of(node));
    }

    /**
     * compact()'s arguments (php-src's php_compact_var over each): a string names a variable, looked up in the
     * call's symbol table (the function's locals, when frame is given, then the call's other variables), an array
     * holds names (nested arrays too), anything else is warned about.
     */
    @TruffleBoundary
    private static void compactEntries(PhpArray result, Object[] entries, MaterializedFrame frame,
                                       PhpBytecodeRootNode root, IncludeScope scope, Object thisObject,
                                       BytecodeNode bytecode, Node node) {
        if (entries.length == 0) {
            throw new PhpError("ArgumentCountError", "compact() expects at least 1 argument, 0 given");
        }
        for (int i = 0; i < entries.length; i++) {
            compactEntry(result, entries[i], i + 1, frame, root, scope, thisObject, bytecode, node);
        }
    }

    private static void compactEntry(PhpArray result, Object entry, int position, MaterializedFrame frame,
                                     PhpBytecodeRootNode root, IncludeScope scope, Object thisObject,
                                     BytecodeNode bytecode, Node node) {
        Object value = PhpRef.deref(entry);
        if (value instanceof PhpArray names) {
            for (int p = names.firstPosition(); p >= 0; p = names.nextPosition(p)) {
                compactEntry(result, names.valueAt(p), position, frame, root, scope, thisObject, bytecode, node);
            }
            return;
        }
        if (!(value instanceof TruffleString name)) {
            ErrorFunctions.raise(PhpContext.get(node), ErrorFunctions.E_WARNING, Str.of("compact(): Argument #"
                    + position + " must be string or array of strings, " + Args.valueName(value) + " given"),
                    SourceLocation.of(node));
            return;
        }
        String variableName = Str.java(name);
        LocalAccessor variable = frame == null ? null : root.variableNamed(variableName);
        Object found;
        if (variable != null) {
            Object stored = Locals.stored(frame, variable, bytecode);
            found = stored == null ? null : Variables.value(stored);
        } else {
            found = scope != null ? scope.get(variableName) : root.file ? globalValue(node, variableName) : null;
        }
        if (found != null) {
            result.put(ArrayKey.of(name), found);
        } else if (variableName.equals("this")) {
            if (thisObject instanceof PhpObject) {
                result.put(ArrayKey.of(name), thisObject);
            }
        } else {
            compactUndefined(node, name);
        }
    }

    /**
     * get_object_vars($object) (ObjectVarsNode): the properties the calling scope may access (a closure's bound
     * class, else the function's).
     */
    @Operation
    public static final class ObjectVars {
        @Specialization
        public static Object vars(VirtualFrame frame, Object object, @Bind PhpBytecodeRootNode root) {
            return ObjectVarsNode.objectVars(object, PropNode.callingScope(frame, root.scopeName));
        }
    }

    /**
     * `$$name` fetched for a read (VariableVariableNode.read: R, IS, W for a property's object, ISSET): the call's
     * variable by name, through its BytecodeScope, made at the first such fetch as an include's.
     */
    @Operation
    @ConstantOperand(type = int.class, name = "type")
    public static final class VariableValue {
        @Specialization
        public static Object read(VirtualFrame frame, int type, Object name, @Bind PhpBytecodeRootNode root,
                                  @Bind BytecodeNode bytecode, @Bind Node node) {
            return VariableVariableNode.read(node, root.callScope(frame, bytecode),
                    frame.getArguments()[PhpFunction.ARG_THIS], VariableVariableNode.name(node, name), type);
        }
    }

    /** `$$name` as a place (VariableVariableNode.ref: W, RW, UNSET): its reference, or null for a missing UNSET. */
    @Operation
    @ConstantOperand(type = int.class, name = "type")
    public static final class VariableRef {
        @Specialization
        public static Object ref(VirtualFrame frame, int type, Object name, @Bind PhpBytecodeRootNode root,
                                 @Bind BytecodeNode bytecode, @Bind Node node) {
            return VariableVariableNode.ref(node, root.callScope(frame, bytecode),
                    VariableVariableNode.name(node, name), type);
        }
    }

    /** `unset($$name)` (ZEND_UNSET_VAR). */
    @Operation
    public static final class UnsetVariable {
        @Specialization
        public static void unset(VirtualFrame frame, Object name, @Bind PhpBytecodeRootNode root,
                                 @Bind BytecodeNode bytecode, @Bind Node node) {
            VariableVariableNode.unset(node, root.callScope(frame, bytecode), VariableVariableNode.name(node, name));
        }
    }

    /** `$$name = &place`: the reference, then the variable named bound to it (as BindGlobal). */
    @Operation
    public static final class BindVariable {
        @Specialization
        public static Object bind(VirtualFrame frame, PhpRef ref, Object name, @Bind PhpBytecodeRootNode root,
                                  @Bind BytecodeNode bytecode, @Bind Node node) {
            VariableVariableNode.bind(node, root.callScope(frame, bytecode), VariableVariableNode.name(node, name),
                    ref);
            return ref.get();
        }
    }

    /**
     * `global $$name` (zend_compile_global_var of a name that isn't a constant): FETCH_W of the global, then
     * ASSIGN_REF of the call's variable, each converting the name.
     */
    @Operation
    public static final class GlobalVariable {
        @Specialization
        public static void bind(VirtualFrame frame, Object name, @Bind PhpBytecodeRootNode root,
                                @Bind BytecodeNode bytecode, @Bind Node node) {
            PhpRef global = (PhpRef) VariableVariableNode.ref(node, null, VariableVariableNode.name(node, name),
                    VariableVariableNode.W);
            VariableVariableNode.bind(node, root.callScope(frame, bytecode), VariableVariableNode.name(node, name),
                    global);
        }
    }

    /** `f(...)` (CallableConvertNodes.FunctionNode): a Closure of the function the name resolves to. */
    @Operation
    @ConstantOperand(type = String.class, name = "displayName")
    @ConstantOperand(type = int.class, name = "id")
    @ConstantOperand(type = int.class, name = "fallbackId")
    @ConstantOperand(type = int.class, name = "site")
    public static final class ConvertFunction {
        @Specialization
        public static Object convert(String displayName, int id, int fallbackId, int site, @Bind Node node) {
            return CallableConvertNodes.FunctionNode.convert(PhpContext.get(node), displayName, id, fallbackId,
                    site);
        }
    }

    /** `$obj->name(...)`, `$obj->$name(...)` (CallableConvertNodes.MethodNode): the object, then the name. */
    @Operation
    public static final class ConvertMethod {
        @Specialization
        public static Object convert(VirtualFrame frame, Object object, Object name,
                                     @Bind PhpBytecodeRootNode root) {
            if (!(name instanceof TruffleString method)) {
                throw MethodCallNode.nameNotString();
            }
            return CallableConvertNodes.MethodNode.convert(object, Str.java(method), root.callingScope(frame));
        }
    }

    /**
     * `Cls::name(...)` (CallableConvertNodes.StaticMethodNode): a non-static method takes the caller's $this when
     * it is an instance of the class; a forwarding one (self::, parent::, static::) keeps the called class.
     */
    @Operation
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = boolean.class, name = "forwarding")
    public static final class ConvertStatic {
        @Specialization
        public static Object convert(VirtualFrame frame, String name, boolean forwarding, PhpClass phpClass,
                                     @Bind PhpBytecodeRootNode root) {
            Object[] caller = frame.getArguments();
            Object called = caller[PhpFunction.ARG_STATIC];
            return CallableConvertNodes.StaticMethodNode.convert(phpClass, caller[PhpFunction.ARG_THIS],
                    forwarding && called instanceof PhpClass calledClass ? calledClass : phpClass, name,
                    root.callingScope(frame));
        }
    }

    /** `Cls::$name(...)`: as ConvertStatic, the name computed; php-src's Error for one that isn't a string. */
    @Operation
    @ConstantOperand(type = boolean.class, name = "forwarding")
    public static final class ConvertStaticNamed {
        @Specialization
        public static Object convert(VirtualFrame frame, boolean forwarding, PhpClass phpClass, Object name,
                                     @Bind PhpBytecodeRootNode root) {
            if (!(name instanceof TruffleString method)) {
                throw MethodCallNode.nameNotString();
            }
            return ConvertStatic.convert(frame, Str.java(method), forwarding, phpClass, root);
        }
    }

    /**
     * What `Cls::$name(args)` calls (StaticCallNode.target), found before the arguments; CallValue calls it. forwarding:
     * self::, parent::, static::, which keep the caller's called class.
     */
    @Operation
    @ConstantOperand(type = boolean.class, name = "forwarding")
    public static final class ResolveStaticCall {
        @Specialization
        public static Callables.Target resolve(VirtualFrame frame, boolean forwarding, PhpClass phpClass, Object name,
                                               @Bind PhpBytecodeRootNode root) {
            Object[] caller = frame.getArguments();
            PhpClass called = forwarding && caller[PhpFunction.ARG_STATIC] instanceof PhpClass callerStatic
                    ? callerStatic : phpClass;
            return StaticCallNode.target(phpClass, name, caller[PhpFunction.ARG_THIS], called,
                    root.callingScope(frame));
        }
    }

    /** `$f(...)` (CallableConvertNodes.ValueNode): what the call `$f()` would call; a Closure stays itself. */
    @Operation
    public static final class ConvertValue {
        @Specialization
        public static Object convert(VirtualFrame frame, Object callable, @Bind Node node,
                                     @Bind PhpBytecodeRootNode root) {
            return CallableConvertNodes.ValueNode.convert(PhpContext.get(node), callable, root.callingScope(frame));
        }
    }

    /**
     * A closure or arrow function (ClosureNode): the values it captures (an array gains the closure as a holder)
     * and references (bound to it), $this unless it is static, and the class scope (null outside classes).
     */
    @Operation
    @ConstantOperand(type = PhpFunction.class)
    @ConstantOperand(type = boolean[].class, name = "byRef")
    @ConstantOperand(type = boolean.class, name = "isStatic")
    public static final class MakeClosure {
        @Specialization
        public static PhpClosure make(VirtualFrame frame, PhpFunction function, boolean[] byRef, boolean isStatic,
                                      Object scope, @Variadic Object[] captures) {
            Object[] captured = new Object[captures.length];
            for (int i = 0; i < captured.length; i++) {
                Object value = captures[i];
                if (byRef[i]) {
                    ((PhpRef) value).bind();
                } else if (value instanceof PhpArray array) {
                    array.addHolder();
                } else if (value instanceof PhpObject object) {
                    object.addRef();
                }
                captured[i] = value;
            }
            return PhpClosure.declared(function, isStatic, frame.getArguments()[PhpFunction.ARG_THIS],
                    frame.getArguments()[PhpFunction.ARG_STATIC], scope instanceof PhpClass phpClass ? phpClass : null,
                    captured);
        }
    }

    /** self, parent, their ::class, or the scope a nested closure gets, in a closure (ClassRefNode.closureScope). */
    @Operation
    @ConstantOperand(type = int.class, name = "mode")
    public static final class ClosureScope {
        @Specialization
        public static Object scope(VirtualFrame frame, int mode) {
            return ClassRefNode.closureScope(frame, mode);
        }
    }

    // ---- methods, classes and objects (MethodCallNode, StaticCallNode, NewNode, ClassRefNode) ----

    /**
     * The method `$obj->name(args)` calls, found before the arguments (INIT_METHOD_CALL) by the receiver's class,
     * inline-cached on its key (MethodCallNode.Dispatch); BY_VALUE for __call standing in for it and for a Closure
     * receiver, whose arguments go by value; a receiver that isn't an object is an Error.
     */
    @Operation
    @ConstantOperand(type = MethodSite.class)
    public static final class ResolveMethod {
        /**
         * Per class key, as long as the answer follows from it (keyed) and, in a closure whose bound scope may
         * change, the scope is the entry's or doesn't matter (scoped: the method's visibility or a private method
         * of a scope decides). In a method the scope is the declaring class: both fold.
         */
        @Specialization(guards = {"receiver.getPhpClass().getKey() == cachedKey", "keyed",
                "!scoped || root.sameScope(frame, cachedScope)"}, limit = "4", excludeForUncached = true)
        public static PhpFunction cached(VirtualFrame frame, MethodSite site, PhpObject receiver,
                                         @Bind PhpBytecodeRootNode root,
                                         @Cached("receiver.getPhpClass().getKey()") ClassKey cachedKey,
                                         @Cached("root.callingScope(frame)") String cachedScope,
                                         @Cached("site.isKeyed(receiver.getPhpClass(), cachedScope)") boolean keyed,
                                         @Cached("site.isScoped(root, receiver.getPhpClass())") boolean scoped,
                                         @Cached("find(site, receiver.getPhpClass(), cachedScope)") PhpFunction method) {
            return method;
        }

        @Specialization(replaces = "cached")
        public static PhpFunction object(VirtualFrame frame, MethodSite site, PhpObject receiver,
                                         @Bind PhpBytecodeRootNode root) {
            return find(site, receiver.getPhpClass(), root.callingScope(frame));
        }

        /** A Closure's __invoke is its function (unless __call stands in for it); its other methods take values. */
        @Specialization
        public static PhpFunction closure(MethodSite site, PhpClosure receiver) {
            return site.lowerName.equals("__invoke") && receiver.getMagicName() == null ? receiver.getFunction()
                    : MethodSite.BY_VALUE;
        }

        @Fallback
        public static PhpFunction other(MethodSite site, Object receiver) {
            throw MethodCallNode.memberOnNonObject(site.name, receiver);
        }

        /** The method from the scope (zend_std_get_method), BY_VALUE for __call standing in for it. */
        public static PhpFunction find(MethodSite site, PhpClass phpClass, String scope) {
            PhpFunction method = phpClass.methodFrom(site.name, scope);
            return method == null ? MethodSite.BY_VALUE : method;
        }
    }



    /**
     * `$obj->name(args)` once the arguments are sent: $this is the receiver unless the method is static, the
     * called class the receiver's (MethodCallNode). By value: __call with the name as called and the arguments in
     * an array, a Closure's __invoke (CallableDispatchNode) or its other methods (bindTo, call). keepRef: the
     * result goes where a returned reference is kept (an argument).
     */
    @Operation
    @ConstantOperand(type = MethodSite.class)
    @ConstantOperand(type = boolean.class, name = "keepRef")
    public static final class CallMethod {
        @Specialization(guards = {"!isByValue(function)", "function == cachedFunction"}, limit = "4")
        public static Object direct(MethodSite site, boolean keepRef, PhpObject receiver, PhpFunction function,
                                    @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                    @Cached("function") PhpFunction cachedFunction,
                                    @Cached("create(cachedFunction.getCallTarget())") DirectCallNode callNode) {
            arguments[PhpFunction.ARG_THIS] = cachedFunction.isStatic() ? null : receiver;
            arguments[PhpFunction.ARG_STATIC] = receiver.getPhpClass();
            if (site.receiver == MethodSite.COUNTED) {
                receiver.addRef();
                try {
                    return result(callNode.call(arguments), keepRef);
                } finally {
                    receiver.release();
                }
            }
            if (site.receiver == MethodSite.VARIABLE) {
                receiver.possibleRoot();
            }
            return result(callNode.call(arguments), keepRef);
        }

        @Specialization(guards = "!isByValue(function)", replaces = "direct")
        public static Object indirect(MethodSite site, boolean keepRef, PhpObject receiver, PhpFunction function,
                                      @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                      @Shared @Cached IndirectCallNode callNode) {
            arguments[PhpFunction.ARG_THIS] = function.isStatic() ? null : receiver;
            arguments[PhpFunction.ARG_STATIC] = receiver.getPhpClass();
            if (site.receiver == MethodSite.COUNTED) {
                receiver.addRef();
                try {
                    return result(callNode.call(function.getCallTarget(), arguments), keepRef);
                } finally {
                    receiver.release();
                }
            }
            if (site.receiver == MethodSite.VARIABLE) {
                receiver.possibleRoot();
            }
            return result(callNode.call(function.getCallTarget(), arguments), keepRef);
        }

        /** `$closure->__invoke(args)`: the closure's function, as `$closure(args)` calls it (CallValue). */
        @Specialization(guards = "!isByValue(function)")
        public static Object invoke(MethodSite site, boolean keepRef, PhpClosure receiver, PhpFunction function,
                                    @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                    @Shared @Cached IndirectCallNode callNode) {
            PhpObject self = receiver.getBoundThis();
            arguments[PhpFunction.ARG_THIS] = self;
            arguments[PhpFunction.ARG_STATIC] = self != null ? self.getPhpClass() : receiver.getScope();
            arguments[PhpFunction.ARG_CLOSURE] = receiver;
            return result(callNode.call(function.getCallTarget(), arguments), keepRef);
        }

        @Specialization(guards = "isByValue(function)")
        public static Object byValue(MethodSite site, boolean keepRef, Object receiver, PhpFunction function,
                                     @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                     @Shared @Cached IndirectCallNode callNode, @Bind Node node) {
            Object[] values = values(arguments);
            if (receiver instanceof PhpClosure closure) {
                if (site.lowerName.equals("__invoke")) {
                    return result(callNode.call(closure.getFunction().getCallTarget(),
                            CallableDispatchNode.closureArguments(closure, values, null)), keepRef);
                }
                return result(MethodCallNode.closureMethod(node, site.name, closure, values), keepRef);
            }
            PhpObject object = (PhpObject) receiver;
            PhpClass phpClass = object.getPhpClass();
            return result(callNode.call(magicMethod(phpClass, "__call").getCallTarget(),
                    ArgumentsNode.frameArguments(object, phpClass, null,
                            Callables.magicValues(site.calledName, values))), keepRef);
        }

        public static boolean isByValue(PhpFunction function) {
            return MethodSite.byValue(function);
        }
    }

    /** `$obj->name(...)` with unpacked arguments (SpreadStart): as CallMethod, the frame arguments from the list. */
    @Operation
    @ConstantOperand(type = MethodSite.class)
    @ConstantOperand(type = boolean.class, name = "keepRef")
    public static final class CallMethodSpread {
        @Specialization(guards = {"!isByValue(function)", "function == cachedFunction"}, limit = "4")
        public static Object direct(MethodSite site, boolean keepRef, PhpObject receiver, PhpFunction function,
                                    SpreadArguments arguments, @Bind Node node,
                                    @Cached("function") PhpFunction cachedFunction,
                                    @Cached("create(cachedFunction.getCallTarget())") DirectCallNode callNode) {
            Object[] frame = arguments.frameArguments();
            frame[PhpFunction.ARG_THIS] = cachedFunction.isStatic() ? null : receiver;
            frame[PhpFunction.ARG_STATIC] = receiver.getPhpClass();
            if (arguments.isNamed()) {
                arguments.enter(frame, cachedFunction, node);
            }
            return result(callNode.call(frame), keepRef);
        }

        @Specialization(guards = "!isByValue(function)", replaces = "direct")
        public static Object indirect(MethodSite site, boolean keepRef, PhpObject receiver, PhpFunction function,
                                      SpreadArguments arguments, @Shared @Cached IndirectCallNode callNode,
                                      @Bind Node node) {
            Object[] frame = arguments.frameArguments();
            frame[PhpFunction.ARG_THIS] = function.isStatic() ? null : receiver;
            frame[PhpFunction.ARG_STATIC] = receiver.getPhpClass();
            if (arguments.isNamed()) {
                arguments.enter(frame, function, node);
            }
            return result(callNode.call(function.getCallTarget(), frame), keepRef);
        }

        @Specialization(guards = "!isByValue(function)")
        public static Object invoke(MethodSite site, boolean keepRef, PhpClosure receiver, PhpFunction function,
                                    SpreadArguments arguments, @Shared @Cached IndirectCallNode callNode,
                                    @Bind Node node) {
            Object[] frame = arguments.frameArguments();
            PhpObject self = receiver.getBoundThis();
            frame[PhpFunction.ARG_THIS] = self;
            frame[PhpFunction.ARG_STATIC] = self != null ? self.getPhpClass() : receiver.getScope();
            frame[PhpFunction.ARG_CLOSURE] = receiver;
            if (arguments.isNamed()) {
                arguments.enter(frame, function, node);
            }
            return result(callNode.call(function.getCallTarget(), frame), keepRef);
        }

        /** __call (or a closure of a method __call stands in for), its named arguments in its array; or bindTo... */
        @Specialization(guards = "isByValue(function)")
        public static Object byValue(MethodSite site, boolean keepRef, Object receiver, PhpFunction function,
                                     SpreadArguments arguments, @Shared @Cached IndirectCallNode callNode,
                                     @Bind Node node) {
            if (!arguments.isNamed()) {
                return CallMethod.byValue(site, keepRef, receiver, function, arguments.frameArguments(), callNode,
                        node);
            }
            if (receiver instanceof PhpClosure closure && !site.lowerName.equals("__invoke")) {
                // a Closure's own method (call, bindTo...): a built-in, bound by its arginfo names
                PhpFunction builtin = closureMethod(node, site.name);
                Object[] frame = arguments.frameArguments();
                frame[PhpFunction.ARG_THIS] = closure;
                return result(Backtrace.callFrom(node, () -> {
                    arguments.enter(frame, builtin, node);
                    return callNode.call(builtin.getCallTarget(), frame);
                }), keepRef);
            }
            if (receiver instanceof PhpClosure closure) {
                return result(callNode.call(closure.getFunction().getCallTarget(), ArgumentsNode.frameArguments(
                        closure.getBoundThis(), closure.staticClass(), closure,
                        arguments.magicValues(closure.getMagicName()))), keepRef);
            }
            PhpObject object = (PhpObject) receiver;
            PhpClass phpClass = object.getPhpClass();
            return result(callNode.call(magicMethod(phpClass, "__call").getCallTarget(),
                    ArgumentsNode.frameArguments(object, phpClass, null, arguments.magicValues(site.calledName))),
                    keepRef);
        }

        public static boolean isByValue(PhpFunction function) {
            return MethodSite.byValue(function);
        }
    }

    /** A call's arguments, without the frame's first slots. */
    private static Object[] values(Object[] arguments) {
        Object[] values = new Object[arguments.length - PhpFunction.ARG_FIRST];
        System.arraycopy(arguments, PhpFunction.ARG_FIRST, values, 0, values.length);
        return values;
    }

    private static Object result(Object returned, boolean keepRef) {
        return keepRef ? returned : callResult(returned);
    }

    @TruffleBoundary
    private static PhpFunction magicMethod(PhpClass phpClass, String lowerName) {
        return phpClass.findMethod(lowerName);
    }

    /**
     * The method `Cls::name(args)` calls, found before the arguments (INIT_STATIC_METHOD_CALL), inline-cached on the
     * class's key (StaticCallNode.Dispatch); BY_VALUE when __call or __callStatic stands in for it.
     */
    @Operation
    @ConstantOperand(type = MethodSite.class)
    public static final class ResolveStaticMethod {
        /** Cached as ResolveMethod's lookup. */
        @Specialization(guards = {"phpClass.getKey() == cachedKey", "keyed",
                "!scoped || root.sameScope(frame, cachedScope)"}, limit = "4", excludeForUncached = true)
        public static PhpFunction cached(VirtualFrame frame, MethodSite site, PhpClass phpClass,
                                         @Bind PhpBytecodeRootNode root,
                                         @Cached("phpClass.getKey()") ClassKey cachedKey,
                                         @Cached("root.callingScope(frame)") String cachedScope,
                                         @Cached("site.isKeyed(phpClass, cachedScope)") boolean keyed,
                                         @Cached("site.isScoped(root, phpClass)") boolean scoped,
                                         @Cached("find(site, phpClass, cachedScope)") PhpFunction method,
                                         @Shared @Cached SubclassTestNode subclassTest) {
            return checked(frame, site, phpClass, method, subclassTest, root);
        }

        @Specialization(replaces = "cached")
        public static PhpFunction generic(VirtualFrame frame, MethodSite site, PhpClass phpClass,
                                          @Bind PhpBytecodeRootNode root,
                                          @Shared @Cached SubclassTestNode subclassTest) {
            return checked(frame, site, phpClass, find(site, phpClass, root.callingScope(frame)), subclassTest,
                    root);
        }

        /**
         * The method from the scope (zend_std_get_static_method), BY_VALUE when __call or __callStatic stands in;
         * `Cls::__construct()` is the class's constructor, unchecked (INIT_STATIC_METHOD_CALL without a name).
         */
        public static PhpFunction find(MethodSite site, PhpClass phpClass, String scope) {
            PhpFunction method = site.constructor ? StaticCallNode.constructorOf(phpClass)
                    : phpClass.staticMethodFrom(site.name, scope);
            return method == null ? MethodSite.BY_VALUE : method;
        }

        /**
         * A non-static method needs the caller's $this, an instance of the class; a private constructor, a $this of
         * its own class.
         */
        private static PhpFunction checked(VirtualFrame frame, MethodSite site, PhpClass phpClass,
                                           PhpFunction method, SubclassTestNode subclassTest,
                                           PhpBytecodeRootNode root) {
            Object[] caller = frame.getArguments();
            if (MethodSite.byValue(method)) {
                // zend_std_get_static_method: __call on the caller's $this, else __callStatic, else the error
                // before the arguments
                if (!site.constructor && magicMethod(phpClass, "__callstatic") == null
                        && (magicMethod(phpClass, "__call") == null
                        || CallStatic.callerThis(caller, phpClass, subclassTest) == null)) {
                    throw phpClass.callError(site.name, root.callingScope(frame));
                }
                return method;
            }
            if (method.isStatic()) {
                return method;
            }
            if (site.constructor) {
                StaticCallNode.checkPrivateConstructor(phpClass, method, caller[PhpFunction.ARG_THIS]);
            }
            if (CallStatic.callerThis(caller, phpClass, subclassTest) == null) {
                throw PhpClass.nonStaticCall(method);
            }
            return method;
        }
    }

    /**
     * `Cls::name(args)` once the arguments are sent (StaticCallNode): a non-static method gets the caller's $this
     * when it is an instance of the class; a forwarding call (self::, parent::, static::) keeps the caller's
     * late-static-binding class. By value: __call on that $this, else __callStatic.
     */
    @Operation
    @ConstantOperand(type = MethodSite.class)
    @ConstantOperand(type = boolean.class, name = "keepRef")
    public static final class CallStatic {
        @Specialization(guards = {"!isByValue(function)", "function == cachedFunction"}, limit = "4")
        public static Object direct(VirtualFrame frame, MethodSite site, boolean keepRef, PhpClass phpClass,
                                    PhpFunction function,
                                    @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                    @Cached("function") PhpFunction cachedFunction,
                                    @Cached("create(cachedFunction.getCallTarget())") DirectCallNode callNode,
                                    @Shared @Cached SubclassTestNode subclassTest) {
            Object[] caller = frame.getArguments();
            arguments[PhpFunction.ARG_THIS] = cachedFunction.isStatic() ? null
                    : callerThis(caller, phpClass, subclassTest);
            arguments[PhpFunction.ARG_STATIC] = staticClass(site, caller, phpClass);
            return result(callNode.call(arguments), keepRef);
        }

        @Specialization(guards = "!isByValue(function)", replaces = "direct")
        public static Object indirect(VirtualFrame frame, MethodSite site, boolean keepRef, PhpClass phpClass,
                                      PhpFunction function,
                                      @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                      @Shared @Cached IndirectCallNode callNode,
                                      @Shared @Cached SubclassTestNode subclassTest) {
            Object[] caller = frame.getArguments();
            arguments[PhpFunction.ARG_THIS] = function.isStatic() ? null
                    : callerThis(caller, phpClass, subclassTest);
            arguments[PhpFunction.ARG_STATIC] = staticClass(site, caller, phpClass);
            return result(callNode.call(function.getCallTarget(), arguments), keepRef);
        }

        @Specialization(guards = "isByValue(function)")
        public static Object magic(VirtualFrame frame, MethodSite site, boolean keepRef, PhpClass phpClass,
                                   PhpFunction function,
                                   @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                   @Shared @Cached IndirectCallNode callNode,
                                   @Shared @Cached SubclassTestNode subclassTest,
                                   @Bind PhpBytecodeRootNode root) {
            return callMagic(frame, site, keepRef, phpClass,
                    Callables.magicValues(site.calledName, values(arguments)), callNode, subclassTest, root);
        }

        /** __call on the caller's $this, else __callStatic, with its two arguments values. */
        static Object callMagic(VirtualFrame frame, MethodSite site, boolean keepRef, PhpClass phpClass,
                                Object[] values, IndirectCallNode callNode, SubclassTestNode subclassTest,
                                PhpBytecodeRootNode root) {
            Object[] caller = frame.getArguments();
            Object self = callerThis(caller, phpClass, subclassTest);
            PhpClass staticClass = staticClass(site, caller, phpClass);
            PhpFunction viaCall = self != null ? magicMethod(phpClass, "__call") : null;
            if (viaCall != null) {
                return result(callNode.call(viaCall.getCallTarget(),
                        ArgumentsNode.frameArguments(self, staticClass, null, values)), keepRef);
            }
            PhpFunction viaStatic = magicMethod(phpClass, "__callstatic");
            if (viaStatic == null) {
                throw phpClass.callError(site.name, root.callingScope(frame));
            }
            return result(callNode.call(viaStatic.getCallTarget(),
                    ArgumentsNode.frameArguments(null, staticClass, null, values)), keepRef);
        }

        /** The caller's $this when it is an instance of the class. */
        static Object callerThis(Object[] caller, PhpClass phpClass, SubclassTestNode subclassTest) {
            Object self = caller[PhpFunction.ARG_THIS];
            if (self instanceof PhpObject object && !subclassTest.execute(object.getPhpClass(), phpClass)) {
                return null;
            }
            return self;
        }

        private static PhpClass staticClass(MethodSite site, Object[] caller, PhpClass phpClass) {
            if (site.forwarding && caller[PhpFunction.ARG_STATIC] instanceof PhpClass callerStatic) {
                return callerStatic;
            }
            return phpClass;
        }

        public static boolean isByValue(PhpFunction function) {
            return MethodSite.byValue(function);
        }
    }

    /** `Cls::name(...)` with unpacked arguments (SpreadStart): as CallStatic, the frame arguments from the list. */
    @Operation
    @ConstantOperand(type = MethodSite.class)
    @ConstantOperand(type = boolean.class, name = "keepRef")
    public static final class CallStaticSpread {
        @Specialization(guards = {"!isByValue(function)", "function == cachedFunction"}, limit = "4")
        public static Object direct(VirtualFrame frame, MethodSite site, boolean keepRef, PhpClass phpClass,
                                    PhpFunction function, SpreadArguments arguments, @Bind Node node,
                                    @Cached("function") PhpFunction cachedFunction,
                                    @Cached("create(cachedFunction.getCallTarget())") DirectCallNode callNode,
                                    @Shared @Cached SubclassTestNode subclassTest) {
            Object[] callee = arguments.frameArguments();
            Object[] caller = frame.getArguments();
            callee[PhpFunction.ARG_THIS] = cachedFunction.isStatic() ? null
                    : CallStatic.callerThis(caller, phpClass, subclassTest);
            callee[PhpFunction.ARG_STATIC] = CallStatic.staticClass(site, caller, phpClass);
            if (arguments.isNamed()) {
                arguments.enter(callee, cachedFunction, node);
            }
            return result(callNode.call(callee), keepRef);
        }

        @Specialization(guards = "!isByValue(function)", replaces = "direct")
        public static Object indirect(VirtualFrame frame, MethodSite site, boolean keepRef, PhpClass phpClass,
                                      PhpFunction function, SpreadArguments arguments, @Bind Node node,
                                      @Shared @Cached IndirectCallNode callNode,
                                      @Shared @Cached SubclassTestNode subclassTest) {
            Object[] callee = arguments.frameArguments();
            Object[] caller = frame.getArguments();
            callee[PhpFunction.ARG_THIS] = function.isStatic() ? null
                    : CallStatic.callerThis(caller, phpClass, subclassTest);
            callee[PhpFunction.ARG_STATIC] = CallStatic.staticClass(site, caller, phpClass);
            if (arguments.isNamed()) {
                arguments.enter(callee, function, node);
            }
            return result(callNode.call(function.getCallTarget(), callee), keepRef);
        }

        @Specialization(guards = "isByValue(function)")
        public static Object magic(VirtualFrame frame, MethodSite site, boolean keepRef, PhpClass phpClass,
                                   PhpFunction function, SpreadArguments arguments,
                                   @Shared @Cached IndirectCallNode callNode,
                                   @Shared @Cached SubclassTestNode subclassTest,
                                   @Bind PhpBytecodeRootNode root) {
            return CallStatic.callMagic(frame, site, keepRef, phpClass, arguments.magicValues(site.calledName),
                    callNode, subclassTest, root);
        }

        public static boolean isByValue(PhpFunction function) {
            return MethodSite.byValue(function);
        }
    }

    /**
     * A class by name (ClassRefNode.named): with one context the class found first stays (classes can't be
     * redeclared); the uncached interpreter looks it up each time.
     */
    @Operation
    @ConstantOperand(type = ClassSite.class)
    public static final class ResolveClass {
        @Specialization(assumptions = "singleContext()", excludeForUncached = true)
        public static PhpClass single(ClassSite site, @Cached("site.find(context())") PhpClass phpClass) {
            return phpClass;
        }

        @Specialization(replaces = "single")
        public static PhpClass perContext(ClassSite site, @Bind Node node) {
            return site.find(PhpContext.get(node));
        }

        public static Assumption singleContext() {
            return PhpLanguage.get(null).singleContext();
        }

        public static PhpContext context() {
            return PhpContext.get(null);
        }
    }

    /** `static` (ClassRefNode.lateStatic): the called class the frame was passed, else the class written in. */
    @Operation
    @ConstantOperand(type = ClassSite.class)
    public static final class ResolveLateStatic {
        @Specialization
        public static PhpClass resolve(VirtualFrame frame, ClassSite site, @Bind Node node) {
            if (frame.getArguments()[PhpFunction.ARG_STATIC] instanceof PhpClass phpClass) {
                return phpClass;
            }
            return site.find(PhpContext.get(node));
        }
    }

    /** `$class::...`, `new $class`: an object's class, or the class a name names (ClassRefNode.classOf). */
    @Operation
    public static final class ClassOf {
        @Specialization
        public static PhpClass of(Object value, @Bind Node node) {
            return ClassRefNode.classOf(node, value);
        }
    }

    /** `Cls::NAME` (ClassConstNode): with one context, a named class's value stays once found. */
    @Operation
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = boolean.class, name = "cacheable")
    public static final class ClassConstant {
        /** A constant that isn't public, read in a closure (whose bound scope may change), is looked up per call. */
        @Specialization(guards = {"cacheable", "phpClass == cachedClass", "!scoped"}, limit = "1",
                assumptions = "singleContext()", excludeForUncached = true)
        public static Object single(VirtualFrame frame, String name, boolean cacheable, PhpClass phpClass,
                                    @Bind PhpBytecodeRootNode root,
                                    @Cached("phpClass") PhpClass cachedClass,
                                    @Cached("scoped(root, cachedClass, name)") boolean scoped,
                                    @Cached("classConstant(cachedClass, name, root.callingScope(frame))") Object value) {
            return value;
        }

        @Specialization(replaces = "single")
        public static Object perCall(VirtualFrame frame, String name, boolean cacheable, PhpClass phpClass,
                                     @Bind PhpBytecodeRootNode root) {
            return ConstantNodes.classConstant(phpClass, name, root.callingScope(frame));
        }

        public static Object classConstant(PhpClass phpClass, String name, String scope) {
            return ConstantNodes.classConstant(phpClass, name, scope);
        }

        public static boolean scoped(PhpBytecodeRootNode root, PhpClass phpClass, String name) {
            return root.isClosure() && phpClass.isRestrictedConstant(name);
        }

        public static Assumption singleContext() {
            return PhpLanguage.get(null).singleContext();
        }
    }

    /** `$obj::class` (ObjectClassNameNode): a value that isn't an object is a TypeError. */
    @Operation
    public static final class ObjectClassName {
        @Specialization
        public static TruffleString name(Object value) {
            return ConstantNodes.objectClassName(value);
        }
    }

    /**
     * A global constant the translation doesn't fold (GlobalConstNode): with one context the value found first stays
     * (constants can't be redefined); the uncached interpreter looks it up each time. An undefined one is an Error.
     */
    @Operation
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = int.class, name = "id")
    @ConstantOperand(type = int.class, name = "fallbackId")
    public static final class GlobalConstant {
        @Specialization(assumptions = "singleContext()", excludeForUncached = true)
        public static Object single(String name, int id, int fallbackId,
                                    @Cached("find(context(), name, id, fallbackId)") Object value) {
            return value;
        }

        @Specialization(replaces = "single")
        public static Object perContext(String name, int id, int fallbackId, @Bind Node node) {
            return find(PhpContext.get(node), name, id, fallbackId);
        }

        public static Object find(PhpContext context, String name, int id, int fallbackId) {
            return ConstantNodes.globalConstant(context, name, id, fallbackId);
        }

        public static Assumption singleContext() {
            return PhpLanguage.get(null).singleContext();
        }

        public static PhpContext context() {
            return PhpContext.get(null);
        }
    }

    /** A deprecated predefined constant (DeprecatedConstNode): the deprecation raised at every fetch. */
    @Operation
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = int.class, name = "id")
    @ConstantOperand(type = int.class, name = "fallbackId")
    @ConstantOperand(type = TruffleString.class, name = "deprecation")
    @ConstantOperand(type = int.class, name = "line")
    public static final class DeprecatedConstant {
        @Specialization
        public static Object fetch(String name, int id, int fallbackId, TruffleString deprecation, int line,
                                   @Bind Node node) {
            return ConstantNodes.DeprecatedConstNode.fetch(node, name, id, fallbackId, deprecation, line);
        }
    }

    /** `static::class` (ClassNameNode). */
    @Operation
    public static final class ClassName {
        @Specialization
        public static TruffleString name(PhpClass phpClass) {
            return Str.of(phpClass.getName());
        }
    }

    /** Whether a `?->`'s object is null (ZEND_JMP_NULL), which short-circuits the rest of its chain. */
    @Operation
    public static final class IsNull {
        @Specialization
        public static boolean isNull(Object value) {
            return value == PhpNull.NULL;
        }
    }

    /** `$x instanceof Name` (InstanceofNode): the class need not be loaded; a Closure is an instance of Closure. */
    @Operation
    @ConstantOperand(type = String.class, name = "lowerName")
    public static final class InstanceOf {
        @Specialization
        public static boolean object(String lowerName, PhpObject value, @Cached SubclassTestNode subclassTest) {
            return subclassTest.execute(value.getPhpClass(), lowerName);
        }

        @Specialization
        public static boolean closure(String lowerName, PhpClosure value) {
            return lowerName.equals("closure");
        }

        @Fallback
        public static boolean other(String lowerName, Object value) {
            return false;
        }
    }

    /** `$x instanceof $class` (DynamicInstanceofNode), and `instanceof static`'s class. */
    @Operation
    public static final class InstanceOfValue {
        @Specialization
        public static boolean test(Object value, Object target) {
            return TestNodes.DynamicInstanceofNode.test(value, target);
        }
    }

    /**
     * `new Cls(args)` (NewNode): the object, with its declared properties at their defaults (InstantiateNode),
     * before the arguments.
     */
    @Operation
    public static final class Instantiate {
        @Specialization(excludeForUncached = true)
        public static PhpObject cached(PhpClass phpClass, @Cached(neverDefault = true) InstantiateNode instantiate) {
            return owned(instantiate.execute(phpClass));
        }

        @Specialization(replaces = "cached")
        public static PhpObject uncached(PhpClass phpClass, @Bind Node node) {
            return owned(InstantiateNode.instantiate(phpClass, node));
        }

        /** NEW's result: the temporary holds the object's first count, before the constructor runs. */
        private static PhpObject owned(PhpObject object) {
            object.addRef();
            return object;
        }
    }

    /**
     * The constructor a `new` calls, for its arguments (NEW resolves it first); BY_VALUE for a class without one,
     * whose arguments are evaluated and dropped.
     */
    @Operation
    public static final class ResolveConstructor {
        @Specialization
        public static PhpFunction constructor(VirtualFrame frame, PhpObject object, @Bind PhpBytecodeRootNode root) {
            PhpFunction constructor = object.getPhpClass().getConstructor();
            if (constructor == null) {
                return MethodSite.BY_VALUE;
            }
            if (!constructor.isPublic()) {
                NewNode.checkConstructor(object.getPhpClass(), constructor, root.callingScope(frame));
            }
            return constructor;
        }
    }

    /**
     * The constructor's call on the new object (NewNode.Construct): $this the object, the called class its; the
     * object is the value.
     */
    @Operation
    public static final class Construct {
        @Specialization(guards = {"!isByValue(constructor)", "constructor == cachedConstructor"}, limit = "4")
        public static PhpObject direct(PhpObject object, PhpFunction constructor,
                                       @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                       @Cached("constructor") PhpFunction cachedConstructor,
                                       @Cached("create(cachedConstructor.getCallTarget())") DirectCallNode callNode) {
            arguments[PhpFunction.ARG_THIS] = object;
            arguments[PhpFunction.ARG_STATIC] = object.getPhpClass();
            callNode.call(arguments);
            return object;
        }

        @Specialization(guards = "!isByValue(constructor)", replaces = "direct")
        public static PhpObject indirect(PhpObject object, PhpFunction constructor,
                                         @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                         @Cached IndirectCallNode callNode) {
            arguments[PhpFunction.ARG_THIS] = object;
            arguments[PhpFunction.ARG_STATIC] = object.getPhpClass();
            callNode.call(constructor.getCallTarget(), arguments);
            return object;
        }

        @Specialization(guards = "isByValue(constructor)")
        public static PhpObject withoutConstructor(PhpObject object, PhpFunction constructor,
                                                   @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments) {
            return object;
        }

        public static boolean isByValue(PhpFunction function) {
            return MethodSite.byValue(function);
        }
    }

    /** `new Cls(...)` with unpacked arguments (SpreadStart): as Construct, the frame arguments from the list. */
    @Operation
    public static final class ConstructSpread {
        @Specialization(guards = {"!isByValue(constructor)", "constructor == cachedConstructor"}, limit = "4")
        public static PhpObject direct(PhpObject object, PhpFunction constructor, SpreadArguments arguments,
                                       @Bind Node node, @Cached("constructor") PhpFunction cachedConstructor,
                                       @Cached("create(cachedConstructor.getCallTarget())") DirectCallNode callNode) {
            Object[] frame = arguments.frameArguments();
            frame[PhpFunction.ARG_THIS] = object;
            frame[PhpFunction.ARG_STATIC] = object.getPhpClass();
            if (arguments.isNamed()) {
                arguments.enter(frame, cachedConstructor, node);
            }
            callNode.call(frame);
            return object;
        }

        @Specialization(guards = "!isByValue(constructor)", replaces = "direct")
        public static PhpObject indirect(PhpObject object, PhpFunction constructor, SpreadArguments arguments,
                                         @Bind Node node, @Cached IndirectCallNode callNode) {
            Object[] frame = arguments.frameArguments();
            frame[PhpFunction.ARG_THIS] = object;
            frame[PhpFunction.ARG_STATIC] = object.getPhpClass();
            if (arguments.isNamed()) {
                arguments.enter(frame, constructor, node);
            }
            callNode.call(constructor.getCallTarget(), frame);
            return object;
        }

        @Specialization(guards = "isByValue(constructor)")
        public static PhpObject withoutConstructor(PhpObject object, PhpFunction constructor,
                                                   SpreadArguments arguments) {
            return object;
        }

        public static boolean isByValue(PhpFunction function) {
            return MethodSite.byValue(function);
        }
    }

    /** A match whose subject no arm took, without a default arm (MatchNode): UnhandledMatchError at errorLine. */
    @Operation
    @ConstantOperand(type = int.class, name = "errorLine")
    public static final class MatchError {
        @Specialization
        public static Object fail(int errorLine, Object subject, @Bind Node node) {
            throw MatchNode.unhandled(node, subject, errorLine);
        }
    }

    /** count($x) / sizeof($x) compiled in (CountNode): Countable objects and errors go to the built-in's body. */
    @Operation
    public static final class Count {
        @Specialization
        public static long array(PhpArray array) {
            return array.size();
        }

        @Fallback
        public static Object other(Object value, @Bind Node node) {
            Object[] arguments = new Object[PhpFunction.ARG_FIRST + 1];
            arguments[PhpFunction.ARG_FIRST] = value;
            return BuiltinNodes.BuiltinNode.callInPlace(node, "count", arguments);
        }
    }

    // ---- built-ins compiled in place (the tree's Intrinsics), BytecodeTranslator.intrinsic ----

    /**
     * The built-in's own call, for the cases a built-in compiled in place leaves out: from this instruction, whose
     * CallInfo traces find (frameless where php-src compiles the call into an opcode of its own). values are the
     * arguments the call passed.
     */
    static Object builtin(IndirectCallNode callNode, Node node, CallInfo call, Object... values) {
        Object[] arguments = new Object[PhpFunction.ARG_FIRST + values.length];
        System.arraycopy(values, 0, arguments, PhpFunction.ARG_FIRST, values.length);
        return callResult(callNode.call(call.find(PhpContext.get(node)).getCallTarget(), arguments));
    }

    /** is_array(), is_string()... of one argument: the built-in's test (CoreFunctions). */
    @Operation
    @ConstantOperand(type = int.class)
    public static final class TypeTest {
        public static final int ARRAY = 0;
        public static final int STRING = 1;
        public static final int INT = 2;
        public static final int FLOAT = 3;
        public static final int BOOL = 4;
        public static final int NULL = 5;
        public static final int OBJECT = 6;

        @Specialization
        public static boolean test(int type, Object value) {
            return switch (type) {
                case ARRAY -> value instanceof PhpArray;
                case STRING -> value instanceof TruffleString;
                case INT -> value instanceof Long;
                case FLOAT -> value instanceof Double;
                case BOOL -> value instanceof Boolean;
                case NULL -> value == PhpNull.NULL;
                default -> value instanceof PhpObject || value instanceof PhpClosure;
            };
        }
    }

    @Operation
    @ConstantOperand(type = CallInfo.class)
    public static final class Strlen {
        @Specialization
        public static long string(CallInfo call, TruffleString s) {
            return s.byteLength(Str.ENC);
        }

        @Fallback
        public static Object other(CallInfo call, Object value, @Bind Node node,
                                   @Cached IndirectCallNode callNode) {
            return builtin(callNode, node, call, value);
        }
    }

    /** current() / pos() / key() of an array: the element or key at its internal pointer. */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    @ConstantOperand(type = boolean.class, name = "wantKey")
    public static final class Current {
        @Specialization
        public static Object array(CallInfo call, boolean wantKey, PhpArray array) {
            int p = array.pointer();
            if (!array.isValidPosition(p)) {
                return wantKey ? PhpNull.NULL : Boolean.FALSE;
            }
            return wantKey ? array.keyAt(p) : array.valueAt(p);
        }

        @Fallback
        public static Object other(CallInfo call, boolean wantKey, Object value, @Bind Node node,
                                   @Cached IndirectCallNode callNode) {
            return builtin(callNode, node, call, value);
        }
    }

    /**
     * next() (1), prev() (-1), reset() (0), end() (2) of an array's reference (ArrayFunctions.move): the array
     * separated first (the pointer is part of it), the pointer moved, the new current value.
     */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    @ConstantOperand(type = int.class, name = "how")
    public static final class Move {
        @Specialization(guards = "isArrayRef(ref)")
        public static Object array(CallInfo call, int how, Object function, Object ref) {
            return moved(((PhpRef) ref).arrayForWrite(), how);
        }

        static Object moved(PhpArray array, int how) {
            int p = array.pointer();
            switch (how) {
                case 1 -> p = array.isValidPosition(p) ? array.nextPosition(p) : -1;
                case -1 -> p = array.isValidPosition(p) ? array.previousPosition(p) : -1;
                case 0 -> p = array.firstPosition();
                default -> p = array.lastPosition();
            }
            array.setPointer(p);
            return array.isValidPosition(p) ? array.valueAt(p) : Boolean.FALSE;
        }

        /** A by-reference argument that is a reference to an array. */
        public static boolean isArrayRef(Object value) {
            return value instanceof PhpRef ref && ref.get() instanceof PhpArray;
        }

        @Fallback
        public static Object other(CallInfo call, int how, Object function, Object ref, @Bind Node node,
                                   @Cached IndirectCallNode callNode) {
            return builtin(callNode, node, call, ref);
        }
    }

    /**
     * next/prev/reset/end (how 1, -1, 0, 2), array_pop, array_push and array_unshift of one value (POP, PUSH, UNSHIFT)
     * on a variable: its array changed in place as through the reference the call passes (separated if shared), but
     * the variable not made one. Anything but an array goes to the built-in with the variable's reference.
     */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    @ConstantOperand(type = int.class, name = "how")
    @ConstantOperand(type = LocalAccessor.class)
    public static final class ArrayOnVar {
        public static final int POP = 3;
        public static final int PUSH = 4;
        public static final int UNSHIFT = 5;

        @Specialization
        public static Object onVariable(VirtualFrame frame, CallInfo call, int how, LocalAccessor variable,
                                        @Variadic Object[] values, @Bind BytecodeNode bytecode, @Bind Node node,
                                        @Cached InlinedBranchProfile sharedArray,
                                        @Cached InlinedBranchProfile notArray,
                                        @Cached IndirectCallNode callNode) {
            Object stored = Locals.stored(frame, variable, bytecode);
            PhpArray array;
            if (stored instanceof PhpArray own) {
                array = own;
                if (own.isShared()) {
                    sharedArray.enter(node);
                    array = Variables.separated(own);
                    variable.setObject(bytecode, frame, array);
                }
            } else if (stored instanceof PhpRef ref && ref.get() instanceof PhpArray) {
                array = ref.arrayForWrite();
            } else {
                notArray.enter(node);
                PhpRef ref = Locals.makeRef(frame, variable, bytecode);
                return how >= PUSH ? builtin(callNode, node, call, ref, values[0]) : builtin(callNode, node, call, ref);
            }
            switch (how) {
                case POP:
                    return array.pop();
                case PUSH:
                    array.append(values[0]);
                    return (long) array.size();
                case UNSHIFT:
                    array.prepend(values[0] == array ? array.copy() : values[0]);
                    return (long) array.size();
                default:
                    return Move.moved(array, how);
            }
        }
    }

    /** array_pop() of an array's reference. */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    public static final class ArrayPop {
        @Specialization(guards = "isArrayRef(ref)")
        public static Object array(CallInfo call, Object function, Object ref) {
            return ((PhpRef) ref).arrayForWrite().pop();
        }

        /** A by-reference argument that is a reference to an array. */
        public static boolean isArrayRef(Object value) {
            return value instanceof PhpRef ref && ref.get() instanceof PhpArray;
        }

        @Fallback
        public static Object other(CallInfo call, Object function, Object ref, @Bind Node node,
                                   @Cached IndirectCallNode callNode) {
            return builtin(callNode, node, call, ref);
        }
    }

    /** array_push() of one value onto an array's reference. */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    public static final class ArrayPush {
        @Specialization(guards = "isArrayRef(ref)")
        public static long array(CallInfo call, Object function, Object ref, Object value) {
            PhpArray array = ((PhpRef) ref).arrayForWrite();
            array.append(value);
            return array.size();
        }

        /** A by-reference argument that is a reference to an array. */
        public static boolean isArrayRef(Object value) {
            return value instanceof PhpRef ref && ref.get() instanceof PhpArray;
        }

        @Fallback
        public static Object other(CallInfo call, Object function, Object ref, Object value, @Bind Node node,
                                   @Cached IndirectCallNode callNode) {
            return builtin(callNode, node, call, ref, value);
        }
    }

    /** array_unshift() of one value onto an array's reference; a packed list shifts its elements in place. */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    public static final class ArrayUnshift {
        @Specialization(guards = "isArrayRef(ref)")
        public static long array(CallInfo call, Object function, Object ref, Object value) {
            PhpArray array = ((PhpRef) ref).arrayForWrite();
            array.prepend(value == array ? array.copy() : value);
            return array.size();
        }

        /** A by-reference argument that is a reference to an array. */
        public static boolean isArrayRef(Object value) {
            return value instanceof PhpRef ref && ref.get() instanceof PhpArray;
        }

        @Fallback
        public static Object other(CallInfo call, Object function, Object ref, Object value, @Bind Node node,
                                   @Cached IndirectCallNode callNode) {
            return builtin(callNode, node, call, ref, value);
        }
    }

    /**
     * substr(string, int[, ?int]): a lazy view of the bytes, with php-src's offset and length rules (passed: the
     * arguments the call passed, length null when 2).
     */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    @ConstantOperand(type = int.class, name = "passed")
    public static final class Substr {
        @Specialization(guards = "isLengthOrNull(length)")
        public static TruffleString string(CallInfo call, int passed, TruffleString s, long from, Object length,
                                           @Cached TruffleString.SubstringByteIndexNode substring) {
            int n = s.byteLength(Str.ENC);
            long start = from < 0 ? Math.max(0, n + from) : from;
            if (start > n) {
                return Str.EMPTY;
            }
            long end;
            if (length instanceof Long count) {
                if (count < 0) {
                    end = n + count;
                    if (end < start) {
                        return Str.EMPTY;
                    }
                } else {
                    end = Math.min(n, start + count);
                }
            } else {
                end = n;
            }
            if (start == 0 && end == n) {
                return s;
            }
            return substring.execute(s, (int) start, (int) (end - start), Str.ENC, true);
        }

        public static boolean isLengthOrNull(Object length) {
            return length instanceof Long || length == PhpNull.NULL;
        }

        @Fallback
        public static Object other(CallInfo call, int passed, Object s, Object from, Object length, @Bind Node node,
                                   @Cached IndirectCallNode callNode) {
            return passed == 2 ? builtin(callNode, node, call, s, from)
                    : builtin(callNode, node, call, s, from, length);
        }
    }

    /** strpos($haystack, $needle): the byte offset of the first occurrence, or false. */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    public static final class Strpos {
        @Specialization
        public static Object strings(CallInfo call, TruffleString haystack, TruffleString needle,
                                     @Cached TruffleString.ByteIndexOfStringNode indexOf) {
            int length = haystack.byteLength(Str.ENC);
            int needleLength = needle.byteLength(Str.ENC);
            int index = needleLength == 0 ? 0
                    : needleLength > length ? -1 : indexOf.execute(haystack, needle, 0, length, Str.ENC);
            return index < 0 ? (Object) Boolean.FALSE : (long) index;
        }

        @Fallback
        public static Object other(CallInfo call, Object haystack, Object needle, @Bind Node node,
                                   @Cached IndirectCallNode callNode) {
            return builtin(callNode, node, call, haystack, needle);
        }
    }

    /** str_contains (0), str_starts_with (1), str_ends_with (2). */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    @ConstantOperand(type = int.class, name = "mode")
    public static final class Contains {
        @Specialization
        public static boolean strings(CallInfo call, int mode, TruffleString haystack, TruffleString needle,
                                      @Cached TruffleString.ByteIndexOfStringNode indexOf,
                                      @Cached TruffleString.RegionEqualByteIndexNode regionEqual) {
            int length = haystack.byteLength(Str.ENC);
            int needleLength = needle.byteLength(Str.ENC);
            if (needleLength == 0) {
                return true;
            }
            if (needleLength > length) {
                return false;
            }
            return switch (mode) {
                case 0 -> indexOf.execute(haystack, needle, 0, length, Str.ENC) >= 0;
                case 1 -> regionEqual.execute(haystack, 0, needle, 0, needleLength, Str.ENC);
                default -> regionEqual.execute(haystack, length - needleLength, needle, 0, needleLength, Str.ENC);
            };
        }

        @Fallback
        public static Object other(CallInfo call, int mode, Object haystack, Object needle, @Bind Node node,
                                   @Cached IndirectCallNode callNode) {
            return builtin(callNode, node, call, haystack, needle);
        }
    }

    /**
     * strspn / strcspn (accept: strspn): the length of the run at offset made of (or free of) mask bytes, scanned
     * in place; a literal mask's table is made once. passed: the arguments the call passed (offset 0 and length
     * null filled in).
     */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    @ConstantOperand(type = boolean.class, name = "accept")
    @ConstantOperand(type = int.class, name = "passed")
    public static final class Span {
        @Specialization(guards = {"mask == cachedMask", "isLengthOrNull(length)"}, limit = "1")
        public static long constantMask(CallInfo call, boolean accept, int passed, TruffleString s, TruffleString mask,
                                        long offset, Object length, @Cached("mask") TruffleString cachedMask,
                                        @Cached(value = "table(mask)", dimensions = 1) boolean[] table,
                                        @Shared @Cached TruffleString.GetInternalByteArrayNode bytes) {
            return span(s, table, offset, length, accept, bytes);
        }

        @Specialization(guards = "isLengthOrNull(length)", replaces = "constantMask")
        public static long anyMask(CallInfo call, boolean accept, int passed, TruffleString s, TruffleString mask,
                                   long offset, Object length,
                                   @Shared @Cached TruffleString.GetInternalByteArrayNode bytes) {
            return span(s, table(mask), offset, length, accept, bytes);
        }

        @TruffleBoundary
        static boolean[] table(TruffleString mask) {
            boolean[] set = new boolean[256];
            for (byte b : Str.readBytes(mask)) {
                set[b & 0xFF] = true;
            }
            return set;
        }

        private static long span(TruffleString s, boolean[] set, long offset, Object length, boolean accept,
                                 TruffleString.GetInternalByteArrayNode bytes) {
            InternalByteArray view = bytes.execute(s, Str.ENC);
            int n = view.getLength();
            long start = offset < 0 ? Math.max(0, n + offset) : Math.min(offset, n);
            long end;
            if (length instanceof Long len) {
                end = len < 0 ? Math.max(start, n + len) : Math.min(n, start + len);
            } else {
                end = n;
            }
            byte[] array = view.getArray();
            int base = view.getOffset();
            int i = (int) start;
            int limit = (int) end;
            while (i < limit && set[array[base + i] & 0xFF] == accept) {
                i++;
            }
            return i - start;
        }

        public static boolean isLengthOrNull(Object length) {
            return length instanceof Long || length == PhpNull.NULL;
        }

        @Fallback
        public static Object other(CallInfo call, boolean accept, int passed, Object s, Object mask, Object offset,
                                   Object length, @Bind Node node,
                                   @Cached IndirectCallNode callNode) {
            return switch (passed) {
                case 2 -> builtin(callNode, node, call, s, mask);
                case 3 -> builtin(callNode, node, call, s, mask, offset);
                default -> builtin(callNode, node, call, s, mask, offset, length);
            };
        }
    }

    /** strtolower / strtoupper: a vectorized scan for a letter to change; most strings have none. */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    @ConstantOperand(type = boolean.class, name = "upper")
    public static final class Case {
        private static final TruffleString.CodePointSet UPPERCASE = TruffleString.CodePointSet.fromRanges(
                new int[] {'A', 'Z'}, Str.ENC);
        private static final TruffleString.CodePointSet LOWERCASE = TruffleString.CodePointSet.fromRanges(
                new int[] {'a', 'z'}, Str.ENC);

        @Specialization
        public static TruffleString string(CallInfo call, boolean upper, TruffleString s,
                                           @Cached TruffleString.ByteIndexOfCodePointSetNode find) {
            if (find.execute(s, 0, s.byteLength(Str.ENC), upper ? LOWERCASE : UPPERCASE) < 0) {
                return s;
            }
            return convert(s, upper);
        }

        @TruffleBoundary
        private static TruffleString convert(TruffleString s, boolean upper) {
            return upper ? StringFunctions.upper(s) : StringFunctions.lower(s);
        }

        @Fallback
        public static Object other(CallInfo call, boolean upper, Object value, @Bind Node node,
                                   @Cached IndirectCallNode callNode) {
            return builtin(callNode, node, call, value);
        }
    }

    /** trim (3) / ltrim (1) / rtrim (2) with php-src's default characters " \n\r\t\v\0". */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    @ConstantOperand(type = int.class, name = "mode")
    public static final class Trim {
        @Specialization
        public static TruffleString string(CallInfo call, int mode, TruffleString s,
                                           @Cached TruffleString.GetInternalByteArrayNode bytes,
                                           @Cached TruffleString.SubstringByteIndexNode substring) {
            InternalByteArray internal = bytes.execute(s, Str.ENC);
            byte[] array = internal.getArray();
            int offset = internal.getOffset();
            int length = internal.getLength();
            int start = 0;
            int end = length;
            if ((mode & 1) != 0) {
                while (start < end && isTrimmed(array[offset + start])) {
                    start++;
                }
            }
            if ((mode & 2) != 0) {
                while (end > start && isTrimmed(array[offset + end - 1])) {
                    end--;
                }
            }
            if (start == 0 && end == length) {
                return s;
            }
            return substring.execute(s, start, end - start, Str.ENC, true);
        }

        private static boolean isTrimmed(byte b) {
            return b == ' ' || b == '\n' || b == '\r' || b == '\t' || b == 0x0B || b == 0;
        }

        @Fallback
        public static Object other(CallInfo call, int mode, Object value, @Bind Node node,
                                   @Cached IndirectCallNode callNode) {
            return builtin(callNode, node, call, value);
        }
    }

    /** ucfirst: unchanged unless the first byte is a lowercase ASCII letter. */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    public static final class Ucfirst {
        @Specialization
        public static TruffleString string(CallInfo call, TruffleString s,
                                           @Cached TruffleString.ReadByteNode read) {
            if (s.byteLength(Str.ENC) == 0) {
                return s;
            }
            int first = read.execute(s, 0, Str.ENC);
            return first >= 'a' && first <= 'z' ? upperFirst(s) : s;
        }

        @TruffleBoundary
        private static TruffleString upperFirst(TruffleString s) {
            return StringFunctions.caseFirst(s, true);
        }

        @Fallback
        public static Object other(CallInfo call, Object value, @Bind Node node,
                                   @Cached IndirectCallNode callNode) {
            return builtin(callNode, node, call, value);
        }
    }

    /**
     * str_replace(string, string, string): a vectorized search first; a subject without the search string (the
     * usual case) is returned as it is. The replacing itself, and arrays, go to the built-in.
     */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    public static final class StrReplace {
        @Specialization
        public static Object strings(CallInfo call, TruffleString search, TruffleString replacement,
                                     TruffleString subject, @Cached TruffleString.ByteIndexOfStringNode index,
                                     @Bind Node node,
                                   @Shared @Cached IndirectCallNode callNode) {
            int length = subject.byteLength(Str.ENC);
            int searchLength = search.byteLength(Str.ENC);
            if (searchLength == 0 || searchLength > length || index.execute(subject, search, 0, length, Str.ENC) < 0) {
                return subject;
            }
            return builtin(callNode, node, call, search, replacement, subject);
        }

        @Fallback
        public static Object other(CallInfo call, Object search, Object replacement, Object subject,
                                   @Bind Node node,
                                   @Shared @Cached IndirectCallNode callNode) {
            return builtin(callNode, node, call, search, replacement, subject);
        }
    }

    /** in_array(needle, array[, strict]) without the call (passed: the arguments the call passed). */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    @ConstantOperand(type = int.class, name = "passed")
    public static final class InArray {
        @Specialization
        public static boolean array(CallInfo call, int passed, Object needle, PhpArray haystack, boolean strict) {
            return ArrayFunctions.search(needle, haystack, strict) != null;
        }

        @Fallback
        public static Object other(CallInfo call, int passed, Object needle, Object haystack, Object strict,
                                   @Bind Node node,
                                   @Cached IndirectCallNode callNode) {
            return passed == 2 ? builtin(callNode, node, call, needle, haystack)
                    : builtin(callNode, node, call, needle, haystack, strict);
        }
    }

    /**
     * array_slice of a packed list with int bounds and renumbered keys (WP_Hook's argument trimming), built inline
     * as ArrayFunctions.slice builds it; passed: the arguments the call passed.
     */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    @ConstantOperand(type = int.class, name = "passed")
    public static final class ArraySlice {
        @Specialization(guards = {"array.isPacked()", "isLengthOrNull(length)", "!preserveKeys"})
        public static PhpArray packed(CallInfo call, int passed, PhpArray array, long offset, Object length,
                                      boolean preserveKeys) {
            int n = array.size();
            long start = offset < 0 ? Math.max(0, n + offset) : Math.min(offset, n);
            long end;
            if (length instanceof Long count) {
                end = count < 0 ? Math.max(start, n + count) : Math.min(n, start + count);
            } else {
                end = n;
            }
            if (start >= end) {
                return new PhpArray();
            }
            // one loop per layout, so the loop tests none (wiki/partial-evaluation.md, round 2)
            if (array.holdsLongs()) {
                PhpArray result = new PhpArray();
                for (int p = (int) start; p < end; p++) {
                    result.appendLong(array.longAt(p));
                }
                return result;
            }
            PhpArray result = PhpArray.ofCapacity((int) (end - start));
            for (int p = (int) start; p < end; p++) {
                result.appendCopied(array.copiedAt(p));
            }
            return result;
        }

        public static boolean isLengthOrNull(Object length) {
            return length instanceof Long || length == PhpNull.NULL;
        }

        @Fallback
        public static Object other(CallInfo call, int passed, Object array, Object offset, Object length,
                                   Object preserveKeys, @Bind Node node,
                                   @Cached IndirectCallNode callNode) {
            return switch (passed) {
                case 2 -> builtin(callNode, node, call, array, offset);
                case 3 -> builtin(callNode, node, call, array, offset, length);
                default -> builtin(callNode, node, call, array, offset, length, preserveKeys);
            };
        }
    }

    /** array_keys($a): a packed array's keys are 0..n-1, built unboxed. */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    public static final class ArrayKeys {
        @Specialization
        public static PhpArray array(CallInfo call, PhpArray array) {
            if (array.isPacked()) {
                PhpArray result = PhpArray.ofLongCapacity(array.size());
                for (int i = 0; i < array.size(); i++) {
                    result.appendLong(i);
                }
                return result;
            }
            PhpArray result = PhpArray.ofCapacity(array.size());
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                result.append(array.keyAt(p));
            }
            return result;
        }

        @Fallback
        public static Object other(CallInfo call, Object value, @Bind Node node,
                                   @Cached IndirectCallNode callNode) {
            return builtin(callNode, node, call, value);
        }
    }

    /** explode(separator, string) without a limit: a vectorized search for each piece; passed as above. */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    @ConstantOperand(type = int.class, name = "passed")
    public static final class Explode {
        @Specialization(guards = {"limit == NO_LIMIT", "separator.byteLength(ENC) > 0"})
        public static PhpArray split(CallInfo call, int passed, TruffleString separator, TruffleString s, long limit,
                                     @Cached TruffleString.ByteIndexOfStringNode index,
                                     @Cached TruffleString.SubstringByteIndexNode substring) {
            int separatorLength = separator.byteLength(Str.ENC);
            int length = s.byteLength(Str.ENC);
            PhpArray result = new PhpArray();
            int from = 0;
            while (from + separatorLength <= length) {
                int found = index.execute(s, separator, from, length, Str.ENC);
                if (found < 0) {
                    break;
                }
                result.append(substring.execute(s, from, found - from, Str.ENC, false));
                from = found + separatorLength;
            }
            result.append(substring.execute(s, from, length - from, Str.ENC, false));
            return result;
        }

        public static final long NO_LIMIT = Long.MAX_VALUE;
        public static final TruffleString.Encoding ENC = Str.ENC;

        @Fallback
        public static Object other(CallInfo call, int passed, Object separator, Object s, Object limit,
                                   @Bind Node node,
                                   @Cached IndirectCallNode callNode) {
            return passed == 2 ? builtin(callNode, node, call, separator, s)
                    : builtin(callNode, node, call, separator, s, limit);
        }
    }

    // ---- foreach by value (ForeachNode) ----

    /**
     * Starts a foreach over subject: an array is held by the loop while it runs, so writes in the body copy it and
     * the iteration is unaffected; state gets the array (position its first element) or an object's
     * {@link ObjectIteration}; anything else warns, and the loop runs no step.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "state")
    @ConstantOperand(type = LocalAccessor.class, name = "position")
    public static final class ForeachStart {
        @Specialization
        public static void array(VirtualFrame frame, LocalAccessor state, LocalAccessor position, PhpArray subject,
                                 @Bind BytecodeNode bytecode) {
            subject.addHolder();
            state.setObject(bytecode, frame, subject);
            position.setLong(bytecode, frame, subject.firstPosition());
        }

        @Specialization
        public static void object(VirtualFrame frame, LocalAccessor state, LocalAccessor position,
                                  PhpObject subject, @Bind BytecodeNode bytecode, @Bind Node node,
                                  @Bind PhpBytecodeRootNode root) {
            state.setObject(bytecode, frame, ObjectIteration.start(node, subject,
                    PropNode.callingScope(frame, root.scopeName)));
        }

        @Fallback
        public static void other(VirtualFrame frame, LocalAccessor state, LocalAccessor position, Object subject,
                                 @Bind BytecodeNode bytecode, @Bind Node node) {
            ForeachNode.notIterable(node, subject);
            state.setObject(bytecode, frame, PhpNull.NULL);
        }
    }

    /**
     * The next step of a foreach: binds the element's key (hasKey) and value and answers true, or false at the end.
     * An array binds by its layout, as ForeachNode.BindNode: a site has code only for the layouts it has met, and an
     * int list binds unboxed.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "position")
    @ConstantOperand(type = LocalAccessor.class, name = "value")
    @ConstantOperand(type = LocalAccessor.class, name = "key")
    @ConstantOperand(type = boolean.class, name = "hasKey")
    public static final class ForeachNext {
        @Specialization(guards = "array.holdsLongs()")
        public static boolean longs(VirtualFrame frame, LocalAccessor position, LocalAccessor value,
                                    LocalAccessor key, boolean hasKey, PhpArray array,
                                    @Bind BytecodeNode bytecode,
                @Shared("keyWrites") @Cached(neverDefault = true) Locals.Writes keyWrites,
                @Shared("valueWrites") @Cached(neverDefault = true) Locals.Writes valueWrites) {
                int p = Locals.position(frame, position, bytecode);
                if (p < 0) {
                return false;
            }
            if (hasKey) {
                keyWrites.writeLong(frame, key, bytecode, p);
            }
                valueWrites.writeLong(frame, value, bytecode, array.longAt(p));
            position.setLong(bytecode, frame, array.nextPackedPosition(p));
            return true;
        }

        @Specialization(guards = "array.holdsObjects()")
        public static boolean objects(VirtualFrame frame, LocalAccessor position, LocalAccessor value,
                                      LocalAccessor key, boolean hasKey, PhpArray array,
                                      @Bind BytecodeNode bytecode,
                @Shared("keyWrites") @Cached(neverDefault = true) Locals.Writes keyWrites,
                @Shared("valueWrites") @Cached(neverDefault = true) Locals.Writes valueWrites) {
                int p = Locals.position(frame, position, bytecode);
                if (p < 0) {
                return false;
            }
            if (hasKey) {
                keyWrites.writeLong(frame, key, bytecode, p);
            }
                valueWrites.write(frame, value, bytecode, PhpRef.deref(array.packedAt(p)));
            position.setLong(bytecode, frame, array.nextPackedPosition(p));
            return true;
        }

        @Specialization(guards = "!array.isPacked()")
        public static boolean hash(VirtualFrame frame, LocalAccessor position, LocalAccessor value,
                                   LocalAccessor key, boolean hasKey, PhpArray array, @Bind BytecodeNode bytecode,
                @Shared("keyWrites") @Cached(neverDefault = true) Locals.Writes keyWrites,
                @Shared("valueWrites") @Cached(neverDefault = true) Locals.Writes valueWrites) {
                int p = Locals.position(frame, position, bytecode);
                if (p < 0) {
                return false;
            }
            if (hasKey) {
                keyWrites.write(frame, key, bytecode, array.hashKeyAt(p));
            }
                valueWrites.write(frame, value, bytecode, PhpRef.deref(array.hashValueAt(p)));
            position.setLong(bytecode, frame, array.nextHashPosition(p));
            return true;
        }

        @Specialization
        public static boolean object(VirtualFrame frame, LocalAccessor position, LocalAccessor value,
                                     LocalAccessor key, boolean hasKey, ObjectIteration iteration,
                                     @Bind BytecodeNode bytecode, @Bind Node node,
                @Shared("keyWrites") @Cached(neverDefault = true) Locals.Writes keyWrites,
                @Shared("valueWrites") @Cached(neverDefault = true) Locals.Writes valueWrites) {
                return iteration.next(frame, value, key, hasKey, bytecode, node, keyWrites, valueWrites);
        }

        @Fallback
        public static boolean done(VirtualFrame frame, LocalAccessor position, LocalAccessor value,
                                   LocalAccessor key, boolean hasKey, Object state) {
            return false;
        }
    }

    /** However the loop ends: it lets go of the array it held. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "state")
    @ConstantOperand(type = LocalAccessor.class, name = "position")
    public static final class ForeachEnd {
        @Specialization
        public static void end(VirtualFrame frame, LocalAccessor state, LocalAccessor position,
                               @Bind BytecodeNode bytecode) {
            if (!state.isCleared(bytecode, frame) && state.getObject(bytecode, frame) instanceof PhpArray array) {
                array.removeHolder();
            }
            state.clear(bytecode, frame);
            position.clear(bytecode, frame);
        }
    }

    // ---- foreach by reference (ForeachNode's by-reference paths, RefIteration) ----

    /**
     * The subject of a foreach by reference that is a variable (ForeachNode.executeByRef, FE_RESET_RW on a CV): an
     * array makes the variable a reference, which the loop takes; any other value is passed on as it is.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    public static final class ForeachRefVar {
        @Specialization
        public static Object subject(VirtualFrame frame, LocalAccessor variable, @Bind BytecodeNode bytecode,
                                     @Bind Node node, @Cached InlinedBranchProfile undefined) {
            Object value = readValue(frame, variable, bytecode, node, undefined);
            return value instanceof PhpArray ? Locals.makeRef(frame, variable, bytecode) : value;
        }
    }

    /** `foreach ($obj->$name as &$v)`: as ForeachRefProp, the name computed (PropName). */
    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class ForeachRefPropNamed {
        @Specialization
        public static Object subject(VirtualFrame frame, PropSite site, Object object, String name,
                                     @Cached(value = "site.create()", uncached = "site.uncached()",
                                             neverDefault = true) PropNode access) {
            Object value = access.readOn(frame.getArguments(), object, name);
            return value instanceof PhpArray ? access.makeRefOn(frame.getArguments(), object, name) : value;
        }
    }

    /** As ForeachRefVar, for a property of an object (PropNode.execute, then makeRef for an array). */
    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class ForeachRefProp {
        @Specialization
        public static Object subject(VirtualFrame frame, PropSite site, Object object,
                                     @Cached(value = "site.create()", uncached = "site.uncached()",
                                             neverDefault = true) PropNode access) {
            Object value = access.readOn(frame.getArguments(), object, site.name);
            return value instanceof PhpArray ? access.makeRefOn(frame.getArguments(), object, site.name) : value;
        }
    }

    /**
     * Starts a foreach by reference (FE_RESET_RW): a reference holding an array, or an array (a temporary, which
     * the loop changes unseen), is iterated as RefIteration follows it; a Generator yielding by reference by its
     * references (GeneratorRefIteration); anything else warns, and the loop runs no step.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "state")
    public static final class ForeachRefStart {
        @Specialization
        public static void start(VirtualFrame frame, LocalAccessor state, Object subject,
                                 @Bind BytecodeNode bytecode, @Bind Node node, @Bind PhpBytecodeRootNode root) {
            Object value = subject instanceof PhpRef ref ? ref.get() : subject;
            if (value instanceof PhpArray) {
                PhpRef ref = subject instanceof PhpRef held ? held : PhpRef.unbound(value);
                state.setObject(bytecode, frame, RefIteration.start(PhpContext.get(node), ref));
            } else if (value instanceof PhpObject object) {
                state.setObject(bytecode, frame, GeneratorRefIteration.start(node, object,
                        PropNode.callingScope(frame, root.scopeName)));
            } else {
                ForeachNode.notIterable(node, value);
                state.setObject(bytecode, frame, PhpNull.NULL);
            }
        }
    }

    /**
     * The next step of a foreach by reference (FE_FETCH_RW): the value variable bound to the next element's
     * reference, then the key (hasKey); false at the end. An array is the one the loop's reference holds now
     * (RefIteration.current), which warns and ends the loop once it isn't one.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "value")
    @ConstantOperand(type = LocalAccessor.class, name = "key")
    @ConstantOperand(type = boolean.class, name = "hasKey")
    public static final class ForeachRefNext {
        @Specialization
        public static boolean array(VirtualFrame frame, LocalAccessor value, LocalAccessor key, boolean hasKey,
                                    RefIteration iteration, @Bind BytecodeNode bytecode, @Bind Node node,
                @Shared("keyWrites") @Cached(neverDefault = true) Locals.Writes keyWrites) {
                PhpArray array = iteration.current();
                if (array == null) {
                ForeachNode.notIterable(node, iteration.ref().get());
                return false;
            }
            int from = iteration.position();
            int p = from == RefIteration.END ? -1 : array.positionFrom(from);
            if (p < 0) {
                return false;
            }
            iteration.setPosition(p + 1);
            Locals.bindRef(frame, value, bytecode, array.refAtPosition(p));
            if (hasKey) {
                keyWrites.write(frame, key, bytecode, array.keyAt(p));
            }
            return true;
        }

        @Specialization
        public static boolean generator(VirtualFrame frame, LocalAccessor value, LocalAccessor key, boolean hasKey,
                                        GeneratorRefIteration iteration, @Bind BytecodeNode bytecode,
                                        @Bind Node node,
                @Shared("keyWrites") @Cached(neverDefault = true) Locals.Writes keyWrites) {
                if (!iteration.advance(node)) {
                return false;
            }
            Locals.bindRef(frame, value, bytecode, iteration.currentRef(node));
            if (hasKey) {
                keyWrites.write(frame, key, bytecode, PhpRef.deref(iteration.key(node)));
            }
            return true;
        }

        @Fallback
        public static boolean done(VirtualFrame frame, LocalAccessor value, LocalAccessor key, boolean hasKey,
                                   Object state) {
            return false;
        }
    }

    /** However the loop ends: it lets go of the reference and the iterator (FE_FREE). */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "state")
    public static final class ForeachRefEnd {
        @Specialization
        public static void end(VirtualFrame frame, LocalAccessor state, @Bind BytecodeNode bytecode,
                               @Bind Node node) {
            if (!state.isCleared(bytecode, frame) && state.getObject(bytecode, frame) instanceof RefIteration it) {
                it.end(PhpContext.get(node));
            }
            state.clear(bytecode, frame);
        }
    }

    // ---- generators ----

    /**
     * A generator function's call, once its parameters are bound (ZEND_GENERATOR_CREATE): the call returns the
     * Generator, suspended before the body's first statement, and keeps it in a local for the yields.
     */
    @Yield
    @ConstantOperand(type = LocalAccessor.class)
    public static final class GeneratorStart {
        @Specialization
        public static Object start(LocalAccessor generator, @Bind ContinuationRootNode continuation,
                                   @Bind MaterializedFrame frame, @Bind BytecodeNode bytecode,
                                   @Bind PhpBytecodeRootNode root, @Bind Node node) {
            PhpGenerator state = new PhpGenerator(continuation, frame, root.generatorByRef,
                    root.generatorHasFinally);
            generator.setObject(bytecode, frame, state);
            return state.newObject(PhpContext.get(node));
        }
    }

    /**
     * `yield`, `yield value`, `yield key => value` (ZEND_YIELD): records the key (null for the next integer key)
     * and the value and suspends; resuming continues here, Resumed giving the yield's result.
     */
    @Yield(resultOperandIndex = 1)
    @ConstantOperand(type = LocalAccessor.class)
    public static final class YieldValue {
        /**
         * The generator comes from its local, not the operand stack: in compiled code a yield copies the operands
         * below its own into the continuation's frame, and a statement's yield then has none.
         */
        @Specialization
        public static Object yield(LocalAccessor generator, Object key, Object value,
                                   @Bind ContinuationRootNode continuation, @Bind MaterializedFrame frame,
                                   @Bind BytecodeNode bytecode) {
            PhpGenerator state = (PhpGenerator) generator.getObject(bytecode, frame);
            state.yielded(key == PhpGenerator.SUSPENDED ? null : key, value);
            state.suspendAt(continuation);
            return PhpGenerator.SUSPENDED;
        }
    }

    /**
     * `yield from source` (ZEND_YIELD_FROM): starts delegating and suspends; the generator takes the elements, and
     * resumes the body here once they are done, Resumed giving the yield from's result.
     */
    @Yield
    @ConstantOperand(type = LocalAccessor.class)
    public static final class YieldFrom {
        @Specialization
        public static Object yieldFrom(LocalAccessor generator, Object source,
                                       @Bind ContinuationRootNode continuation, @Bind MaterializedFrame frame,
                                       @Bind BytecodeNode bytecode, @Bind Node node) {
            PhpGenerator state = (PhpGenerator) generator.getObject(bytecode, frame);
            state.yieldFrom(node, source);
            state.suspendAt(continuation);
            return PhpGenerator.DELEGATED;
        }
    }

    /** `&$x` as a value: the variable's reference, converting it into one if needed. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    public static final class RefVar {
        @Specialization
        public static PhpRef ref(VirtualFrame frame, LocalAccessor variable, @Bind BytecodeNode bytecode) {
            return Locals.makeRef(frame, variable, bytecode);
        }
    }

    // ---- references (`=&`, by-reference elements and properties, global, static, return by reference) ----

    /**
     * `&$x[k1]...[kn]`, `&$x[k]...[]` (DimNode.makeRef down a chain): the containers made writable from the
     * variable, then the element's reference, made one (null) if it is missing; a container on the way that
     * isn't an array can't give one.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = boolean.class, name = "append")
    public static final class RefNested {
        @Specialization
        public static PhpRef ref(VirtualFrame frame, LocalAccessor variable, boolean append, @Variadic Object[] keys,
                                 @Bind BytecodeNode bytecode, @Bind Node node,
                                 @Cached ElementNodes.ArrayKeyNode toKey,
                                 @Cached InlinedBranchProfile sharedArray,
                                 @Cached InlinedBranchProfile refArray,
                                 @Cached InlinedBranchProfile createdArray) {
            Object base = variableForWrite(frame, variable, bytecode, node, sharedArray, refArray, createdArray);
            return elementRef(node, base, keys, append, toKey);
        }
    }

    /** `&$obj->name[k1]...[kn]`: as RefNested, from the property (PropNode.arrayForWrite). */
    @Operation
    @ConstantOperand(type = PropSite.class)
    @ConstantOperand(type = boolean.class, name = "append")
    public static final class RefPropNested {
        @Specialization
        public static PhpRef ref(VirtualFrame frame, PropSite site, boolean append, Object object,
                                 @Variadic Object[] keys,
                                 @Cached(value = "site.create()", uncached = "site.uncached()",
                                         neverDefault = true) PropNode access,
                                 @Bind Node node, @Cached ElementNodes.ArrayKeyNode toKey) {
            String name = placeName(site, keys);
            keys = placeKeys(site, keys);
            return elementRef(node, propertyForWrite(frame, access, object, name), keys, append, toKey);
        }
    }

    /** `&Cls::$name[k1]...[kn]`, `&$_POST[k]...`: as RefNested, from the reference the place is kept in. */
    @Operation
    @ConstantOperand(type = boolean.class, name = "append")
    public static final class RefRefNested {
        @Specialization
        public static PhpRef ref(boolean append, PhpRef ref, @Variadic Object[] keys, @Bind Node node,
                                 @Cached ElementNodes.ArrayKeyNode toKey) {
            return elementRef(node, referenceForWrite(ref), keys, append, toKey);
        }
    }

    /** The reference of the element keys name from base (made writable), or of a new element (append). */
    private static PhpRef elementRef(Node node, Object base, Object[] keys, boolean append,
                                     ElementNodes.ArrayKeyNode toKey) {
        int levels = keys.length - 1;
        Object container = ElementChain.forWrite(node, base, keys, append ? keys.length : levels, toKey);
        if (!(container instanceof PhpArray array)) {
            return DimNode.overloadedRef(node, container, append ? PhpNull.NULL : keys[levels]);
        }
        return append ? array.appendRef() : array.refAt(toKey.execute(keys[levels]));
    }

    /** `&$obj->name` (PropNode.makeRef): the property's reference, which it becomes if it isn't one. */
    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class RefProp {
        @Specialization
        public static PhpRef ref(VirtualFrame frame, PropSite site, Object object,
                                 @Cached(value = "site.create()", uncached = "site.uncached()",
                                         neverDefault = true) PropNode access) {
            return access.makeRefOn(frame.getArguments(), object, site.name);
        }
    }

    /** `&$obj->$name`: as RefProp, the name computed (PropName). */
    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class RefPropNamed {
        @Specialization
        public static PhpRef ref(VirtualFrame frame, PropSite site, Object object, String name,
                                 @Cached(value = "site.create()", uncached = "site.uncached()",
                                         neverDefault = true) PropNode access) {
            return access.makeRefOn(frame.getArguments(), object, name);
        }
    }

    /**
     * `$x = &place` (RefAssignNode on a LocalVarNode, LocalVarNode.bindRef): the variable gives up what it held
     * and becomes the reference; the value is the reference's.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    public static final class BindVar {
        @Specialization
        public static Object bind(VirtualFrame frame, LocalAccessor variable, PhpRef ref,
                                  @Bind BytecodeNode bytecode) {
            Locals.bindRef(frame, variable, bytecode, ref);
            return ref.get();
        }
    }

    /**
     * `$x[k1]...[kn] = &place`, `$x[...][] = &place` (DimNode.bindRef): the containers made writable from the
     * variable, then the element becomes the reference.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = boolean.class, name = "append")
    public static final class BindNested {
        @Specialization
        public static Object bind(VirtualFrame frame, LocalAccessor variable, boolean append, PhpRef ref,
                                  @Variadic Object[] keys, @Bind BytecodeNode bytecode, @Bind Node node,
                                  @Cached ElementNodes.ArrayKeyNode toKey,
                                  @Cached InlinedBranchProfile sharedArray,
                                  @Cached InlinedBranchProfile refArray,
                                  @Cached InlinedBranchProfile createdArray) {
            Object base = variableForWrite(frame, variable, bytecode, node, sharedArray, refArray, createdArray);
            bindElement(node, base, keys, append, ref, toKey);
            return ref.get();
        }
    }

    /** `$obj->name[k1]...[kn] = &place`: as BindNested, from the property. */
    @Operation
    @ConstantOperand(type = PropSite.class)
    @ConstantOperand(type = boolean.class, name = "append")
    public static final class BindPropNested {
        @Specialization
        public static Object bind(VirtualFrame frame, PropSite site, boolean append, PhpRef ref, Object object,
                                  @Variadic Object[] keys,
                                  @Cached(value = "site.create()", uncached = "site.uncached()",
                                          neverDefault = true) PropNode access,
                                  @Bind Node node, @Cached ElementNodes.ArrayKeyNode toKey) {
            String name = placeName(site, keys);
            keys = placeKeys(site, keys);
            bindElement(node, propertyForWrite(frame, access, object, name), keys, append, ref, toKey);
            return ref.get();
        }
    }

    /** `Cls::$name[k1]...[kn] = &place`, of a global's element too: as BindNested, from the place's reference. */
    @Operation
    @ConstantOperand(type = boolean.class, name = "append")
    public static final class BindRefNested {
        @Specialization
        public static Object bind(boolean append, PhpRef ref, PhpRef base, @Variadic Object[] keys,
                                  @Bind Node node, @Cached ElementNodes.ArrayKeyNode toKey) {
            bindElement(node, referenceForWrite(base), keys, append, ref, toKey);
            return ref.get();
        }
    }

    private static void bindElement(Node node, Object base, Object[] keys, boolean append, PhpRef ref,
                                    ElementNodes.ArrayKeyNode toKey) {
        int levels = append ? keys.length : keys.length - 1;
        Object container = ElementChain.forWrite(node, base, keys, levels, toKey);
        if (!(container instanceof PhpArray array)) {
            throw ElementChain.overloaded();
        }
        array.putRef(append ? (Object) array.nextIndex() : toKey.execute(keys[levels]), ref);
    }

    /** `$obj->$name = &place`: as BindProp, the name computed (PropName). */
    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class BindPropNamed {
        @Specialization
        public static Object bind(VirtualFrame frame, PropSite site, PhpRef ref, Object object, String name,
                                  @Cached(value = "site.create()", uncached = "site.uncached()",
                                          neverDefault = true) PropNode access) {
            access.bindRefOn(frame.getArguments(), object, name, ref);
            return ref.get();
        }
    }

    /** `$obj->name = &place` (PropNode.bindRef): the reference, then the object. */
    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class BindProp {
        @Specialization
        public static Object bind(VirtualFrame frame, PropSite site, PhpRef ref, Object object,
                                  @Cached(value = "site.create()", uncached = "site.uncached()",
                                          neverDefault = true) PropNode access) {
            access.bindRefOn(frame.getArguments(), object, site.name, ref);
            return ref.get();
        }
    }

    /**
     * `$x = &f()` (CallRefAssignNode): the variable bound to the reference a function returning by reference
     * returned; a value is assigned, with php-src's notice.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    public static final class BindVarToResult {
        @Specialization
        public static Object bind(VirtualFrame frame, LocalAccessor variable, Object result,
                                  @Bind BytecodeNode bytecode, @Bind Node node,
                @Shared("writes") @Cached(neverDefault = true) Locals.Writes writes) {
                if (result instanceof PhpRef ref) {
                Locals.bindRef(frame, variable, bytecode, ref);
                return ref.get();
            }
            CallNode.notice(node, CallNode.ASSIGNED_VALUE);
                writes.write(frame, variable, bytecode, result);
            return result;
        }
    }

    /** `global $x` (GlobalNode): the variable bound to the global's reference, made (null) if it is missing. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = String.class, name = "name")
    public static final class Global {
        @Specialization(excludeForUncached = true)
        public static void cached(VirtualFrame frame, LocalAccessor variable, String name,
                                  @Bind BytecodeNode bytecode, @Bind Node node,
                                  @Cached(value = "create(name)", neverDefault = true) GlobalRefNode global) {
            Locals.bindRef(frame, variable, bytecode, global.execute(PhpContext.get(node), true));
        }

        @Specialization(replaces = "cached")
        public static void uncached(VirtualFrame frame, LocalAccessor variable, String name,
                                    @Bind BytecodeNode bytecode, @Bind Node node) {
            Locals.bindRef(frame, variable, bytecode, PhpContext.get(node).globalRef(name));
        }
    }

    /**
     * A global by name read: a superglobal (`$_GET`, GlobalVarNode.execute) or `$GLOBALS['name']` by a constant
     * key (GlobalsDimNode): its value; null when it doesn't exist, after "Undefined global variable" unless the
     * read is isset()'s (quiet). The site's GlobalRefNode keeps the reference.
     */
    @Operation
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = boolean.class, name = "quiet")
    public static final class GlobalValue {
        @Specialization(excludeForUncached = true)
        public static Object cached(String name, boolean quiet, @Bind Node node,
                                    @Cached(value = "create(name)", neverDefault = true) GlobalRefNode global) {
            PhpRef ref = global.execute(PhpContext.get(node), false);
            return ref == null ? undefinedGlobal(node, name, quiet) : ref.get();
        }

        @Specialization(replaces = "cached")
        public static Object uncached(String name, boolean quiet, @Bind Node node) {
            PhpRef ref = PhpContext.get(node).findGlobal(name);
            return ref == null ? undefinedGlobal(node, name, quiet) : ref.get();
        }
    }

    /** A missing global read: null, after php-src's warning (ZEND_FETCH_R with ZEND_FETCH_GLOBAL) unless quiet. */
    @TruffleBoundary
    static Object undefinedGlobal(Node node, String name, boolean quiet) {
        if (!quiet) {
            ErrorFunctions.raise(PhpContext.get(node), ErrorFunctions.E_WARNING,
                    Str.of("Undefined global variable $" + name), SourceLocation.of(node));
        }
        return PhpNull.NULL;
    }

    /**
     * A global by name as a place (GlobalVarNode.makeRef): its reference, made (null) when create says so and the
     * global doesn't exist; else null for a missing one (an unset of an element, a foreach by reference read it
     * without creating it). For a read-modify-write (update: `$GLOBALS['x'] .= `, BP_VAR_RW) a missing global
     * warns before it is made.
     */
    @Operation
    @ConstantOperand(type = String.class, name = "name")
    @ConstantOperand(type = boolean.class, name = "create")
    @ConstantOperand(type = boolean.class, name = "update")
    public static final class GlobalRef {
        @Specialization(excludeForUncached = true)
        public static Object cached(String name, boolean create, boolean update, @Bind Node node,
                                    @Cached(value = "create(name)", neverDefault = true) GlobalRefNode global) {
            PhpContext context = PhpContext.get(node);
            // ponytail: an uncached lookup for the check; a read-modify-write of a global by name is rare
            if (update && context.findGlobal(name) == null) {
                undefinedGlobal(node, name, false);
            }
            PhpRef ref = global.execute(context, create);
            return ref == null ? PhpNull.NULL : ref;
        }

        @Specialization(replaces = "cached")
        public static Object uncached(String name, boolean create, boolean update, @Bind Node node) {
            PhpContext context = PhpContext.get(node);
            if (update && context.findGlobal(name) == null) {
                undefinedGlobal(node, name, false);
            }
            PhpRef ref = create ? context.globalRef(name) : context.findGlobal(name);
            return ref == null ? PhpNull.NULL : ref;
        }
    }

    /** `$GLOBALS[key]` by a computed key read (GlobalsDimNode.execute): the global the key names, or null. */
    @Operation
    @ConstantOperand(type = boolean.class, name = "quiet")
    public static final class GlobalsElementValue {
        @Specialization
        public static Object read(boolean quiet, Object key, @Bind Node node) {
            String name = GlobalsDimNode.toName(key);
            PhpRef ref = PhpContext.get(node).findGlobal(name);
            return ref == null ? undefinedGlobal(node, name, quiet) : ref.get();
        }
    }

    /** `$GLOBALS[key]` by a computed key as a place (GlobalsDimNode.makeRef): as GlobalRef. */
    @Operation
    @ConstantOperand(type = boolean.class, name = "create")
    @ConstantOperand(type = boolean.class, name = "update")
    public static final class GlobalsElementRef {
        @Specialization
        public static Object ref(boolean create, boolean update, Object key, @Bind Node node) {
            PhpContext context = PhpContext.get(node);
            String name = GlobalsDimNode.toName(key);
            if (update && context.findGlobal(name) == null) {
                undefinedGlobal(node, name, false);
            }
            PhpRef ref = create ? context.globalRef(name) : context.findGlobal(name);
            return ref == null ? PhpNull.NULL : ref;
        }
    }

    /** `unset($_SESSION)`, `unset($GLOBALS[key])` (GlobalVarNode, GlobalsDimNode unset): the global named. */
    @Operation
    public static final class UnsetGlobal {
        @Specialization
        public static void unset(Object key, @Bind Node node) {
            PhpContext.get(node).unsetGlobal(GlobalsDimNode.toName(key));
        }
    }

    /** `$_SESSION = &place`, `$GLOBALS[key] = &place` (bindRef): the reference, then the global named. */
    @Operation
    public static final class BindGlobal {
        @Specialization
        public static Object bind(PhpRef ref, Object key, @Bind Node node) {
            PhpContext.get(node).bindGlobal(GlobalsDimNode.toName(key), ref);
            return ref.get();
        }
    }

    /** `$GLOBALS` read as a whole (GlobalsArrayNode): a copy of the global variables. */
    @Operation
    public static final class GlobalsArray {
        @Specialization
        public static PhpArray read(@Bind Node node) {
            return PhpContext.get(node).globalsArray();
        }
    }

    /**
     * `static $x = init;` (StaticVarNode): binds the variable to the function's static variable of that name in
     * this context and answers true; false while it has none, and StaticCreate makes it from the initializer.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = PhpFunction.class)
    @ConstantOperand(type = String.class, name = "name")
    public static final class StaticBind {
        @Specialization
        public static boolean bind(VirtualFrame frame, LocalAccessor variable, PhpFunction function, String name,
                                   @Bind BytecodeNode bytecode, @Bind Node node) {
            PhpRef ref = PhpContext.get(node).findStaticVariable(function,
                    frame.getArguments()[PhpFunction.ARG_CLOSURE], name);
            if (ref == null) {
                return false;
            }
            Locals.bindRef(frame, variable, bytecode, ref);
            return true;
        }
    }

    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = PhpFunction.class)
    @ConstantOperand(type = String.class, name = "name")
    public static final class StaticCreate {
        @Specialization
        public static void create(VirtualFrame frame, LocalAccessor variable, PhpFunction function, String name,
                                  Object initial, @Bind BytecodeNode bytecode, @Bind Node node) {
            Locals.bindRef(frame, variable, bytecode,
                    PhpContext.get(node).createStaticVariable(function, frame.getArguments()[PhpFunction.ARG_CLOSURE],
                            name, initial));
        }
    }

    /**
     * `place = &f()` where place isn't a plain variable (zend_compile_assign_ref's ZEND_RETURNS_FUNCTION): the
     * reference the callee returned, else its value in a reference of its own, with php-src's notice.
     */
    @Operation
    public static final class AssignedRef {
        @Specialization
        public static PhpRef assigned(Object value, @Bind Node node) {
            if (value instanceof PhpRef ref) {
                return ref;
            }
            CallNode.notice(node, CallNode.ASSIGNED_VALUE);
            return PhpRef.unbound(value);
        }
    }

    /**
     * What `return value;` gives in a function returning by reference when the value is no place
     * (ReturnRefValueNode): a call's reference when its callee returned one, else a new reference to the value,
     * with php-src's notice (none for $this).
     */
    @Operation
    @ConstantOperand(type = boolean.class, name = "notice")
    public static final class ReturnedRef {
        @Specialization
        public static PhpRef returned(boolean notice, Object value, @Bind Node node) {
            if (value instanceof PhpRef ref) {
                return ref;
            }
            if (notice) {
                CallNode.notice(node, CallNode.RETURNED_VALUE);
            }
            return PhpRef.unbound(value);
        }
    }

    /**
     * The end of a function returning by reference (FunctionRootNode.implicitReturnRef): null as a new reference,
     * with php-src's notice at the closing brace.
     */
    @Operation
    @ConstantOperand(type = int.class, name = "endLine")
    public static final class ImplicitReturnRef {
        @Specialization
        public static PhpRef implicit(int endLine, @Bind PhpBytecodeRootNode root, @Bind Node node) {
            CallNode.notice(node, CallNode.RETURNED_VALUE, new SourceLocation(root.path, endLine));
            return PhpRef.unbound(PhpNull.NULL);
        }
    }

    /**
     * A call's result a generator yielding by reference yields (ZEND_YIELD of a call): the reference its callee
     * returned, else the value with php-src's notice.
     */
    @Operation
    public static final class YieldedRef {
        @Specialization
        public static Object yielded(Object result, @Bind Node node) {
            if (!(result instanceof PhpRef)) {
                CallNode.notice(node, "Only variable references should be yielded by reference");
            }
            return result;
        }
    }

    /** What a generator yielding by reference yields that isn't a variable: the value, with php-src's notice. */
    @Operation
    public static final class NotReferenceYielded {
        @Specialization
        public static Object value(Object value, @Bind Node node) {
            CallNode.notice(node, "Only variable references should be yielded by reference");
            return value;
        }
    }

    /** What a yield or yield from gives once resumed: the sent value, or it throws (throw(), destruction). */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    public static final class Resumed {
        @Specialization
        public static Object resumed(VirtualFrame frame, LocalAccessor generator, Object value,
                                     @Bind BytecodeNode bytecode) {
            return ((PhpGenerator) generator.getObject(bytecode, frame)).resumed(value);
        }
    }

    // ---- try / catch / finally (TryNode) and throw ----

    /** `throw value` (ThrowNode), as a statement or an expression: it never gives a value. */
    @Operation
    public static final class Throw {
        @Specialization
        public static Object doThrow(Object value) {
            BasicStmts.ThrowNode.throwValue(value);
            return value;
        }
    }

    /**
     * The object the catch blocks of a try see for exception (TryNode.handle): a PHP exception's, or a runtime
     * error's made into one; for any other exception (a destroyed generator's unwinding, a bailout) null, which no
     * catch block takes.
     */
    @Operation
    public static final class CaughtThrowable {
        @Specialization
        public static Object caught(Object exception, @Bind Node node) {
                // by class, as TryNode.tryCatch: PhpThrowable.toThrowable is a virtual call PE can't inline
                if (exception instanceof PhpException thrown) {
                return thrown.getThrowable();
                }
                if (exception instanceof PhpError error) {
                return error.toThrowable(PhpContext.get(node));
                }
                return PhpNull.NULL;
        }
    }

    /** Whether a catch block of these types (lowercase class names) takes the caught object (TryNode.matches). */
    @Operation
    @ConstantOperand(type = String[].class)
    public static final class CatchMatches {
        @Specialization
        public static boolean matches(String[] types, Object thrown) {
            return thrown instanceof PhpObject object && TryNode.matches(object, types);
        }
    }

    /** An exception no catch block took goes on as it was. */
    @Operation
    public static final class Rethrow {
        @Specialization
        public static void rethrow(Object exception) {
            throw (AbstractTruffleException) exception;
        }
    }

    /**
     * Whether a finally block runs as exception leaves its try: for PHP's exceptions and a destroyed generator's
     * unwinding; not for a bailout after a fatal error, which runs none (TryNode).
     */
    @Operation
    public static final class FinallyRuns {
        @Specialization
        public static boolean runs(Object exception) {
            return exception instanceof PhpThrowable || exception instanceof PhpGenerator.Destroyed;
        }
    }

    /**
     * An exception a finally block throws while pending leaves the try: a PHP exception takes a pending PHP
     * exception as its previous (TryNode.setPrevious), and goes on.
     */
    @Operation
    public static final class ThrowFromFinally {
        @Specialization
        public static void chain(Object thrown, Object pending, @Bind Node node) {
            if (thrown instanceof PhpThrowable exception && pending instanceof PhpThrowable previous) {
                TryNode.setPrevious(PhpContext.get(node), exception, previous);
            }
            throw (AbstractTruffleException) thrown;
        }
    }

    // ---- strings ----

    /**
     * `"text {$expr} $var"` (InterpolationNode): the parts, each converted as it was evaluated (InterpolationPart,
     * php-src's ROPE_ADD), joined in one call.
     */
    @Operation
    public static final class Interpolate {
        @Specialization
        public static TruffleString join(@Variadic Object[] parts) {
            return Str.join(null, parts);
        }
    }

    /**
     * A function declared (DeclareFunctionNode): in a body when the statement runs, at a file's top level before the
     * file runs (compileTime, as php-src binds it when it compiles the file).
     */
    @Operation
    @ConstantOperand(type = PhpFunction.class)
    @ConstantOperand(type = boolean.class, name = "compileTime")
    public static final class DeclareFunction {
        @Specialization
        public static void declare(PhpFunction function, boolean compileTime, @Bind Node node) {
            DeclareNodes.DeclareFunctionNode.declare(PhpContext.get(node), function, compileTime, node);
        }
    }

    /**
     * A top-level class bound before its file runs (EarlyClassNode): true when it was bound; late, only if its name
     * is free and its parent declared by now.
     */
    @Operation
    @ConstantOperand(type = ClassTemplate.class)
    @ConstantOperand(type = boolean.class, name = "late")
    public static final class EarlyClass {
        @Specialization
        public static boolean bind(ClassTemplate template, boolean late, @Bind Node node) {
            return DeclareNodes.EarlyClassNode.bind(PhpContext.get(node), template, late, node);
        }
    }

    /** `const NAME = value;` at a file's top level (Translator's DefineNode): the value. */
    @Operation
    @ConstantOperand(type = String.class)
    public static final class DefineConstant {
        @Specialization
        public static Object define(String name, Object value, @Bind Node node) {
            PhpContext.get(node).defineConstant(name, value);
            return value;
        }
    }

    /** `defined('NAME')` in place (DefinedSite, ZEND_DEFINED). */
    @Operation
    @ConstantOperand(type = DefinedSite.class)
    public static final class Defined {
        @Specialization
        public static boolean defined(DefinedSite site, @Bind Node node) {
            return site.test(node);
        }
    }

    /** A class declared in a function's body, when the statement runs (DeclareClassNode). */
    @Operation
    @ConstantOperand(type = ClassTemplate.class)
    public static final class DeclareClass {
        @Specialization
        public static void declare(ClassTemplate template, @Bind Node node) {
            DeclareNodes.DeclareClassNode.declare(PhpContext.get(node), template, node);
        }
    }

    /** `new class {...}`'s class, declared the first time (NewAnonymousClassNode). */
    @Operation
    @ConstantOperand(type = ClassTemplate.class)
    public static final class DeclareAnonymousClass {
        @Specialization
        public static void declare(ClassTemplate template, @Bind Node node) {
            DeclareNodes.NewAnonymousClassNode.declareOnce(PhpContext.get(node), template,
                    Str.lower(template.getName()));
        }
    }

    /**
     * `parent::$name::get()`, `parent::$name::set($value)` in a property hook (ZEND_INIT_PARENT_PROPERTY_HOOK_CALL):
     * the parent's hook, or its trampoline's access to the backing value.
     */
    @Operation
    @ConstantOperand(type = PropSite.class)
    @ConstantOperand(type = boolean.class, name = "set")
    public static final class ParentHook {
        @Specialization
        public static Object call(VirtualFrame frame, PropSite site, boolean set, @Variadic Object[] args,
                                  @Bind Node node) {
            return PropNode.parentHook(node, frame.getArguments(), site.lexicalClass, site.name, set, args);
        }
    }

    /** A constructor's promoted parameter stored in its property (PromoteNode). */
    @Operation
    @ConstantOperand(type = String.class)
    public static final class Promote {
        @Specialization
        public static void promote(VirtualFrame frame, String name, Object value) {
            BasicStmts.PromoteNode.store((PhpObject) frame.getArguments()[PhpFunction.ARG_THIS], name, value);
        }
    }

    /** The first of two values, once both are evaluated (a concatenation chain's first operand). */
    @Operation
    public static final class FirstOfTwo {
        @Specialization
        public static Object first(Object first, Object second) {
            return first;
        }
    }

    /** `(array)` of a value that isn't an array yet (ToOtherNode). */
    @Operation
    public static final class CastArray {
        @Specialization
        public static PhpArray same(PhpArray value) {
            return value;
        }

        @Fallback
        public static PhpArray other(Object value, @Bind Node node) {
            return UnaryNodes.ToOtherNode.toArray(node, value);
        }
    }

    /** `(object)` (ToOtherNode): an object stays, anything else becomes a stdClass. */
    @Operation
    public static final class CastObject {
        @Specialization
        public static Object same(PhpObject value) {
            return value;
        }

        @Fallback
        public static Object other(Object value, @Bind Node node) {
            return UnaryNodes.ToOtherNode.toObject(node, value);
        }
    }

    // ---- statements and logic ----

    /** `print value` (the tree's PrintNode): echoes it; the value is 1. */
    @Operation
    public static final class Print {
        @Specialization
        public static long print(Object value, @Bind Node node) {
            BasicStmts.EchoNode.echo(node, value);
            return 1L;
        }
    }

    @Operation
    public static final class Echo {
        @Specialization
        public static void echo(Object value, @Bind Node node) {
            BasicStmts.EchoNode.echo(node, value);
        }
    }

    @Operation
    public static final class Not {
        @Specialization
        public static boolean not(boolean value) {
            return !value;
        }
    }

    // ---- named arguments (NamedSite, BindingPlan) ----

    /*
     * A call with `name: value` arguments: the callee is resolved first, as for any call; each argument from the
     * first name on is sent by a named send, which binds it as the site's plan for that callee says (php-src's
     * zend_handle_named_arg at its SEND: the Error once its value is evaluated, before the next argument); then the
     * named call scatters the values to their parameters' positions, fills the holes with the callee's defaults
     * target, and calls. Each operation caches the plan with its callee, as the positional ones cache the callee.
     */

    /**
     * What a named place argument's send (SendElement, SendProp, ArgByRef... at position 0) asks about its
     * parameter: a stand-in taking it by reference or by value, as the binding says. An unknown name is by value
     * (CHECK_FUNC_ARG falls back to it): the place is fetched, then SendPlaceNamed throws.
     */
    @Operation
    @ConstantOperand(type = NamedSite.class)
    @ConstantOperand(type = int.class, name = "argument")
    public static final class NamedTarget {
        @Specialization(guards = "function == cachedFunction", limit = "1", excludeForUncached = true)
        public static PhpFunction cached(NamedSite site, int argument, PhpFunction function,
                                         @Cached("function") PhpFunction cachedFunction,
                                         @Cached("site.plan(cachedFunction)") BindingPlan plan) {
            return plan.sendTarget(argument);
        }

        @Specialization(replaces = "cached")
        public static PhpFunction generic(NamedSite site, int argument, PhpFunction function) {
            return site.plan(function).sendTarget(argument);
        }
    }

    /**
     * A variable as a named argument (SEND_VAR_EX): bound before it is read, so an unknown name throws without the
     * "Undefined variable" warning; then its reference or its value, as SendVar.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = NamedSite.class)
    @ConstantOperand(type = int.class, name = "argument")
    public static final class SendVarNamed {
        @Specialization(guards = "function == cachedFunction", limit = "1", excludeForUncached = true)
        public static Object cached(VirtualFrame frame, LocalAccessor variable, NamedSite site, int argument,
                                    PhpFunction function, @Bind BytecodeNode bytecode, @Bind Node node,
                                    @Cached("function") PhpFunction cachedFunction,
                                    @Cached("site.plan(cachedFunction)") BindingPlan plan,
                                    @Shared("undefined") @Cached InlinedBranchProfile undefined) {
            return send(frame, variable, argument, plan, bytecode, node, undefined);
        }

        @Specialization(replaces = "cached")
        public static Object generic(VirtualFrame frame, LocalAccessor variable, NamedSite site, int argument,
                                     PhpFunction function, @Bind BytecodeNode bytecode, @Bind Node node,
                                     @Shared("undefined") @Cached InlinedBranchProfile undefined) {
            return send(frame, variable, argument, site.plan(function), bytecode, node, undefined);
        }

        private static Object send(VirtualFrame frame, LocalAccessor variable, int argument, BindingPlan plan,
                                   BytecodeNode bytecode, Node node, InlinedBranchProfile undefined) {
            plan.checkSend(argument);
            if (plan.takesRef(argument)) {
                return Locals.makeRef(frame, variable, bytecode);
            }
            return readValue(frame, variable, bytecode, node, undefined);
        }
    }

    /**
     * A value as a named argument (SEND_VAL_EX), once it is evaluated: bound, then php-src's Error when its
     * parameter is by reference, as SendValue.
     */
    @Operation
    @ConstantOperand(type = NamedSite.class)
    @ConstantOperand(type = int.class, name = "argument")
    public static final class SendValueNamed {
        @Specialization(guards = "function == cachedFunction", limit = "1", excludeForUncached = true)
        public static Object cached(NamedSite site, int argument, PhpFunction function, Object value,
                                    @Cached("function") PhpFunction cachedFunction,
                                    @Cached("site.plan(cachedFunction)") BindingPlan plan) {
            return send(argument, cachedFunction, plan, value);
        }

        @Specialization(replaces = "cached")
        public static Object generic(NamedSite site, int argument, PhpFunction function, Object value) {
            return send(argument, function, site.plan(function), value);
        }

        private static Object send(int argument, PhpFunction function, BindingPlan plan, Object value) {
            plan.checkSend(argument);
            if (plan.takesRef(argument) && !function.isPreferRef()) {
                throw CallNode.cannotPassByRef(function, plan.parameter(argument));
            }
            return value;
        }
    }

    /** A call's result as a named argument (SEND_VAR_NO_REF), once it is evaluated: bound, then as SendCall. */
    @Operation
    @ConstantOperand(type = NamedSite.class)
    @ConstantOperand(type = int.class, name = "argument")
    public static final class SendCallNamed {
        @Specialization(guards = "function == cachedFunction", limit = "1", excludeForUncached = true)
        public static Object cached(NamedSite site, int argument, PhpFunction function, Object result,
                                    @Bind Node node, @Cached("function") PhpFunction cachedFunction,
                                    @Cached("site.plan(cachedFunction)") BindingPlan plan) {
            return send(argument, cachedFunction, plan, result, node);
        }

        @Specialization(replaces = "cached")
        public static Object generic(NamedSite site, int argument, PhpFunction function, Object result,
                                     @Bind Node node) {
            return send(argument, function, site.plan(function), result, node);
        }

        private static Object send(int argument, PhpFunction function, BindingPlan plan, Object result, Node node) {
            plan.checkSend(argument);
            if (!plan.takesRef(argument) || function.isPreferRef()) {
                return PhpRef.deref(result);
            }
            if (!(result instanceof PhpRef)) {
                CallNode.notice(node, CallNode.PASSED_VALUE);
            }
            return result;
        }
    }

    /**
     * A place as a named argument (an element, a property, a static property, $this...), once fetched as its
     * NamedTarget said (FETCH_*_FUNC_ARG, then SEND_FUNC_ARG): bound, so an unknown name's Error comes after the
     * fetch's warnings.
     */
    @Operation
    @ConstantOperand(type = NamedSite.class)
    @ConstantOperand(type = int.class, name = "argument")
    public static final class SendPlaceNamed {
        @Specialization(guards = "function == cachedFunction", limit = "1", excludeForUncached = true)
        public static Object cached(NamedSite site, int argument, PhpFunction function, Object value,
                                    @Cached("function") PhpFunction cachedFunction,
                                    @Cached("site.plan(cachedFunction)") BindingPlan plan) {
            plan.checkSend(argument);
            return value;
        }

        @Specialization(replaces = "cached")
        public static Object generic(NamedSite site, int argument, PhpFunction function, Object value) {
            site.plan(function).checkSend(argument);
            return value;
        }
    }

    /**
     * `name(args)` with named arguments, once they are sent: as Call and CallRef (keepRef), the values at their
     * parameters' positions, the holes filled by the defaults target first (ZEND_CHECK_UNDEF_ARGS).
     */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    @ConstantOperand(type = NamedSite.class)
    @ConstantOperand(type = boolean.class, name = "keepRef")
    public static final class CallNamed {
        @Specialization(guards = "function == cachedFunction", limit = "1")
        public static Object direct(CallInfo call, NamedSite site, boolean keepRef, PhpFunction function,
                                    @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                    @Cached("function") PhpFunction cachedFunction,
                                    @Cached("site.plan(cachedFunction)") BindingPlan plan,
                                    @Cached("create(cachedFunction.getCallTarget())") DirectCallNode callNode,
                                    @Cached(value = "plan.defaultsCall(cachedFunction)", neverDefault = false)
                                    DirectCallNode defaults) {
            Object[] frame = plan.frame(arguments);
            plan.enter(frame, arguments, cachedFunction, defaults);
            return result(callNode.call(frame), keepRef);
        }

        @Specialization(replaces = "direct")
        public static Object indirect(CallInfo call, NamedSite site, boolean keepRef, PhpFunction function,
                                      @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                      @Cached IndirectCallNode callNode) {
            BindingPlan plan = site.plan(function);
            Object[] frame = plan.frame(arguments);
            plan.enter(frame, arguments, function, callNode);
            return result(callNode.call(function.getCallTarget(), frame), keepRef);
        }
    }

    /** `$obj->name(args)` with named arguments: as CallMethod, the frame arguments bound as CallNamed's. */
    @Operation
    @ConstantOperand(type = MethodSite.class)
    @ConstantOperand(type = NamedSite.class)
    @ConstantOperand(type = boolean.class, name = "keepRef")
    public static final class CallMethodNamed {
        @Specialization(guards = {"!isByValue(function)", "function == cachedFunction"}, limit = "4")
        public static Object direct(MethodSite method, NamedSite site, boolean keepRef, PhpObject receiver,
                                    PhpFunction function,
                                    @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                    @Cached("function") PhpFunction cachedFunction,
                                    @Cached("site.plan(cachedFunction)") BindingPlan plan,
                                    @Cached("create(cachedFunction.getCallTarget())") DirectCallNode callNode,
                                    @Cached(value = "plan.defaultsCall(cachedFunction)", neverDefault = false)
                                    DirectCallNode defaults) {
            Object[] frame = plan.frame(arguments);
            frame[PhpFunction.ARG_THIS] = cachedFunction.isStatic() ? null : receiver;
            frame[PhpFunction.ARG_STATIC] = receiver.getPhpClass();
            plan.enter(frame, arguments, cachedFunction, defaults);
            return result(callNode.call(frame), keepRef);
        }

        @Specialization(guards = "!isByValue(function)", replaces = "direct")
        public static Object indirect(MethodSite method, NamedSite site, boolean keepRef, PhpObject receiver,
                                      PhpFunction function,
                                      @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                      @Shared @Cached IndirectCallNode callNode) {
            BindingPlan plan = site.plan(function);
            Object[] frame = plan.frame(arguments);
            frame[PhpFunction.ARG_THIS] = function.isStatic() ? null : receiver;
            frame[PhpFunction.ARG_STATIC] = receiver.getPhpClass();
            plan.enter(frame, arguments, function, callNode);
            return result(callNode.call(function.getCallTarget(), frame), keepRef);
        }

        /** `$closure->__invoke(args)`: the closure's function, as CallMethod.invoke. */
        @Specialization(guards = "!isByValue(function)")
        public static Object invoke(MethodSite method, NamedSite site, boolean keepRef, PhpClosure receiver,
                                    PhpFunction function,
                                    @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                    @Shared @Cached IndirectCallNode callNode) {
            BindingPlan plan = site.plan(function);
            Object[] frame = plan.frame(arguments);
            PhpObject self = receiver.getBoundThis();
            frame[PhpFunction.ARG_THIS] = self;
            frame[PhpFunction.ARG_STATIC] = self != null ? self.getPhpClass() : receiver.getScope();
            frame[PhpFunction.ARG_CLOSURE] = receiver;
            plan.enter(frame, arguments, function, callNode);
            return result(callNode.call(function.getCallTarget(), frame), keepRef);
        }

        /**
         * __call (or a closure of a method __call stands in for): every name is an extra (__call's trampoline is
         * variadic), in its arguments array under its name after the positional ones. A Closure's own methods are
         * built-ins.
         */
        @Specialization(guards = "isByValue(function)")
        public static Object byValue(MethodSite method, NamedSite site, boolean keepRef, Object receiver,
                                     PhpFunction function,
                                     @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                     @Shared @Cached IndirectCallNode callNode, @Bind Node node) {
            if (receiver instanceof PhpClosure closure && !method.lowerName.equals("__invoke")) {
                // a Closure's own method (call, bindTo...): a built-in, bound by its arginfo names
                PhpFunction builtin = closureMethod(node, method.name);
                BindingPlan builtinPlan = site.plan(builtin);
                Object[] frame = builtinPlan.frame(arguments);
                frame[PhpFunction.ARG_THIS] = closure;
                return result(Backtrace.callFrom(node, () -> {
                    builtinPlan.enter(frame, arguments, builtin, callNode);
                    return callNode.call(builtin.getCallTarget(), frame);
                }), keepRef);
            }
            BindingPlan plan = site.plan(function);
            Object[] frame = plan.frame(arguments);
            if (receiver instanceof PhpClosure closure) {
                return result(callNode.call(closure.getFunction().getCallTarget(),
                        ArgumentsNode.frameArguments(closure.getBoundThis(), closure.staticClass(), closure,
                                plan.magicValues(closure.getMagicName(), frame, arguments))), keepRef);
            }
            PhpObject object = (PhpObject) receiver;
            PhpClass phpClass = object.getPhpClass();
            return result(callNode.call(magicMethod(phpClass, "__call").getCallTarget(),
                    ArgumentsNode.frameArguments(object, phpClass, null,
                            plan.magicValues(method.calledName, frame, arguments))), keepRef);
        }

        public static boolean isByValue(PhpFunction function) {
            return MethodSite.byValue(function);
        }
    }

    /** `Cls::name(args)` with named arguments: as CallStatic, the frame arguments bound as CallNamed's. */
    @Operation
    @ConstantOperand(type = MethodSite.class)
    @ConstantOperand(type = NamedSite.class)
    @ConstantOperand(type = boolean.class, name = "keepRef")
    public static final class CallStaticNamed {
        @Specialization(guards = {"!isByValue(function)", "function == cachedFunction"}, limit = "4")
        public static Object direct(VirtualFrame frame, MethodSite method, NamedSite site, boolean keepRef,
                                    PhpClass phpClass, PhpFunction function,
                                    @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                    @Cached("function") PhpFunction cachedFunction,
                                    @Cached("site.plan(cachedFunction)") BindingPlan plan,
                                    @Cached("create(cachedFunction.getCallTarget())") DirectCallNode callNode,
                                    @Cached(value = "plan.defaultsCall(cachedFunction)", neverDefault = false)
                                    DirectCallNode defaults,
                                    @Shared @Cached SubclassTestNode subclassTest) {
            Object[] callee = plan.frame(arguments);
            Object[] caller = frame.getArguments();
            callee[PhpFunction.ARG_THIS] = cachedFunction.isStatic() ? null
                    : CallStatic.callerThis(caller, phpClass, subclassTest);
            callee[PhpFunction.ARG_STATIC] = CallStatic.staticClass(method, caller, phpClass);
            plan.enter(callee, arguments, cachedFunction, defaults);
            return result(callNode.call(callee), keepRef);
        }

        @Specialization(guards = "!isByValue(function)", replaces = "direct")
        public static Object indirect(VirtualFrame frame, MethodSite method, NamedSite site, boolean keepRef,
                                      PhpClass phpClass, PhpFunction function,
                                      @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                      @Shared @Cached IndirectCallNode callNode,
                                      @Shared @Cached SubclassTestNode subclassTest) {
            BindingPlan plan = site.plan(function);
            Object[] callee = plan.frame(arguments);
            Object[] caller = frame.getArguments();
            callee[PhpFunction.ARG_THIS] = function.isStatic() ? null
                    : CallStatic.callerThis(caller, phpClass, subclassTest);
            callee[PhpFunction.ARG_STATIC] = CallStatic.staticClass(method, caller, phpClass);
            plan.enter(callee, arguments, function, callNode);
            return result(callNode.call(function.getCallTarget(), callee), keepRef);
        }

        /** __call or __callStatic: every name is an extra. */
        @Specialization(guards = "isByValue(function)")
        public static Object magic(VirtualFrame frame, MethodSite method, NamedSite site, boolean keepRef,
                                   PhpClass phpClass, PhpFunction function,
                                   @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                   @Shared @Cached IndirectCallNode callNode,
                                   @Shared @Cached SubclassTestNode subclassTest,
                                   @Bind PhpBytecodeRootNode root) {
            BindingPlan plan = site.plan(function);
            return CallStatic.callMagic(frame, method, keepRef, phpClass,
                    plan.magicValues(method.calledName, plan.frame(arguments), arguments), callNode, subclassTest,
                    root);
        }

        public static boolean isByValue(PhpFunction function) {
            return MethodSite.byValue(function);
        }
    }

    /** `$f(args)` with named arguments: as CallValue, the frame arguments bound as CallNamed's. */
    @Operation
    @ConstantOperand(type = NamedSite.class)
    @ConstantOperand(type = boolean.class, name = "keepRef")
    public static final class CallValueNamed {
        @Specialization(guards = {"target.magicName() == null", "function == cachedFunction"}, limit = "4")
        public static Object direct(NamedSite site, boolean keepRef, Callables.Target target, PhpFunction function,
                                    @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                    @Cached("function") PhpFunction cachedFunction,
                                    @Cached("site.plan(cachedFunction)") BindingPlan plan,
                                    @Cached("create(cachedFunction.getCallTarget())") DirectCallNode callNode,
                                    @Cached(value = "plan.defaultsCall(cachedFunction)", neverDefault = false)
                                    DirectCallNode defaults) {
            Object[] frame = targetArguments(target, plan.frame(arguments));
            plan.enter(frame, arguments, cachedFunction, defaults);
            return result(callNode.call(frame), keepRef);
        }

        @Specialization(guards = "target.magicName() == null", replaces = "direct")
        public static Object indirect(NamedSite site, boolean keepRef, Callables.Target target, PhpFunction function,
                                      @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                      @Shared @Cached IndirectCallNode callNode) {
            BindingPlan plan = site.plan(function);
            Object[] frame = targetArguments(target, plan.frame(arguments));
            plan.enter(frame, arguments, function, callNode);
            return result(callNode.call(function.getCallTarget(), frame), keepRef);
        }

        /** __call or __callStatic standing in for the method: every name is an extra. */
        @Specialization(guards = "target.magicName() != null")
        public static Object magic(NamedSite site, boolean keepRef, Callables.Target target, PhpFunction function,
                                   @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                   @Shared @Cached IndirectCallNode callNode) {
            BindingPlan plan = site.plan(function);
            return result(callNode.call(target.function().getCallTarget(), target.boundArguments(
                    plan.magicValues(target.magicName(), plan.frame(arguments), arguments))), keepRef);
        }
    }

    /**
     * `new Cls(args)` with named arguments: as Construct, the frame arguments bound as CallNamed's. A class without
     * a constructor took no name (each send's "Unknown named parameter"); in a constant expression its arguments
     * are evaluated and dropped, and the binding's errors come once all are evaluated (zend_ast_evaluate).
     */
    @Operation
    @ConstantOperand(type = NamedSite.class)
    public static final class ConstructNamed {
        @Specialization(guards = {"!isByValue(constructor)", "constructor == cachedConstructor"}, limit = "4")
        public static PhpObject direct(NamedSite site, PhpObject object, PhpFunction constructor,
                                       @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                       @Bind Node node,
                                       @Cached("constructor") PhpFunction cachedConstructor,
                                       @Cached("site.plan(cachedConstructor)") BindingPlan plan,
                                       @Cached("create(cachedConstructor.getCallTarget())") DirectCallNode callNode,
                                       @Cached(value = "plan.defaultsCall(cachedConstructor)", neverDefault = false)
                                       DirectCallNode defaults) {
            if (site.isConstant()) {
                plan.bindConstant(arguments, cachedConstructor, node);
            }
            Object[] frame = plan.frame(arguments);
            frame[PhpFunction.ARG_THIS] = object;
            frame[PhpFunction.ARG_STATIC] = object.getPhpClass();
            plan.enter(frame, arguments, cachedConstructor, defaults);
            callNode.call(frame);
            return object;
        }

        @Specialization(guards = "!isByValue(constructor)", replaces = "direct")
        public static PhpObject indirect(NamedSite site, PhpObject object, PhpFunction constructor,
                                         @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                         @Bind Node node, @Cached IndirectCallNode callNode) {
            BindingPlan plan = site.plan(constructor);
            if (site.isConstant()) {
                plan.bindConstant(arguments, constructor, node);
            }
            Object[] frame = plan.frame(arguments);
            frame[PhpFunction.ARG_THIS] = object;
            frame[PhpFunction.ARG_STATIC] = object.getPhpClass();
            plan.enter(frame, arguments, constructor, callNode);
            callNode.call(constructor.getCallTarget(), frame);
            return object;
        }

        @Specialization(guards = "isByValue(constructor)")
        public static PhpObject withoutConstructor(NamedSite site, PhpObject object, PhpFunction constructor,
                                                   @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments) {
            return object;
        }

        public static boolean isByValue(PhpFunction function) {
            return MethodSite.byValue(function);
        }
    }

    /** A Closure's own method called with names (Closure::call, bindTo...): the built-in, or php-src's Error. */
    @TruffleBoundary
    private static PhpFunction closureMethod(Node node, String name) {
        PhpFunction method = PhpContext.get(node).getClass("Closure").findMethod(Str.lower(name));
        if (method == null) {
            throw new PhpError("Call to undefined method Closure::" + name + "()");
        }
        return method;
    }

    /*
     * Named arguments a variadic parameter collects (php-src's extra_named_params, NamedExtras): passed in the
     * ARG_CLOSURE slot, taken first thing by the callee, appended to its variadic parameter's array.
     */

    /**
     * A variadic function's first instruction (and its defaults target's): the extras a call passed in the closure
     * slot go to the hidden local extras, and the slot gets its own value back before anything reads it (a default's
     * `self::` in a closure reads the scope there). Nothing while no call has passed the function extras.
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    public static final class TakeNamedExtras {
        @Specialization
        public static void take(VirtualFrame frame, LocalAccessor extras, @Bind PhpBytecodeRootNode root,
                                @Bind BytecodeNode bytecode) {
            if (root.noNamedExtras.isValid()) {
                return;
            }
            Object[] arguments = frame.getArguments();
            if (arguments[PhpFunction.ARG_CLOSURE] instanceof NamedExtras passed) {
                arguments[PhpFunction.ARG_CLOSURE] = passed.slot();
                extras.setObject(bytecode, frame, passed);
            }
        }
    }

    /**
     * After BindVariadic (ZEND_RECV_VARIADIC): the extras under their names after the positional ones. A typed
     * variadic checks each with the argument number after the last one passed (and no name, as for any variadic
     * argument): "collectInt(): Argument #2 must be of type int, string given".
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class, name = "variadic")
    @ConstantOperand(type = LocalAccessor.class, name = "extras")
    @ConstantOperand(type = int.class)
    public static final class BindNamedExtras {
        @Specialization
        public static void bind(VirtualFrame frame, LocalAccessor variadic, LocalAccessor extras, int index,
                                @Bind PhpBytecodeRootNode root, @Bind BytecodeNode bytecode) {
            if (root.noNamedExtras.isValid() || extras.isCleared(bytecode, frame)) {
                return;
            }
            Object[] arguments = frame.getArguments();
            append((PhpArray) variadic.getObject(bytecode, frame), (NamedExtras) extras.getObject(bytecode, frame),
                    root.params[index], Math.max(arguments.length - PhpFunction.ARG_FIRST, index), root.name,
                    calledClass(frame), bytecode);
        }

        /** Each extra checked (number: its argument index) and stored, a by-reference variadic's as a reference. */
        @TruffleBoundary
        private static void append(PhpArray rest, NamedExtras passed, PhpFunction.Param param, int number,
                                   String function, PhpClass calledClass, Node node) {
            for (int i = 0; i < passed.count(); i++) {
                Object value = passed.value(i);
                if (param.byRef()) {
                    PhpRef ref = value instanceof PhpRef passedRef ? passedRef : new PhpRef(value);
                    if (param.declared() != null) {
                        FunctionRootNode.checkArgument(function, param, number, ref, calledClass, node);
                    }
                    rest.putRef(passed.name(i), ref);
                    continue;
                }
                value = PhpRef.deref(value);
                if (param.declared() != null) {
                    value = FunctionRootNode.checkArgument(function, param, number, value, calledClass, node);
                }
                rest.put(passed.name(i), value);
            }
        }
    }

    /*
     * A user function's defaults target (BytecodeTranslator.defaults): run on the frame arguments of a named call
     * that left some out, before the callee, as php-src's zend_handle_undef_args runs in a fake frame of the callee.
     */

    /** Whether the call left the argument of parameter index out (a hole, PhpFunction.NOT_PASSED). */
    @Operation
    @ConstantOperand(type = int.class)
    public static final class IsNotPassed {
        @Specialization
        public static boolean notPassed(VirtualFrame frame, int index) {
            Object[] arguments = frame.getArguments();
            return PhpFunction.ARG_FIRST + index < arguments.length
                    && arguments[PhpFunction.ARG_FIRST + index] == PhpFunction.NOT_PASSED;
        }
    }

    /** A required parameter left out: php-src's ArgumentCountError "f(): Argument #2 ($b) not passed". */
    @Operation
    @ConstantOperand(type = int.class)
    public static final class NotPassed {
        @Specialization
        public static void fail(int index, @Bind PhpBytecodeRootNode root) {
            throw BindingPlan.notPassed(root.name, root.params, index);
        }
    }

    /** A left-out parameter's default, a passed argument from then on (RECV_INIT checks it, func_get_args lists it). */
    @Operation
    @ConstantOperand(type = int.class)
    public static final class SetArgument {
        @Specialization
        public static void set(VirtualFrame frame, int index, Object value) {
            frame.getArguments()[PhpFunction.ARG_FIRST + index] = value;
        }
    }

    // ---- what the runtime asks of a function's root ----

    public CallTarget linkedFrame() {
        CallTarget target = linkedFrame;
        if (target == null) {
            target = new PhpGenerator.LinkedFrame(getLanguage(PhpLanguage.class), this).getCallTarget();
            linkedFrame = target;
        }
        return target;
    }

    @Override
    public boolean describe(Frame frame, PhpArray entry, int options, Backtrace.CallSite site) {
        if (thunk) {
            return false;
        }
        if (file) {
            // an included file is listed as its include; the script itself is {main}, and the prelude's aren't
            String kind = site == null ? null : site.includeKind();
            if (kind == null) {
                return false;
            }
            Backtrace.describeInclude(entry, kind, path, options);
            return true;
        }
        BytecodeNode bytecode = getBytecodeNode();
        if (defaults) {
            Object[] arguments = frame.getArguments();
            FunctionRootNode.describeCall(name, className, params, arguments, i -> {
                Object argument = arguments[PhpFunction.ARG_FIRST + i];
                return argument == PhpFunction.NOT_PASSED ? PhpNull.NULL : argument;
            }, entry, options);
        } else {
            FunctionRootNode.describeCall(name, className, params, frame.getArguments(),
                    i -> bytecode.getLocalValue(0, frame, parameterLocals[i]), entry, options);
        }
        // the extra named arguments under their names, after the positional ones (debug_backtrace_get_args)
        if (namedExtrasLocal >= 0 && (options & Backtrace.IGNORE_ARGS) == 0
                && bytecode.getLocalValue(0, frame, namedExtrasLocal) instanceof NamedExtras extras
                && entry.get(Str.of("args")) instanceof PhpArray args) {
            extras.describe(args);
        }
        return true;
    }

    /** Whether this root is {@link LargeCode large}: 0 not counted yet, 1 no, 2 yes. */
    private byte large;

    /** Large by its instructions, as the tree's roots are by their nodes (about one instruction per node). */
    @Override
    public boolean isLarge() {
        if (large == 0) {
                large = getBytecodeNode().getInstructionsAsList().size() >= LargeCode.LARGE ? (byte) 2 : (byte) 1;
        }
        return large == 2;
    }

    /** What self:: means in this function's code (FunctionRootNode.getScopeName). */
    public String getScopeName() {
        return scopeName;
    }

    /**
     * Splitting, as for the tree's functions: a copy per caller profile. The released processors 25.1 to 25.4 make
     * the copy (the generated cloneUninitialized) with the original's specialization state, which lives in the
     * bytecode since GR-61293, and the cached nodes anew, so a @Fallback guard trusting that state answered wrong
     * (is_wp_error()'s instanceof false for a WP_Error; tests/smoke/split-copies.php). The patched processor
     * (truffle-dsl-processor/, GR-79787) clears the state in unquickenBytecode, so the copy specializes anew.
     */
    @Override
    public boolean isCloningAllowed() {
        return true;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public SourceSection getSourceSection() {
        return sourceSection;
    }

    @Override
    public boolean isInternal() {
        return internal;
    }

    @Override
    public String getPath() {
        return path;
    }
}
