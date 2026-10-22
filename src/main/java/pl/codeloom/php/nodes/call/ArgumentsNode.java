package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;

import java.util.ArrayList;
import java.util.List;

/**
 * A call's argument list. Arguments are evaluated once the callee is known, because a by-reference parameter
 * takes a reference to the argument place instead of its value.
 */
public final class ArgumentsNode extends Node {
    @Children private final ExprNode[] args;
    private final boolean[] spread;
    private final boolean hasSpread;

    public ArgumentsNode(ExprNode[] args, boolean[] spread) {
        this.args = args;
        this.spread = spread;
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
            } else {
                arguments[PhpFunction.ARG_FIRST + i] = args[i].execute(frame);
            }
        }
        return arguments;
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

    private Object[] buildWithSpread(VirtualFrame frame, PhpFunction callee, Object thisObject, PhpClass staticClass,
                                     Object closure) {
        Object[] values = valuesWithSpread(frame);
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
