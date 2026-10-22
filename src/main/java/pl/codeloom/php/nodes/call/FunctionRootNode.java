package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.nodes.ControlFlow;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.StmtNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpRef;

/**
 * A user function's body. Binds parameters (with coercive scalar typing) and closure captures to frame slots,
 * runs the body, and on exit releases the arrays its locals held, so copy-on-write sees exact holder counts for
 * locals.
 */
public final class FunctionRootNode extends RootNode {
    private final String name;
    private final PhpFunction.Param[] params;
    private final int[] paramSlots;
    @Children private final ExprNode[] defaults;
    private final int[] captureSlots;
    private final boolean[] captureByRef;
    private final int localCount;
    private final char returnCoerce;
    private final boolean returnNullable;
    @Child private StmtNode body;

    private final SourceSection sourceSection;

    public FunctionRootNode(PhpLanguage language, FrameDescriptor frame, String name, PhpFunction.Param[] params,
                            int[] paramSlots, ExprNode[] defaults, int[] captureSlots, boolean[] captureByRef,
                            char returnCoerce, boolean returnNullable, StmtNode body, SourceSection sourceSection) {
        super(language, frame);
        this.sourceSection = sourceSection;
        this.name = name;
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

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] arguments = frame.getArguments();
        bindParameters(frame, arguments);
        if (captureSlots.length > 0) {
            bindCaptures(frame, (PhpClosure) arguments[PhpFunction.ARG_CLOSURE]);
        }
        Object result = PhpNull.NULL;
        try {
            body.execute(frame);
        } catch (ControlFlow.Return ret) {
            result = ret.value;
        }
        releaseLocals(frame);
        return returnCoerce == 0 ? result : coerce(result, returnCoerce, returnNullable, "return value");
    }

    @ExplodeLoop
    private void bindParameters(VirtualFrame frame, Object[] arguments) {
        int given = arguments.length - PhpFunction.ARG_FIRST;
        for (int i = 0; i < params.length; i++) {
            PhpFunction.Param param = params[i];
            int slot = paramSlots[i];
            if (param.variadic()) {
                frame.setObject(slot, collectVariadic(arguments, i, given));
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
                frame.setObject(slot, value instanceof PhpRef ref ? ref : new PhpRef(value));
                continue;
            }
            value = PhpRef.deref(value);
            if (param.coerce() != 0) {
                value = coerce(value, param.coerce(), param.nullable(), param.name());
            }
            if (value instanceof Long l) {
                frame.setLong(slot, l);
            } else if (value instanceof Double d) {
                frame.setDouble(slot, d);
            } else {
                if (value instanceof PhpArray array) {
                    array.addHolder();
                }
                frame.setObject(slot, value);
            }
        }
    }

    @TruffleBoundary
    private PhpError tooFewArguments(int given) {
        int required = 0;
        for (PhpFunction.Param param : params) {
            if (param.defaultValue() == null && !param.variadic()) {
                required++;
            }
        }
        return new PhpError("ArgumentCountError: Too few arguments to function " + name + "(), " + given
                + " passed and at least " + required + " expected");
    }

    @TruffleBoundary
    private static PhpArray collectVariadic(Object[] arguments, int from, int given) {
        PhpArray rest = new PhpArray();
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
            }
            frame.setObject(captureSlots[i], captured);
        }
    }

    @ExplodeLoop
    private void releaseLocals(VirtualFrame frame) {
        for (int slot = 0; slot < localCount; slot++) {
            if (frame.isObject(slot) && frame.getObject(slot) instanceof PhpArray array) {
                array.removeHolder();
            }
        }
    }

    /**
     * Coercive typing mode (no strict_types): scalars convert to the declared scalar type where PHP would.
     * Values PHP would reject are passed through unchanged; this runtime doesn't raise TypeErrors.
     */
    static Object coerce(Object value, char type, boolean nullable, String what) {
        if (value == PhpNull.NULL) {
            return value;
        }
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
}
