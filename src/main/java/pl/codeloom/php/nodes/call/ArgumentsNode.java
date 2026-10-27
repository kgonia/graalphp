package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.nodes.vars.FileVarNode;
import pl.codeloom.php.nodes.vars.LocalVarNode;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpRef;

import java.util.ArrayList;
import java.util.List;

/**
 * A call's argument list. Arguments are evaluated once the callee is known, because a by-reference parameter
 * takes a reference to the argument place instead of its value.
 */
public final class ArgumentsNode extends Node {
    @Children private final ExprNode[] args;
    @CompilationFinal(dimensions = 1) private final boolean[] spread;
    /**
     * The arguments that are calls (zend_is_call: also of built-ins compiled in place): a by-reference parameter
     * takes the reference a callee returning by reference returned, else the value with a notice.
     */
    @CompilationFinal(dimensions = 1) private final boolean[] calls;
    private final boolean hasSpread;

    public ArgumentsNode(ExprNode[] args, boolean[] spread, boolean[] calls) {
        this.args = args;
        this.spread = spread;
        this.calls = calls;
        boolean any = false;
        for (boolean s : spread) {
            any |= s;
        }
        this.hasSpread = any;
    }

    public int count() {
        return args.length;
    }

    /** The frame arguments for a call: [this, static class, closure, args...]. */
    @ExplodeLoop
    public Object[] build(VirtualFrame frame, PhpFunction callee, Object thisObject, PhpClass staticClass,
                          Object closure) {
        if (hasSpread) {
            return buildWithSpread(frame, callee, thisObject, staticClass, closure);
        }
        Object[] arguments = new Object[PhpFunction.ARG_FIRST + args.length];
        arguments[PhpFunction.ARG_THIS] = thisObject;
        arguments[PhpFunction.ARG_STATIC] = staticClass;
        arguments[PhpFunction.ARG_CLOSURE] = closure;
        for (int i = 0; i < args.length; i++) {
            if (callee.isByRef(i) && args[i] instanceof LValueNode place) {
                arguments[PhpFunction.ARG_FIRST + i] = place.makeRef(frame);
            } else if (calls[i] && callee.isByRef(i)) {
                arguments[PhpFunction.ARG_FIRST + i] = refArgument(frame, args[i]);
            } else {
                arguments[PhpFunction.ARG_FIRST + i] = args[i].execute(frame);
            }
        }
        return arguments;
    }

    /**
     * A call's result passed to a by-reference parameter (SEND_VAR_NO_REF): the reference a function returning by
     * reference returned; a value, which the parameter binds as a new reference, with php-src's notice.
     */
    private Object refArgument(VirtualFrame frame, ExprNode call) {
        Object result = call instanceof CallNode node ? node.executeRef(frame) : call.execute(frame);
        if (!(result instanceof PhpRef)) {
            CallNode.notice(this, CallNode.PASSED_VALUE);
        }
        return result;
    }

    /** Values only (no references): for callables whose target isn't known until the values are. */
    @ExplodeLoop
    public Object[] values(VirtualFrame frame) {
        if (hasSpread) {
            return valuesWithSpread(frame);
        }
        Object[] values = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            values[i] = args[i].execute(frame);
        }
        return values;
    }

    /**
     * With unpacked arguments a parameter's position is known only as the values come: an argument or an unpacked
     * element that lands on a by-reference parameter is passed by reference (ZEND_SEND_UNPACK).
     */
    private Object[] buildWithSpread(VirtualFrame frame, PhpFunction callee, Object thisObject, PhpClass staticClass,
                                     Object closure) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            int position = size(list);
            if (!spread[i]) {
                if (callee.isByRef(position) && args[i] instanceof LValueNode place) {
                    add(list, place.makeRef(frame));
                } else if (calls[i] && callee.isByRef(position)) {
                    add(list, refArgument(frame, args[i]));
                } else {
                    add(list, args[i].execute(frame));
                }
                continue;
            }
            Object value = args[i].execute(frame);
            if (value instanceof PhpArray array && takesRef(callee, position, array.size())) {
                if (args[i] instanceof LocalVarNode || args[i] instanceof FileVarNode) {
                    // a variable's array is separated, and its elements passed by reference become references
                    addSpreadRefs(list, ((LValueNode) args[i]).arrayForWrite(frame), callee, position, true);
                } else {
                    addSpreadRefs(list, array, callee, position, false);
                }
            } else {
                addSpread(list, value);
            }
        }
        Object[] values = toArray(list);
        Object[] arguments = new Object[PhpFunction.ARG_FIRST + values.length];
        arguments[PhpFunction.ARG_THIS] = thisObject;
        arguments[PhpFunction.ARG_STATIC] = staticClass;
        arguments[PhpFunction.ARG_CLOSURE] = closure;
        System.arraycopy(values, 0, arguments, PhpFunction.ARG_FIRST, values.length);
        return arguments;
    }

    private Object[] valuesWithSpread(VirtualFrame frame) {
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            Object value = args[i].execute(frame);
            if (spread[i]) {
                addSpread(values, value);
            } else {
                add(values, value);
            }
        }
        return toArray(values);
    }

    @TruffleBoundary
    private static void add(List<Object> values, Object value) {
        values.add(value);
    }

    @TruffleBoundary
    private static void addSpread(List<Object> values, Object value) {
        if (!(value instanceof PhpArray array)) {
            throw new PhpError("Only arrays and Traversables can be unpacked");
        }
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            values.add(array.valueAt(p));
        }
    }

    @TruffleBoundary
    private static int size(List<Object> values) {
        return values.size();
    }

    /** Whether one of count arguments from position on lands on a by-reference parameter. */
    private static boolean takesRef(PhpFunction callee, int position, int count) {
        for (int i = 0; i < count; i++) {
            if (callee.isByRef(position + i)) {
                return true;
            }
        }
        return false;
    }

    /**
     * An unpacked array's elements, by reference where the parameter is: an element of a variable's array becomes
     * a reference in it; an element of a temporary one is passed as it is, as a by-reference argument that is no
     * place.
     */
    @TruffleBoundary
    private static void addSpreadRefs(List<Object> values, PhpArray array, PhpFunction callee, int position,
                                      boolean variable) {
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            if (!callee.isByRef(position++)) {
                values.add(array.valueAt(p));
            } else if (variable) {
                values.add(array.refAtPosition(p));
            } else {
                values.add(array.rawAt(p)); // a reference stays one; a value is bound as a by-reference argument
            }
        }
    }

    @TruffleBoundary
    private static Object[] toArray(List<Object> values) {
        return values.toArray();
    }

    /** Frame arguments from already-evaluated values. */
    public static Object[] frameArguments(Object thisObject, PhpClass staticClass, Object closure, Object[] values) {
        Object[] arguments = new Object[PhpFunction.ARG_FIRST + values.length];
        arguments[PhpFunction.ARG_THIS] = thisObject;
        arguments[PhpFunction.ARG_STATIC] = staticClass;
        arguments[PhpFunction.ARG_CLOSURE] = closure;
        System.arraycopy(values, 0, arguments, PhpFunction.ARG_FIRST, values.length);
        return arguments;
    }
}
