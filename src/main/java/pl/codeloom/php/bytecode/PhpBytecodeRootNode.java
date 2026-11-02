package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.Assumption;
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
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.nodes.PhpTypes;
import pl.codeloom.php.nodes.ToStrNode;
import pl.codeloom.php.nodes.SubclassTestNode;
import pl.codeloom.php.nodes.call.ArgumentsNode;
import pl.codeloom.php.nodes.call.CallableCallNode;
import pl.codeloom.php.nodes.call.CallableConvertNodes;
import pl.codeloom.php.nodes.call.CallNode;
import pl.codeloom.php.nodes.call.CallableDispatchNode;
import pl.codeloom.php.nodes.call.FunctionRootNode;
import pl.codeloom.php.nodes.call.InstantiateNode;
import pl.codeloom.php.nodes.call.MethodCallNode;
import pl.codeloom.php.nodes.call.StaticCallNode;
import pl.codeloom.php.nodes.expr.ArrayLiteralNode;
import pl.codeloom.php.nodes.expr.AssignNodes;
import pl.codeloom.php.nodes.expr.Comparisons;
import pl.codeloom.php.nodes.expr.ConstantNodes;
import pl.codeloom.php.nodes.expr.InterpolationNode;
import pl.codeloom.php.nodes.expr.MatchNode;
import pl.codeloom.php.nodes.expr.TestNodes;
import pl.codeloom.php.nodes.expr.UnaryNodes;
import pl.codeloom.php.nodes.intrinsics.BuiltinNodes;
import pl.codeloom.php.nodes.stmt.BasicStmts;
import pl.codeloom.php.nodes.stmt.ForeachNode;
import pl.codeloom.php.nodes.stmt.GeneratorRefIteration;
import pl.codeloom.php.nodes.stmt.TryNode;
import pl.codeloom.php.nodes.vars.ClassRefNode;
import pl.codeloom.php.nodes.vars.DimNode;
import pl.codeloom.php.nodes.vars.ElementNodes;
import pl.codeloom.php.nodes.vars.GlobalRefNode;
import pl.codeloom.php.nodes.vars.PropNode;
import pl.codeloom.php.nodes.vars.ThisNode;
import pl.codeloom.php.nodes.vars.Variables;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.ClassKey;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.Operators;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpGenerator;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.PhpThrowable;
import pl.codeloom.php.runtime.RefIteration;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

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
        Backtrace.Callee {
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
    @CompilationFinal private char returnCoerce;
    @CompilationFinal private boolean returnNullable;
    /** The PHP variables' locals (offsets 0 to their count - 1; hidden locals follow). */
    @CompilationFinal(dimensions = 1) private LocalAccessor[] variables;
    /** The hidden locals in which foreach loops keep what they iterate (an array they hold), cleared at the end. */
    @CompilationFinal(dimensions = 1) private LocalAccessor[] foreachStates;
    /** The class the function is declared in (a method's, a trait's user), or null; and its lowercase name. */
    @CompilationFinal private String className;
    @CompilationFinal private String scopeName;
    /** A generator function: `function &gen()` yields references; whether its body has a finally block. */
    @CompilationFinal private boolean generatorByRef;
    @CompilationFinal private boolean generatorHasFinally;
    /** `function &f()` (no generator): every return gives a PhpRef. */
    @CompilationFinal private boolean returnsRef;

    protected PhpBytecodeRootNode(PhpLanguage language, FrameDescriptor.Builder frameDescriptor) {
        super(language, frameDescriptor.build());
    }

    /** What the translator knows of the function, set once its bytecode is built (a reparse keeps it). */
    public void initialize(String functionName, String file, boolean internalCode, SourceSection section,
                    PhpFunction.Param[] parameters, int[] parameterOffsets, char coerce, boolean nullable,
                    LocalAccessor[] variableLocals, LocalAccessor[] foreachLocals, String declaringClass) {
        this.name = functionName;
        this.path = file;
        this.internal = internalCode;
        this.sourceSection = section;
        this.params = parameters;
        this.parameterLocals = parameterOffsets;
        this.returnCoerce = coerce;
        this.returnNullable = nullable;
        this.variables = variableLocals;
        this.foreachStates = foreachLocals;
        this.className = declaringClass;
        this.scopeName = declaringClass == null ? null : Str.lower(declaringClass);
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
     * Prints the bytecode as it is when the process exits (php.bytecode.dump), for debugging: after the run, the
     * instructions show what the interpreter has quickened them to.
     */
    public void dumpAtExit() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> System.err.println(getBytecodeNode().dump())));
    }

    /**
     * On the way out, however the call ends: the variables give up their arrays and references. An unset one is
     * skipped first: compiled code reads a local by the kind it has had (getLocalValue switches on it), and a
     * cleared local of kind Long or Object has no such value, which would deoptimize every call that leaves it
     * unset, and recompiling wouldn't change the kind.
     */
    @ExplodeLoop
    void releaseVariables(VirtualFrame frame, BytecodeNode bytecode) {
        for (LocalAccessor variable : variables) {
            if (!variable.isCleared(bytecode, frame)) {
                Variables.released(variable.getObject(bytecode, frame));
            }
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

    /** The value the function returns: the variables are released, then a declared scalar type converts it. */
    @EpilogReturn
    public static final class ReturnValue {
        @Specialization
        public static Object doReturn(VirtualFrame frame, Object value, @Bind PhpBytecodeRootNode root,
                                      @Bind BytecodeNode bytecode) {
            root.releaseVariables(frame, bytecode);
            if (root.returnCoerce == 0) {
                return value;
            }
            if (root.returnsRef) {
                // a declared scalar type converts the value in the returned reference (FunctionRootNode.coerceRef)
                PhpRef ref = (PhpRef) value;
                Object current = ref.get();
                Object coerced = FunctionRootNode.coerce(current, root.returnCoerce, root.returnNullable,
                        "return value");
                if (coerced != current) {
                    ref.set(coerced);
                }
                return ref;
            }
            return FunctionRootNode.coerce(value, root.returnCoerce, root.returnNullable, "return value");
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
            if (param.coerce() != 0) {
                value = FunctionRootNode.coerce(value, param.coerce(), param.nullable(), param.name());
            }
            if (value instanceof PhpArray array) {
                array.addHolder();
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
            variable.setObject(bytecode, frame, FunctionRootNode.collectVariadic(arguments, index,
                    arguments.length - PhpFunction.ARG_FIRST, root.params[index].byRef()));
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
            if (!byRef && captured instanceof PhpArray array) {
                array.addHolder();
            } else if (captured instanceof PhpRef ref) {
                ref.bind();
            }
            variable.setObject(bytecode, frame, captured);
        }
    }

    // ---- variables ----

    /** `$x`: the variable's value (a reference's content; null when unset), ints and floats unboxed. */
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
        public static Object read(VirtualFrame frame, LocalAccessor variable, @Bind BytecodeNode bytecode) {
            return Variables.value(Locals.stored(frame, variable, bytecode));
        }
    }

    /** `$x = value` (AssignNode on a LocalVarNode): through the reference if the variable is one. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    public static final class WriteVar {
        @Specialization
        public static long writeLong(VirtualFrame frame, LocalAccessor variable, long value,
                                     @Bind BytecodeNode bytecode) {
            Locals.writeLong(frame, variable, bytecode, value);
            return value;
        }

        @Specialization
        public static double writeDouble(VirtualFrame frame, LocalAccessor variable, double value,
                                         @Bind BytecodeNode bytecode) {
            Locals.writeDouble(frame, variable, bytecode, value);
            return value;
        }

        @Specialization(replaces = {"writeLong", "writeDouble"})
        public static Object write(VirtualFrame frame, LocalAccessor variable, Object value,
                                   @Bind BytecodeNode bytecode) {
            Locals.write(frame, variable, bytecode, value);
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
                                 @Bind BytecodeNode bytecode) throws Locals.KindMismatch {
            long old = Locals.readLong(frame, variable, bytecode);
            long updated = increment ? Math.addExact(old, 1) : Math.subtractExact(old, 1);
            Locals.writeLong(frame, variable, bytecode, updated);
            return prefix ? updated : old;
        }

        @Specialization(replaces = "longs")
        public static Object generic(VirtualFrame frame, LocalAccessor variable, boolean increment, boolean prefix,
                                     @Bind BytecodeNode bytecode, @Bind Node node) {
            Object old = Variables.value(Locals.stored(frame, variable, bytecode));
            Object updated = AssignNodes.IncDecNode.stepped(node, old, increment);
            Locals.write(frame, variable, bytecode, updated);
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
                                           @Bind BytecodeNode bytecode, @Cached ToStrNode currentToStr,
                                           @Cached ToStrNode operandToStr,
                                           @Cached TruffleString.ConcatNode concat) {
            TruffleString left = currentToStr.execute(Variables.value(Locals.stored(frame, variable, bytecode)));
            TruffleString result = concat.execute(left, operandToStr.execute(operand), Str.ENC, true);
            Locals.writeObject(frame, variable, bytecode, result);
            return result;
        }

        @Specialization(guards = "isIntOperator(op)", rewriteOn = {Locals.KindMismatch.class,
                ArithmeticException.class})
        public static long longs(VirtualFrame frame, LocalAccessor variable, int op, long operand,
                                 @Bind BytecodeNode bytecode) throws Locals.KindMismatch {
            long current = Locals.readLong(frame, variable, bytecode);
            long result = switch (op) {
                case 1 -> Math.addExact(current, operand);
                case 2 -> Math.subtractExact(current, operand);
                case 3 -> Math.multiplyExact(current, operand);
                case 9 -> current | operand;
                case 10 -> current & operand;
                default -> current ^ operand;
            };
            Locals.writeLong(frame, variable, bytecode, result);
            return result;
        }

        /** Declared before doubles, which the implicit int-to-float cast would otherwise let take an int. */
        @Specialization(guards = "op != 8")
        public static Object longOperand(VirtualFrame frame, LocalAccessor variable, int op, long operand,
                                         @Bind BytecodeNode bytecode, @Bind Node node) {
            return generic(frame, variable, op, operand, bytecode, node);
        }

        @Specialization(guards = "isFloatOperator(op)", rewriteOn = Locals.KindMismatch.class)
        public static double doubles(VirtualFrame frame, LocalAccessor variable, int op, double operand,
                                     @Bind BytecodeNode bytecode, @Bind Node node) throws Locals.KindMismatch {
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
            Locals.writeDouble(frame, variable, bytecode, result);
            return result;
        }

        @Specialization(guards = "op != 8", replaces = {"longs", "longOperand", "doubles"})
        public static Object generic(VirtualFrame frame, LocalAccessor variable, int op, Object operand,
                                     @Bind BytecodeNode bytecode, @Bind Node node) {
            Object current = Variables.value(Locals.stored(frame, variable, bytecode));
            Object result = AssignNodes.CompoundAssignNode.apply(node, op, current, operand);
            Locals.write(frame, variable, bytecode, result);
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
                                     @Bind BytecodeNode bytecode) {
            Locals.writeLong(frame, variable, bytecode, value);
        }

        @ForceQuickening
        @Specialization
        public static void writeDouble(VirtualFrame frame, LocalAccessor variable, double value,
                                       @Bind BytecodeNode bytecode) {
            Locals.writeDouble(frame, variable, bytecode, value);
        }

        @Specialization(replaces = {"writeLong", "writeDouble"})
        public static void write(VirtualFrame frame, LocalAccessor variable, Object value,
                                 @Bind BytecodeNode bytecode) {
            Locals.write(frame, variable, bytecode, value);
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
                                 @Bind BytecodeNode bytecode) throws Locals.KindMismatch {
            IncDecVar.longs(frame, variable, increment, true, bytecode);
        }

        @Specialization(replaces = "longs")
        public static void generic(VirtualFrame frame, LocalAccessor variable, boolean increment,
                                   @Bind BytecodeNode bytecode, @Bind Node node) {
            IncDecVar.generic(frame, variable, increment, true, bytecode, node);
        }
    }

    /** `$x op= value;` as a statement: CompoundVar without the result. */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = int.class, name = "op")
    public static final class CompoundVarStatement {
        @Specialization(guards = "op == 8")
        public static void concat(VirtualFrame frame, LocalAccessor variable, int op, Object operand,
                                  @Bind BytecodeNode bytecode, @Cached ToStrNode currentToStr,
                                  @Cached ToStrNode operandToStr, @Cached TruffleString.ConcatNode concat) {
            CompoundVar.concat(frame, variable, op, operand, bytecode, currentToStr, operandToStr, concat);
        }

        @ForceQuickening
        @Specialization(guards = "isIntOperator(op)", rewriteOn = {Locals.KindMismatch.class,
                ArithmeticException.class})
        public static void longs(VirtualFrame frame, LocalAccessor variable, int op, long operand,
                                 @Bind BytecodeNode bytecode) throws Locals.KindMismatch {
            CompoundVar.longs(frame, variable, op, operand, bytecode);
        }

        @Specialization(guards = "op != 8")
        public static void longOperand(VirtualFrame frame, LocalAccessor variable, int op, long operand,
                                       @Bind BytecodeNode bytecode, @Bind Node node) {
            CompoundVar.generic(frame, variable, op, operand, bytecode, node);
        }

        @ForceQuickening
        @Specialization(guards = "isFloatOperator(op)", rewriteOn = Locals.KindMismatch.class)
        public static void doubles(VirtualFrame frame, LocalAccessor variable, int op, double operand,
                                   @Bind BytecodeNode bytecode, @Bind Node node) throws Locals.KindMismatch {
            CompoundVar.doubles(frame, variable, op, operand, bytecode, node);
        }

        @Specialization(guards = "op != 8", replaces = {"longs", "longOperand", "doubles"})
        public static void generic(VirtualFrame frame, LocalAccessor variable, int op, Object operand,
                                   @Bind BytecodeNode bytecode, @Bind Node node) {
            CompoundVar.generic(frame, variable, op, operand, bytecode, node);
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
                                   @Cached InlinedBranchProfile createdArray) {
            PhpArray held = value instanceof PhpArray array ? array : null;
            if (held != null) {
                held.addHolder();
            }
            try {
                PhpArray array = Locals.arrayForWrite(frame, variable, bytecode, node, sharedArray, refArray,
                        createdArray);
                if (array == null) {
                    DimNode.writeToObject(node, Variables.value(Locals.stored(frame, variable, bytecode)), key,
                            value);
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
                                    @Cached InlinedBranchProfile createdArray) {
            PhpArray held = value instanceof PhpArray array ? array : null;
            if (held != null) {
                held.addHolder();
            }
            try {
                PhpArray array = Locals.arrayForWrite(frame, variable, bytecode, node, sharedArray, refArray,
                        createdArray);
                if (array == null) {
                    DimNode.writeToObject(node, Variables.value(Locals.stored(frame, variable, bytecode)),
                            PhpNull.NULL, value);
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
        public static Object array(PhpArray container, Object key, @Shared @Cached ElementNodes.ElementReadNode read) {
            return read.execute(container, key);
        }

        @Fallback
        public static Object other(Object container, Object key, @Shared @Cached ElementNodes.ElementReadNode read,
                                   @Bind Node node) {
            if (container == PhpNull.NULL || !DimNode.isSetIn(node, container, key)) {
                return PhpNull.NULL;
            }
            return read.execute(container, key);
        }
    }

    /** `isset($container[key])` (DimNode.isSet): as ElementIfSet, but an ArrayAccess object's offsetExists() alone. */
    @Operation
    public static final class IssetElement {
        @Specialization
        public static boolean array(PhpArray container, Object key, @Cached ElementNodes.ElementReadNode read) {
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
                                   @Cached ElementNodes.ElementReadNode read,
                                   @Cached ElementNodes.ElementWriteNode write,
                                   @Cached InlinedBranchProfile sharedArray,
                                   @Cached InlinedBranchProfile refArray,
                                   @Cached InlinedBranchProfile createdArray) {
            PhpArray held = value instanceof PhpArray array ? array : null;
            if (held != null) {
                held.addHolder();
            }
            try {
                Object base = variableForWrite(frame, variable, bytecode, node, sharedArray, refArray, createdArray);
                int levels = append ? keys.length : keys.length - 1;
                Object container = ElementChain.forWrite(node, base, keys, levels, toKey, read);
                ElementChain.write(node, container, append ? ElementChain.APPEND : keys[levels], value, write);
            } finally {
                if (held != null) {
                    held.removeHolder();
                }
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
                                   @Cached ElementNodes.ElementReadNode read,
                                   @Cached ElementNodes.ElementWriteNode write) {
            PhpArray held = value instanceof PhpArray array ? array : null;
            if (held != null) {
                held.addHolder();
            }
            try {
                Object base = propertyForWrite(frame, access, object, site.name);
                int levels = append ? keys.length : keys.length - 1;
                Object container = ElementChain.forWrite(node, base, keys, levels, toKey, read);
                ElementChain.write(node, container, append ? ElementChain.APPEND : keys[levels], value, write);
            } finally {
                if (held != null) {
                    held.removeHolder();
                }
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
                                      @Cached InlinedBranchProfile sharedArray,
                                      @Cached InlinedBranchProfile refArray,
                                      @Cached InlinedBranchProfile createdArray) {
            Object base = variableForWrite(frame, variable, bytecode, node, sharedArray, refArray, createdArray);
            int last = keys.length - 1;
            Object container = ElementChain.forWrite(node, base, keys, last, toKey, read);
            Object result = compounded(node, op, read.execute(container, keys[last]), operand);
            ElementChain.write(node, container, keys[last], result, write);
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
                                      @Cached ElementNodes.ElementWriteNode write) {
            Object base = propertyForWrite(frame, access, object, site.name);
            int last = keys.length - 1;
            Object container = ElementChain.forWrite(node, base, keys, last, toKey, read);
            Object result = compounded(node, op, read.execute(container, keys[last]), operand);
            ElementChain.write(node, container, keys[last], result, write);
            return result;
        }
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
                                  @Cached ElementNodes.ElementWriteNode write,
                                  @Cached InlinedBranchProfile sharedArray,
                                  @Cached InlinedBranchProfile refArray,
                                  @Cached InlinedBranchProfile createdArray) {
            Object base = variableForWrite(frame, variable, bytecode, node, sharedArray, refArray, createdArray);
            int last = keys.length - 1;
            Object container = ElementChain.forWrite(node, base, keys, last, toKey, read);
            Object old = read.execute(container, keys[last]);
            Object updated = AssignNodes.IncDecNode.stepped(node, old, increment);
            ElementChain.write(node, container, keys[last], updated, write);
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
                                  @Cached ElementNodes.ElementWriteNode write) {
            Object base = propertyForWrite(frame, access, object, site.name);
            int last = keys.length - 1;
            Object container = ElementChain.forWrite(node, base, keys, last, toKey, read);
            Object old = read.execute(container, keys[last]);
            Object updated = AssignNodes.IncDecNode.stepped(node, old, increment);
            ElementChain.write(node, container, keys[last], updated, write);
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
                                 @Cached ElementNodes.ElementReadNode read,
                                 @Cached InlinedBranchProfile sharedArray,
                                 @Cached InlinedBranchProfile refArray,
                                 @Cached InlinedBranchProfile createdArray) {
            Object container = ElementChain.read(Variables.value(Locals.stored(frame, variable, bytecode)), keys,
                    read);
            Object key = ElementChain.removed(node, container, keys, toKey);
            if (key != null) {
                Object base = variableForWrite(frame, variable, bytecode, node, sharedArray, refArray,
                        createdArray);
                ElementChain.remove(node, base, keys, key, toKey, read);
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
                                 @Cached ElementNodes.ElementReadNode read) {
            Object container = ElementChain.read(access.readOn(frame, object, site.name), keys, read);
            Object key = ElementChain.removed(node, container, keys, toKey);
            if (key != null) {
                ElementChain.remove(node, propertyForWrite(frame, access, object, site.name), keys, key, toKey,
                        read);
            }
        }
    }

    /**
     * An element's value combined with a compound assignment's operand (CompoundAssignNode): `.=` converts the
     * element first, then the operand.
     */
    private static Object compounded(Node node, int op, Object current, Object operand) {
        if (op == AssignNodes.CompoundAssignNode.CONCAT) {
            return concatenated(current, operand);
        }
        return AssignNodes.CompoundAssignNode.apply(node, op, current, operand);
    }

    private static TruffleString concatenated(Object current, Object operand) {
        TruffleString left = Convert.toStr(current);
        return Str.concat(left, Convert.toStr(operand));
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

    /** A property as the base of an element chain written (PropNode.arrayForWrite), else the object it holds. */
    private static Object propertyForWrite(VirtualFrame frame, PropNode access, Object object, String name) {
        PhpArray array = access.arrayForWriteOn(frame, object, name);
        return array != null ? array : access.readOn(frame, object, name);
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
            return access.readOn(frame, object, site.name);
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
            return access.readOn(frame, object, name);
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
            access.writeOn(frame, object, site.name, value);
            return value;
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
            access.writeOn(frame, object, name, value);
            return value;
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
            PhpObject target = propertyTarget(object, site.name, "assign");
            TruffleString left = currentToStr.execute(access.readOn(frame, target, site.name));
            TruffleString result = concat.execute(left, operandToStr.execute(operand), Str.ENC, true);
            access.writeOn(frame, target, site.name, result);
            return result;
        }

        @Specialization(guards = "op != 8")
        public static Object generic(VirtualFrame frame, PropSite site, int op, Object operand, Object object,
                                     @Cached(value = "site.create()", uncached = "site.uncached()",
                                             neverDefault = true) PropNode access, @Bind Node node) {
            PhpObject target = propertyTarget(object, site.name, "assign");
            Object result = AssignNodes.CompoundAssignNode.apply(node, op, access.readOn(frame, target, site.name),
                    operand);
            access.writeOn(frame, target, site.name, result);
            return result;
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
            PhpObject target = propertyTarget(object, site.name, "increment/decrement");
            Object old = access.readOn(frame, target, site.name);
            Object updated = AssignNodes.IncDecNode.stepped(node, old, increment);
            access.writeOn(frame, target, site.name, updated);
            return prefix ? updated : old;
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
            access.unsetOn(frame, object, site.name);
        }
    }

    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class UnsetPropNamed {
        @Specialization
        public static void unset(VirtualFrame frame, PropSite site, Object object, String name,
                                 @Cached(value = "site.create()", uncached = "site.uncached()",
                                         neverDefault = true) PropNode access) {
            access.unsetOn(frame, object, name);
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
            return access.isSetOn(frame, object, site.name);
        }
    }

    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class IssetPropNamed {
        @Specialization
        public static boolean isSet(VirtualFrame frame, PropSite site, Object object, String name,
                                    @Cached(value = "site.create()", uncached = "site.uncached()",
                                            neverDefault = true) PropNode access) {
            return access.isSetOn(frame, object, name);
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
            Object value = access.readIfSetOn(frame, object, site.name);
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
            PhpArray held = value instanceof PhpArray array ? array : null;
            if (held != null) {
                held.addHolder();
            }
            try {
                PhpArray array = access.arrayForWriteOn(frame, object, site.name);
                if (array == null) {
                    DimNode.writeToObject(node, access.readOn(frame, object, site.name), key, value);
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
    }

    /** `$obj->name[] = value`. */
    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class AppendPropElement {
        @Specialization
        public static Object append(VirtualFrame frame, PropSite site, Object value, Object object,
                                    @Cached(value = "site.create()", uncached = "site.uncached()",
                                            neverDefault = true) PropNode access, @Bind Node node) {
            PhpArray held = value instanceof PhpArray array ? array : null;
            if (held != null) {
                held.addHolder();
            }
            try {
                PhpArray array = access.arrayForWriteOn(frame, object, site.name);
                if (array == null) {
                    DimNode.writeToObject(node, access.readOn(frame, object, site.name), PhpNull.NULL, value);
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
        public static PhpArray spread(PhpArray array, Object source) {
            ArrayLiteralNode.spread(array, source);
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
     * An element of a list() assignment's value (FETCH_LIST_R): an array's element, null when it is missing; an
     * ArrayAccess object's offsetGet(); null for anything else (ListAssignNode).
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
        public static Object other(Object container, Object key) {
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

    // ---- calls ----

    /** The function a call by name calls, looked up before its arguments as php-src's INIT_FCALL does. */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    public static final class ResolveFunction {
        /**
         * With one context, the function found first stays (functions can't be redeclared). The uncached
         * interpreter has no cache to keep it in: it looks the function up each time (perContext).
         */
        @Specialization(assumptions = "singleContext()", excludeForUncached = true)
        public static PhpFunction single(CallInfo call, @Cached("call.find(context())") PhpFunction function) {
            return function;
        }

        @Specialization(replaces = "single")
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

    /** A call: directly while the site sees one function (nearly always), else indirectly; the value it returns. */
    @Operation
    @ConstantOperand(type = CallInfo.class)
    public static final class Call {
        @Specialization(guards = "function == cachedFunction", limit = "1")
        public static Object direct(CallInfo call, PhpFunction function,
                                    @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                    @Cached("function") PhpFunction cachedFunction,
                                    @Cached("create(cachedFunction.getCallTarget())") DirectCallNode callNode) {
            return PhpRef.deref(callNode.call(arguments));
        }

        @Specialization(replaces = "direct")
        public static Object indirect(CallInfo call, PhpFunction function,
                                      @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                      @Cached IndirectCallNode callNode) {
            return PhpRef.deref(callNode.call(function.getCallTarget(), arguments));
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
        @Specialization
        public static Object send(VirtualFrame frame, LocalAccessor variable, int index, PhpFunction function,
                                  @Bind BytecodeNode bytecode) {
            if (function.isByRef(index)) {
                return Locals.makeRef(frame, variable, bytecode);
            }
            return Variables.value(Locals.stored(frame, variable, bytecode));
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
        @Specialization
        public static Object send(VirtualFrame frame, LocalAccessor variable, int index, PhpFunction function,
                                  Object key, @Bind BytecodeNode bytecode, @Bind Node node,
                                  @Cached ElementNodes.ElementReadNode read,
                                  @Cached ElementNodes.ArrayKeyNode toKey,
                                  @Cached InlinedBranchProfile byRef,
                                  @Cached InlinedBranchProfile sharedArray,
                                  @Cached InlinedBranchProfile refArray,
                                  @Cached InlinedBranchProfile createdArray) {
            if (!function.isByRef(index)) {
                return read.execute(Variables.value(Locals.stored(frame, variable, bytecode)), key);
            }
            byRef.enter(node);
            PhpArray array = Locals.arrayForWrite(frame, variable, bytecode, node, sharedArray, refArray,
                    createdArray);
            if (array == null) {
                throw overloadedElement();
            }
            return array.refAt(toKey.execute(key));
        }

        @TruffleBoundary
        private static PhpError overloadedElement() {
            return new PhpError("Indirect modification of overloaded element");
        }
    }

    /** A property of an object as argument index: its reference (PropNode.makeRef) or its value. */
    @Operation
    @ConstantOperand(type = PropSite.class)
    @ConstantOperand(type = int.class)
    public static final class SendProp {
        @Specialization
        public static Object send(VirtualFrame frame, PropSite site, int index, PhpFunction function, Object object,
                                  @Cached(value = "site.create()", uncached = "site.uncached()",
                                          neverDefault = true) PropNode access) {
            if (function.isByRef(index)) {
                return access.makeRefOn(frame, object, site.name);
            }
            return access.readOn(frame, object, site.name);
        }
    }

    /**
     * A nested element `$x[k1]...[kn]` as argument index: its reference (RefNested) when the parameter is by
     * reference, else its value read down the chain (DimNode.execute).
     */
    @Operation
    @ConstantOperand(type = LocalAccessor.class)
    @ConstantOperand(type = int.class)
    public static final class SendNested {
        @Specialization
        public static Object send(VirtualFrame frame, LocalAccessor variable, int index, PhpFunction function,
                                  @Variadic Object[] keys, @Bind BytecodeNode bytecode, @Bind Node node,
                                  @Cached ElementNodes.ArrayKeyNode toKey,
                                  @Cached ElementNodes.ElementReadNode read,
                                  @Cached InlinedBranchProfile sharedArray,
                                  @Cached InlinedBranchProfile refArray,
                                  @Cached InlinedBranchProfile createdArray) {
            if (!function.isByRef(index)) {
                return readChain(Variables.value(Locals.stored(frame, variable, bytecode)), keys, read);
            }
            Object base = variableForWrite(frame, variable, bytecode, node, sharedArray, refArray, createdArray);
            return elementRef(node, base, keys, false, toKey, read);
        }
    }

    /** `$obj->name[k1]...[kn]` as argument index: as SendNested, from the property. */
    @Operation
    @ConstantOperand(type = PropSite.class)
    @ConstantOperand(type = int.class)
    public static final class SendPropNested {
        @Specialization
        public static Object send(VirtualFrame frame, PropSite site, int index, PhpFunction function, Object object,
                                  @Variadic Object[] keys,
                                  @Cached(value = "site.create()", uncached = "site.uncached()",
                                          neverDefault = true) PropNode access,
                                  @Bind Node node, @Cached ElementNodes.ArrayKeyNode toKey,
                                  @Cached ElementNodes.ElementReadNode read) {
            if (!function.isByRef(index)) {
                return readChain(access.readOn(frame, object, site.name), keys, read);
            }
            return elementRef(node, propertyForWrite(frame, access, object, site.name), keys, false, toKey, read);
        }
    }

    /** base[k1]...[kn] read (DimNode.execute down a chain). */
    private static Object readChain(Object base, Object[] keys, ElementNodes.ElementReadNode read) {
        Object value = base;
        for (Object key : keys) {
            value = read.execute(value, key);
        }
        return value;
    }

    /**
     * A call's result as argument index (SEND_VAR_NO_REF): for a by-reference parameter, the reference a function
     * returning by reference returned, else the value with php-src's notice.
     */
    @Operation
    @ConstantOperand(type = int.class)
    public static final class SendCall {
        @Specialization
        public static Object send(int index, PhpFunction function, Object result, @Bind Node node) {
            if (!function.isByRef(index)) {
                return PhpRef.deref(result);
            }
            if (!(result instanceof PhpRef)) {
                CallNode.notice(node, CallNode.PASSED_VALUE);
            }
            return result;
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
        public static Callables.Target resolve(Object callable, @Bind Node node) {
            return Callables.resolveCall(PhpContext.get(node), callable);
        }
    }

    /** What `$obj->$name(args)` calls (DynamicMethodCallNode): the callable [$obj, name], the name a string. */
    @Operation
    public static final class ResolveDynamicMethod {
        @Specialization
        public static Callables.Target resolve(Object object, Object name, @Bind Node node,
                                               @Cached ToStrNode toStr) {
            return Callables.resolveCall(PhpContext.get(node), PhpArray.list(object, toStr.execute(name)));
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
            return target.magicName() != null ? MethodSite.BY_VALUE : target.function();
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

    /** call_user_func_array($f, $args): as CallUserFunc, the arguments the array's elements (references stay). */
    @Operation
    @ConstantOperand(type = boolean.class, name = "keepRef")
    public static final class CallUserFuncArray {
        static final Callables.Parameter PARAMETER = new Callables.Parameter("call_user_func_array", 1, false);

        @Specialization(excludeForUncached = true)
        public static Object cached(boolean keepRef, Object callable, Object args,
                                    @Cached("create(PARAMETER, keepRef)") CallableDispatchNode dispatch) {
            return dispatch.execute(callable, CallableCallNode.arrayArguments(args));
        }

        @Specialization(replaces = "cached")
        public static Object uncached(boolean keepRef, Object callable, Object args, @Bind Node node) {
            Object[] values = CallableCallNode.arrayArguments(args);
            return callTarget(Callables.resolveOrThrow(PhpContext.get(node), callable, PARAMETER), values, keepRef);
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

    /** php-src's E_DEPRECATED at this instruction's line. */
    @Operation
    @ConstantOperand(type = String.class, name = "message")
    public static final class Deprecation {
        @Specialization
        public static void deprecated(String message, @Bind Node node) {
            deprecation(node, message);
        }
    }

    @TruffleBoundary
    private static void deprecation(Node node, String message) {
        ErrorFunctions.raise(PhpContext.get(node), ErrorFunctions.E_DEPRECATED, Str.of(message),
                SourceLocation.of(node));
    }

    /** func_num_args(): how many arguments the call passed. */
    @Operation
    public static final class FuncNumArgs {
        @Specialization
        public static long count(VirtualFrame frame) {
            return frame.getArguments().length - PhpFunction.ARG_FIRST;
        }
    }

    /** `f(...)` (CallableConvertNodes.FunctionNode): a Closure of the function the name resolves to. */
    @Operation
    @ConstantOperand(type = String.class, name = "displayName")
    @ConstantOperand(type = int.class, name = "id")
    @ConstantOperand(type = int.class, name = "fallbackId")
    public static final class ConvertFunction {
        @Specialization
        public static Object convert(String displayName, int id, int fallbackId, @Bind Node node) {
            return CallableConvertNodes.FunctionNode.convert(PhpContext.get(node), displayName, id, fallbackId);
        }
    }

    /** `$obj->name(...)`, `$obj->$name(...)` (CallableConvertNodes.MethodNode): the object, then the name. */
    @Operation
    public static final class ConvertMethod {
        @Specialization
        public static Object convert(Object object, Object name, @Cached ToStrNode toStr) {
            return CallableConvertNodes.MethodNode.convert(object, Str.java(toStr.execute(name)));
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
        public static Object convert(VirtualFrame frame, String name, boolean forwarding, PhpClass phpClass) {
            Object[] caller = frame.getArguments();
            Object called = caller[PhpFunction.ARG_STATIC];
            return CallableConvertNodes.StaticMethodNode.convert(phpClass, caller[PhpFunction.ARG_THIS],
                    forwarding && called instanceof PhpClass calledClass ? calledClass : phpClass, name);
        }
    }

    /** `$f(...)` (CallableConvertNodes.ValueNode): what the call `$f()` would call; a Closure stays itself. */
    @Operation
    public static final class ConvertValue {
        @Specialization
        public static Object convert(Object callable, @Bind Node node) {
            return CallableConvertNodes.ValueNode.convert(PhpContext.get(node), callable);
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
                }
                captured[i] = value;
            }
            Object self = isStatic ? null : frame.getArguments()[PhpFunction.ARG_THIS];
            return new PhpClosure(function, self instanceof PhpObject object ? object : null,
                    scope instanceof PhpClass phpClass ? phpClass : null, captured);
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
        @Specialization(guards = "receiver.getPhpClass().getKey() == cachedKey", limit = "4")
        public static PhpFunction cached(MethodSite site, PhpObject receiver,
                                         @Cached("receiver.getPhpClass().getKey()") ClassKey cachedKey,
                                         @Cached("find(site, receiver.getPhpClass())") PhpFunction method) {
            return method;
        }

        @Specialization(replaces = "cached")
        public static PhpFunction object(MethodSite site, PhpObject receiver) {
            return find(site, receiver.getPhpClass());
        }

        @Specialization
        public static PhpFunction closure(MethodSite site, PhpClosure receiver) {
            return MethodSite.BY_VALUE;
        }

        @Fallback
        public static PhpFunction other(MethodSite site, Object receiver) {
            throw MethodCallNode.memberOnNonObject(site.lowerName, receiver);
        }

        public static PhpFunction find(MethodSite site, PhpClass phpClass) {
            PhpFunction method = MethodCallNode.Dispatch.findMethod(phpClass, site.lowerName);
            return isMagic(phpClass, site.lowerName) ? MethodSite.BY_VALUE : method;
        }

        @TruffleBoundary
        private static boolean isMagic(PhpClass phpClass, String lowerName) {
            return phpClass.findMethod(lowerName) == null;
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
        public static Object direct(MethodSite site, boolean keepRef, Object receiver, PhpFunction function,
                                    @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                    @Cached("function") PhpFunction cachedFunction,
                                    @Cached("create(cachedFunction.getCallTarget())") DirectCallNode callNode) {
            PhpObject object = (PhpObject) receiver;
            arguments[PhpFunction.ARG_THIS] = cachedFunction.isStatic() ? null : object;
            arguments[PhpFunction.ARG_STATIC] = object.getPhpClass();
            return result(callNode.call(arguments), keepRef);
        }

        @Specialization(guards = "!isByValue(function)", replaces = "direct")
        public static Object indirect(MethodSite site, boolean keepRef, Object receiver, PhpFunction function,
                                      @Variadic(startOffset = PhpFunction.ARG_FIRST) Object[] arguments,
                                      @Shared @Cached IndirectCallNode callNode) {
            PhpObject object = (PhpObject) receiver;
            arguments[PhpFunction.ARG_THIS] = function.isStatic() ? null : object;
            arguments[PhpFunction.ARG_STATIC] = object.getPhpClass();
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
                            CallableDispatchNode.closureArguments(closure, values)), keepRef);
                }
                return result(MethodCallNode.closureMethod(node, site.lowerName, closure, values), keepRef);
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

    /** A call's arguments, without the frame's first slots. */
    private static Object[] values(Object[] arguments) {
        Object[] values = new Object[arguments.length - PhpFunction.ARG_FIRST];
        System.arraycopy(arguments, PhpFunction.ARG_FIRST, values, 0, values.length);
        return values;
    }

    private static Object result(Object returned, boolean keepRef) {
        return keepRef ? returned : PhpRef.deref(returned);
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
        @Specialization(guards = "phpClass.getKey() == cachedKey", limit = "4")
        public static PhpFunction cached(MethodSite site, PhpClass phpClass,
                                         @Cached("phpClass.getKey()") ClassKey cachedKey,
                                         @Cached("find(site, phpClass)") PhpFunction method) {
            return method;
        }

        @Specialization(replaces = "cached")
        public static PhpFunction generic(MethodSite site, PhpClass phpClass) {
            return find(site, phpClass);
        }

        public static PhpFunction find(MethodSite site, PhpClass phpClass) {
            PhpFunction method = StaticCallNode.Dispatch.findStatic(phpClass, site.lowerName);
            return method == null ? MethodSite.BY_VALUE : method;
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
                                   @Shared @Cached SubclassTestNode subclassTest) {
            Object[] caller = frame.getArguments();
            Object self = callerThis(caller, phpClass, subclassTest);
            PhpClass staticClass = staticClass(site, caller, phpClass);
            Object[] values = Callables.magicValues(site.calledName, values(arguments));
            PhpFunction viaCall = self != null ? magicMethod(phpClass, "__call") : null;
            if (viaCall != null) {
                return result(callNode.call(viaCall.getCallTarget(),
                        ArgumentsNode.frameArguments(self, staticClass, null, values)), keepRef);
            }
            PhpFunction viaStatic = magicMethod(phpClass, "__callstatic");
            if (viaStatic == null) {
                throw StaticCallNode.Dispatch.undefined(phpClass, site.lowerName);
            }
            return result(callNode.call(viaStatic.getCallTarget(),
                    ArgumentsNode.frameArguments(null, staticClass, null, values)), keepRef);
        }

        /** The caller's $this when it is an instance of the class. */
        private static Object callerThis(Object[] caller, PhpClass phpClass, SubclassTestNode subclassTest) {
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
        @Specialization(guards = {"cacheable", "phpClass == cachedClass"}, limit = "1",
                assumptions = "singleContext()", excludeForUncached = true)
        public static Object single(String name, boolean cacheable, PhpClass phpClass,
                                    @Cached("phpClass") PhpClass cachedClass,
                                    @Cached("classConstant(cachedClass, name)") Object value) {
            return value;
        }

        @Specialization(replaces = "single")
        public static Object perCall(String name, boolean cacheable, PhpClass phpClass) {
            return ConstantNodes.classConstant(phpClass, name);
        }

        public static Object classConstant(PhpClass phpClass, String name) {
            return ConstantNodes.classConstant(phpClass, name);
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
            return instantiate.execute(phpClass);
        }

        @Specialization(replaces = "cached")
        public static PhpObject uncached(PhpClass phpClass, @Bind Node node) {
            return InstantiateNode.instantiate(phpClass, node);
        }
    }

    /**
     * The constructor a `new` calls, for its arguments (NEW resolves it first); BY_VALUE for a class without one,
     * whose arguments are evaluated and dropped.
     */
    @Operation
    public static final class ResolveConstructor {
        @Specialization
        public static PhpFunction constructor(PhpObject object) {
            PhpFunction constructor = object.getPhpClass().getConstructor();
            return constructor == null ? MethodSite.BY_VALUE : constructor;
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
                                    @Bind BytecodeNode bytecode) {
            int p = Locals.position(frame, position, bytecode);
            if (p < 0) {
                return false;
            }
            if (hasKey) {
                Locals.writeLong(frame, key, bytecode, p);
            }
            Locals.writeLong(frame, value, bytecode, array.longAt(p));
            position.setLong(bytecode, frame, array.nextPackedPosition(p));
            return true;
        }

        @Specialization(guards = "array.holdsObjects()")
        public static boolean objects(VirtualFrame frame, LocalAccessor position, LocalAccessor value,
                                      LocalAccessor key, boolean hasKey, PhpArray array,
                                      @Bind BytecodeNode bytecode) {
            int p = Locals.position(frame, position, bytecode);
            if (p < 0) {
                return false;
            }
            if (hasKey) {
                Locals.writeLong(frame, key, bytecode, p);
            }
            Locals.write(frame, value, bytecode, PhpRef.deref(array.packedAt(p)));
            position.setLong(bytecode, frame, array.nextPackedPosition(p));
            return true;
        }

        @Specialization(guards = "!array.isPacked()")
        public static boolean hash(VirtualFrame frame, LocalAccessor position, LocalAccessor value,
                                   LocalAccessor key, boolean hasKey, PhpArray array, @Bind BytecodeNode bytecode) {
            int p = Locals.position(frame, position, bytecode);
            if (p < 0) {
                return false;
            }
            if (hasKey) {
                Locals.write(frame, key, bytecode, array.hashKeyAt(p));
            }
            Locals.write(frame, value, bytecode, PhpRef.deref(array.hashValueAt(p)));
            position.setLong(bytecode, frame, array.nextHashPosition(p));
            return true;
        }

        @Specialization
        public static boolean object(VirtualFrame frame, LocalAccessor position, LocalAccessor value,
                                     LocalAccessor key, boolean hasKey, ObjectIteration iteration,
                                     @Bind BytecodeNode bytecode, @Bind Node node) {
            return iteration.next(frame, value, key, hasKey, bytecode, node);
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
        public static Object subject(VirtualFrame frame, LocalAccessor variable, @Bind BytecodeNode bytecode) {
            Object value = Variables.value(Locals.stored(frame, variable, bytecode));
            return value instanceof PhpArray ? Locals.makeRef(frame, variable, bytecode) : value;
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
            Object value = access.readOn(frame, object, site.name);
            return value instanceof PhpArray ? access.makeRefOn(frame, object, site.name) : value;
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
                                 @Bind BytecodeNode bytecode, @Bind Node node) {
            Object value = subject instanceof PhpRef ref ? ref.get() : subject;
            if (value instanceof PhpArray) {
                PhpRef ref = subject instanceof PhpRef held ? held : PhpRef.unbound(value);
                state.setObject(bytecode, frame, RefIteration.start(PhpContext.get(node), ref));
            } else if (value instanceof PhpObject object) {
                state.setObject(bytecode, frame, GeneratorRefIteration.start(node, object));
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
                                    RefIteration iteration, @Bind BytecodeNode bytecode, @Bind Node node) {
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
                Locals.write(frame, key, bytecode, array.keyAt(p));
            }
            return true;
        }

        @Specialization
        public static boolean generator(VirtualFrame frame, LocalAccessor value, LocalAccessor key, boolean hasKey,
                                        GeneratorRefIteration iteration, @Bind BytecodeNode bytecode,
                                        @Bind Node node) {
            if (!iteration.advance(node)) {
                return false;
            }
            Locals.bindRef(frame, value, bytecode, iteration.currentRef(node));
            if (hasKey) {
                Locals.write(frame, key, bytecode, PhpRef.deref(iteration.key(node)));
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
    @Yield(resultOperandIndex = 2)
    public static final class YieldValue {
        @Specialization
        public static Object yield(PhpGenerator generator, Object key, Object value,
                                   @Bind ContinuationRootNode continuation) {
            generator.yielded(key == PhpGenerator.SUSPENDED ? null : key, value);
            generator.suspendAt(continuation);
            return PhpGenerator.SUSPENDED;
        }
    }

    /**
     * `yield from source` (ZEND_YIELD_FROM): starts delegating and suspends; the generator takes the elements, and
     * resumes the body here once they are done, Resumed giving the yield from's result.
     */
    @Yield(resultOperandIndex = 1)
    public static final class YieldFrom {
        @Specialization
        public static Object yieldFrom(PhpGenerator generator, Object source,
                                       @Bind ContinuationRootNode continuation, @Bind Node node) {
            generator.yieldFrom(node, source);
            generator.suspendAt(continuation);
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
                                 @Cached ElementNodes.ElementReadNode read,
                                 @Cached InlinedBranchProfile sharedArray,
                                 @Cached InlinedBranchProfile refArray,
                                 @Cached InlinedBranchProfile createdArray) {
            Object base = variableForWrite(frame, variable, bytecode, node, sharedArray, refArray, createdArray);
            return elementRef(node, base, keys, append, toKey, read);
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
                                 @Bind Node node, @Cached ElementNodes.ArrayKeyNode toKey,
                                 @Cached ElementNodes.ElementReadNode read) {
            return elementRef(node, propertyForWrite(frame, access, object, site.name), keys, append, toKey, read);
        }
    }

    /** The reference of the element keys name from base (made writable), or of a new element (append). */
    private static PhpRef elementRef(Node node, Object base, Object[] keys, boolean append,
                                     ElementNodes.ArrayKeyNode toKey, ElementNodes.ElementReadNode read) {
        int levels = append ? keys.length : keys.length - 1;
        Object container = ElementChain.forWrite(node, base, keys, levels, toKey, read);
        if (!(container instanceof PhpArray array)) {
            throw ElementChain.overloaded();
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
            return access.makeRefOn(frame, object, site.name);
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
                                  @Cached ElementNodes.ElementReadNode read,
                                  @Cached InlinedBranchProfile sharedArray,
                                  @Cached InlinedBranchProfile refArray,
                                  @Cached InlinedBranchProfile createdArray) {
            Object base = variableForWrite(frame, variable, bytecode, node, sharedArray, refArray, createdArray);
            bindElement(node, base, keys, append, ref, toKey, read);
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
                                  @Bind Node node, @Cached ElementNodes.ArrayKeyNode toKey,
                                  @Cached ElementNodes.ElementReadNode read) {
            bindElement(node, propertyForWrite(frame, access, object, site.name), keys, append, ref, toKey, read);
            return ref.get();
        }
    }

    private static void bindElement(Node node, Object base, Object[] keys, boolean append, PhpRef ref,
                                    ElementNodes.ArrayKeyNode toKey, ElementNodes.ElementReadNode read) {
        int levels = append ? keys.length : keys.length - 1;
        Object container = ElementChain.forWrite(node, base, keys, levels, toKey, read);
        if (!(container instanceof PhpArray array)) {
            throw ElementChain.overloaded();
        }
        array.putRef(append ? (Object) array.nextIndex() : toKey.execute(keys[levels]), ref);
    }

    /** `$obj->name = &place` (PropNode.bindRef): the reference, then the object. */
    @Operation
    @ConstantOperand(type = PropSite.class)
    public static final class BindProp {
        @Specialization
        public static Object bind(VirtualFrame frame, PropSite site, PhpRef ref, Object object,
                                  @Cached(value = "site.create()", uncached = "site.uncached()",
                                          neverDefault = true) PropNode access) {
            access.bindRefOn(frame, object, site.name, ref);
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
                                  @Bind BytecodeNode bytecode, @Bind Node node) {
            if (result instanceof PhpRef ref) {
                Locals.bindRef(frame, variable, bytecode, ref);
                return ref.get();
            }
            CallNode.notice(node, CallNode.ASSIGNED_VALUE);
            Locals.write(frame, variable, bytecode, result);
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
            PhpRef ref = PhpContext.get(node).findStaticVariable(function, name);
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
                    PhpContext.get(node).createStaticVariable(function, name, initial));
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
    public static final class Resumed {
        @Specialization
        public static Object resumed(PhpGenerator generator, Object value) {
            return generator.resumed(value);
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
            if (exception instanceof PhpThrowable thrown) {
                return thrown.toThrowable(PhpContext.get(node));
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

    // ---- what the runtime asks of a function's root ----

    @Override
    public boolean describe(Frame frame, PhpArray entry, int options, Backtrace.CallSite site) {
        BytecodeNode bytecode = getBytecodeNode();
        FunctionRootNode.describeCall(name, className, params, frame.getArguments(),
                i -> bytecode.getLocalValue(0, frame, parameterLocals[i]), entry, options);
        return true;
    }

    /** What self:: means in this function's code (FunctionRootNode.getScopeName). */
    public String getScopeName() {
        return scopeName;
    }

    /** Splitting, as for the tree's functions: a copy per caller profile. */
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
