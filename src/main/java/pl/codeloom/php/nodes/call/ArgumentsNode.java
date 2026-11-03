package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.Args;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.nodes.stmt.ForeachNode;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.nodes.expr.AssignNodes;
import pl.codeloom.php.nodes.vars.FileVarNode;
import pl.codeloom.php.nodes.vars.LocalVarNode;
import pl.codeloom.php.nodes.vars.ThisNode;
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
     * The arguments that are calls (zend_is_call: not of built-ins compiled in place), `new`, include and yield
     * (IS_VAR results): a by-reference parameter takes the reference a callee returning by reference returned, else
     * the value with a notice.
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
            arguments[PhpFunction.ARG_FIRST + i] = send(frame, i, callee, i);
        }
        return arguments;
    }

    /**
     * Argument i at a parameter position: for a by-reference parameter a place's reference, a call's
     * (SEND_VAR_NO_REF), the reference `$a = &$b` assigned, $this as a value; any other value is php-src's Error
     * (SEND_VAL_EX), once evaluated.
     */
    private Object send(VirtualFrame frame, int i, PhpFunction callee, int position) {
        if (!callee.isByRef(position)) {
            return args[i].execute(frame);
        }
        if (args[i] instanceof LValueNode place) {
            return place.makeRef(frame);
        }
        if (calls[i]) {
            return callee.isPreferRef() ? args[i].execute(frame) : refArgument(frame, args[i]);
        }
        if (args[i] instanceof AssignNodes.RefAssignNode assign) {
            return assign.executeRef(frame);
        }
        Object value = args[i].execute(frame);
        // ponytail: `$x = &f()` as an argument is sent as its value, unchecked
        if (!callee.isPreferRef() && !(args[i] instanceof ThisNode)
                && !(args[i] instanceof AssignNodes.CallRefAssignNode)) {
            throw CallNode.cannotPassByRef(callee, position);
        }
        return value;
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
     * element that lands on a by-reference parameter is passed by reference (ZEND_SEND_UNPACK). The loop over the
     * arguments is exploded as build's: each argument node is a constant, and the frame never leaves the
     * compilation (an unexploded loop's virtual execute calls let it escape, and the compilation bails out).
     */
    @ExplodeLoop
    private Object[] buildWithSpread(VirtualFrame frame, PhpFunction callee, Object thisObject, PhpClass staticClass,
                                     Object closure) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            int position = size(list);
            if (!spread[i]) {
                add(list, send(frame, i, callee, position));
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
                addSpread(list, value, callee, this);
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

    @ExplodeLoop
    private Object[] valuesWithSpread(VirtualFrame frame) {
        List<Object> values = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            Object value = args[i].execute(frame);
            if (spread[i]) {
                addSpread(values, value, null, this);
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

    /**
     * `...value` (ZEND_SEND_UNPACK) by value: an array's elements, or a Traversable's, each as the iterator gives
     * it, by a new reference with php-src's warning where it lands on a by-reference parameter of callee (null: not
     * known yet). ponytail: string keys, php-src's named arguments, aren't taken by name (issue 017).
     */
    @TruffleBoundary
    public static void addSpread(List<Object> values, Object value, PhpFunction callee, Node site) {
        if (value instanceof PhpArray array) {
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                values.add(array.valueAt(p));
            }
            return;
        }
        if (!(value instanceof PhpObject object) || !object.getPhpClass().isSubclassOf("traversable")) {
            throw new PhpError("TypeError", "Only arrays and Traversables can be unpacked, " + Args.valueName(value)
                    + " given");
        }
        ForeachNode.traverse(site, object, (key, element) -> {
            if (!(key instanceof Long) && !(key instanceof TruffleString)) {
                throw new PhpError("Keys must be of type int|string during argument unpacking");
            }
            if (callee != null && callee.isByRef(values.size())) {
                ErrorFunctions.raise(PhpContext.get(site), ErrorFunctions.E_WARNING, Str.of("Cannot pass "
                        + "by-reference argument " + (values.size() + 1) + " of " + callee.displayName()
                        + "() by unpacking a Traversable, passing by-value instead"), SourceLocation.of(site));
                values.add(new PhpRef(element));
            } else {
                values.add(element);
            }
            return true;
        });
    }

    @TruffleBoundary
    private static int size(List<Object> values) {
        return values.size();
    }

    /** Whether one of count arguments from position on lands on a by-reference parameter. */
    public static boolean takesRef(PhpFunction callee, int position, int count) {
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
    public static void addSpreadRefs(List<Object> values, PhpArray array, PhpFunction callee, int position,
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
