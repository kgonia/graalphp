package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.api.RootCallTarget;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.nodes.ControlFlow;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.StmtNode;
import pl.codeloom.php.nodes.vars.FrameSlots;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpGenerator;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.SourceLocation;

/**
 * A user function's body. Binds parameters (with coercive scalar typing) and closure captures to frame slots,
 * runs the body, and on exit releases the arrays its locals held, so copy-on-write sees exact holder counts for
 * locals.
 */
public final class FunctionRootNode extends RootNode implements SourceLocation.Code {
    private final String name;
    private final String path;
    @CompilationFinal(dimensions = 1) private final PhpFunction.Param[] params;
    @CompilationFinal(dimensions = 1) private final int[] paramSlots;
    @Children private final ExprNode[] defaults;
    @CompilationFinal(dimensions = 1) private final int[] captureSlots;
    @CompilationFinal(dimensions = 1) private final boolean[] captureByRef;
    private final int localCount;
    private final char returnCoerce;
    private final boolean returnNullable;
    @Child private StmtNode body;
    /** A generator function's body (run by the Generator), or null; its frame slot for the Generator's state. */
    @CompilationFinal private RootCallTarget generatorBody;
    @CompilationFinal private int generatorSlot;

    private final SourceSection sourceSection;

    public FunctionRootNode(PhpLanguage language, FrameDescriptor frame, String name, PhpFunction.Param[] params,
                            int[] paramSlots, ExprNode[] defaults, int[] captureSlots, boolean[] captureByRef,
                            char returnCoerce, boolean returnNullable, StmtNode body, String path,
                            SourceSection sourceSection) {
        super(language, frame);
        this.sourceSection = sourceSection;
        this.name = name;
        this.path = path;
        this.params = params;
        this.paramSlots = paramSlots;
        this.defaults = defaults;
        this.captureSlots = captureSlots;
        this.captureByRef = captureByRef;
        this.localCount = frame.getNumberOfSlots();
        this.returnCoerce = returnCoerce;
        this.returnNullable = returnNullable;
        this.body = body;
    }

    /** Makes this a generator function: a call binds the parameters and returns a Generator running body. */
    public void setGeneratorBody(RootCallTarget body, int slot) {
        this.generatorBody = body;
        this.generatorSlot = slot;
    }

    /**
     * Binds the arguments, runs the body and releases the locals however the call ends (a return, the end of the
     * body, an exception, also one thrown while binding). A generator function's frame outlives the call: the
     * Generator runs the body on it later, so its locals aren't released here.
     */
    @Override
    public Object execute(VirtualFrame frame) {
        Object[] arguments = frame.getArguments();
        if (generatorBody != null) {
            bindArguments(frame, arguments);
            PhpGenerator generator = new PhpGenerator(frame.materialize(), generatorBody);
            frame.setObject(generatorSlot, generator);
            return newGenerator(PhpContext.get(this), generator);
        }
        Object result = PhpNull.NULL;
        try {
            bindArguments(frame, arguments);
            body.execute(frame);
        } catch (ControlFlow.Return ret) {
            result = ret.value;
        } finally {
            releaseLocals(frame);
        }
        return returnCoerce == 0 ? result : coerce(result, returnCoerce, returnNullable, "return value");
    }

    private void bindArguments(VirtualFrame frame, Object[] arguments) {
        bindParameters(frame, arguments);
        if (captureSlots.length > 0) {
            bindCaptures(frame, (PhpClosure) arguments[PhpFunction.ARG_CLOSURE]);
        }
    }

    @ExplodeLoop
    private void bindParameters(VirtualFrame frame, Object[] arguments) {
        int given = arguments.length - PhpFunction.ARG_FIRST;
        for (int i = 0; i < params.length; i++) {
            PhpFunction.Param param = params[i];
            int slot = paramSlots[i];
            if (param.variadic()) {
                FrameSlots.setObject(frame, slot, collectVariadic(arguments, i, given));
                continue;
            }
            Object value;
            if (i < given) {
                value = arguments[PhpFunction.ARG_FIRST + i];
            } else if (defaults[i] != null) {
                value = defaults[i].execute(frame);
            } else {
                throw tooFewArguments(given);
            }
            if (param.byRef()) {
                PhpRef ref = value instanceof PhpRef passed ? passed : new PhpRef(value);
                ref.bind();
                FrameSlots.setObject(frame, slot, ref);
                continue;
            }
            value = PhpRef.deref(value);
            if (param.coerce() != 0) {
                value = coerce(value, param.coerce(), param.nullable(), param.name());
            }
            if (value instanceof PhpArray array) {
                array.addHolder();
            }
            FrameSlots.set(frame, slot, value);
        }
    }

    @TruffleBoundary
    private static PhpObject newGenerator(PhpContext context, PhpGenerator generator) {
        PhpObject object = context.getClass("Generator").instantiate();
        object.setInternal(generator);
        return object;
    }

    @TruffleBoundary
    private PhpError tooFewArguments(int given) {
        int required = 0;
        for (PhpFunction.Param param : params) {
            if (param.defaultValue() == null && !param.variadic()) {
                required++;
            }
        }
        return new PhpError("ArgumentCountError", "Too few arguments to function " + name + "(), " + given
                + " passed and at least " + required + " expected");
    }

    /** Not a boundary: inlined into the caller, the array can be escape-analyzed away (apply_filters' ...$args). */
    private static PhpArray collectVariadic(Object[] arguments, int from, int given) {
        PhpArray rest = PhpArray.ofCapacity(given - from);
        for (int i = from; i < given; i++) {
            rest.append(PhpRef.deref(arguments[PhpFunction.ARG_FIRST + i]));
        }
        rest.addHolder();
        return rest;
    }

    @ExplodeLoop
    private void bindCaptures(VirtualFrame frame, PhpClosure closure) {
        for (int i = 0; i < captureSlots.length; i++) {
            Object captured = closure.getCaptured(i);
            if (!captureByRef[i] && captured instanceof PhpArray array) {
                array.addHolder();
            } else if (captured instanceof PhpRef ref) {
                ref.bind();
            }
            FrameSlots.setObject(frame, captureSlots[i], captured);
        }
    }

    @ExplodeLoop
    private void releaseLocals(VirtualFrame frame) {
        for (int slot = 0; slot < localCount; slot++) {
            if (frame.isObject(slot)) {
                Object value = frame.getObject(slot);
                if (value instanceof PhpArray array) {
                    array.removeHolder();
                } else if (value instanceof PhpRef ref) {
                    ref.unbind();
                }
            }
        }
    }

    /**
     * Coercive typing mode (no strict_types): scalars convert to the declared scalar type where PHP would.
     * Values PHP would reject are passed through unchanged; this runtime doesn't raise TypeErrors.
     */
    static Object coerce(Object value, char type, boolean nullable, String what) {
        boolean scalarType = type == 's' || type == 'i' || type == 'f' || type == 'b';
        if (!scalarType || value == PhpNull.NULL || type == 's' && value instanceof TruffleString
                || type == 'i' && value instanceof Long || type == 'f' && value instanceof Double
                || type == 'b' && value instanceof Boolean) {
            return value;
        }
        return convert(value, type);
    }

    /** Out of line: the conversions, inlined for every typed parameter, were a large part of compiled callers. */
    @TruffleBoundary
    private static Object convert(Object value, char type) {
        switch (type) {
            case 's':
                if (value instanceof Long || value instanceof Double || value instanceof Boolean) {
                    return Convert.toStr(value);
                }
                return value;
            case 'i':
                if (value instanceof Double d && d == Math.rint(d) && Double.isFinite(d)) {
                    return (long) (double) d;
                }
                if (value instanceof Boolean b) {
                    return b ? 1L : 0L;
                }
                if (value instanceof TruffleString s) {
                    Object number = Convert.numericValue(s);
                    if (number instanceof Long) {
                        return number;
                    }
                    if (number instanceof Double d && d == Math.rint(d)) {
                        return (long) (double) d;
                    }
                }
                return value;
            case 'f':
                if (value instanceof Long l) {
                    return (double) l;
                }
                if (value instanceof Boolean b) {
                    return b ? 1.0 : 0.0;
                }
                if (value instanceof TruffleString s) {
                    Object number = Convert.numericValue(s);
                    if (number != null) {
                        return ((Number) number).doubleValue();
                    }
                }
                return value;
            case 'b':
                if (value instanceof Long || value instanceof Double || value instanceof TruffleString) {
                    return Convert.toBool(value);
                }
                return value;
            default:
                return value;
        }
    }

    /** The lowercase name of the class the function is declared in (a method's, a trait's user), or null. */
    private String scopeName;

    public void setScopeName(String scopeName) {
        this.scopeName = scopeName;
    }

    /** What self:: means in this function's code: callables named "self::m" or "parent::m" resolve with it. */
    public String getScopeName() {
        return scopeName;
    }

    /** Splitting: a function called with different types from different sites gets a copy per caller profile. */
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
    public String getPath() {
        return path;
    }
}
