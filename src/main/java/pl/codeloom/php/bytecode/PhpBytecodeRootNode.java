package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.BytecodeNode;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.ConstantOperand;
import com.oracle.truffle.api.bytecode.EpilogExceptional;
import com.oracle.truffle.api.bytecode.EpilogReturn;
import com.oracle.truffle.api.bytecode.ForceQuickening;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.LocalAccessor;
import com.oracle.truffle.api.bytecode.Operation;
import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.bytecode.ShortCircuitOperation;
import com.oracle.truffle.api.bytecode.Variadic;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.exception.AbstractTruffleException;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
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
import pl.codeloom.php.nodes.PhpTypes;
import pl.codeloom.php.nodes.ToStrNode;
import pl.codeloom.php.nodes.call.CallNode;
import pl.codeloom.php.nodes.call.FunctionRootNode;
import pl.codeloom.php.nodes.expr.AssignNodes;
import pl.codeloom.php.nodes.expr.Comparisons;
import pl.codeloom.php.nodes.intrinsics.BuiltinNodes;
import pl.codeloom.php.nodes.stmt.BasicStmts;
import pl.codeloom.php.nodes.stmt.ForeachNode;
import pl.codeloom.php.nodes.vars.DimNode;
import pl.codeloom.php.nodes.vars.ElementNodes;
import pl.codeloom.php.nodes.vars.PropNode;
import pl.codeloom.php.nodes.vars.Variables;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.Operators;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
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
 */
@GenerateBytecode(languageClass = PhpLanguage.class, enableUncachedInterpreter = true, enableYield = true,
        enableBlockScoping = false, boxingEliminationTypes = {long.class, double.class},
        defaultUncachedThreshold = "UNCACHED_THRESHOLD")
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
@ShortCircuitOperation(name = "And", operator = ShortCircuitOperation.Operator.AND_RETURN_CONVERTED,
        booleanConverter = Proxies.ToBool.class)
@ShortCircuitOperation(name = "Or", operator = ShortCircuitOperation.Operator.OR_RETURN_CONVERTED,
        booleanConverter = Proxies.ToBool.class)
@ShortCircuitOperation(name = "Elvis", operator = ShortCircuitOperation.Operator.OR_RETURN_VALUE,
        booleanConverter = Proxies.ToBool.class)
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
            if (!state.isCleared(bytecodeNode, frame)
                    && state.getObject(bytecodeNode, frame) instanceof PhpArray held) {
                held.removeHolder();
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

    @Operation
    @ConstantOperand(type = LiteralSite.class)
    public static final class LiteralEnd {
        @Specialization
        public static PhpArray end(LiteralSite site, PhpArray array) {
            site.built(array);
            return array;
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

    // ---- statements and logic ----

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
