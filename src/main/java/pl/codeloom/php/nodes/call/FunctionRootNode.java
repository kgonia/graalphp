package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.PhpType;
import pl.codeloom.php.runtime.SourceLocation;

import java.util.function.IntFunction;

/**
 * A user function's body. Binds parameters (with coercive scalar typing) and closure captures to frame slots,
 * runs the body, and on exit releases the arrays its locals held, so copy-on-write sees exact holder counts for
 * locals.
 */
public final class FunctionRootNode {
    /** ZEND_VERIFY_RETURN_TYPE for the value function returns, in the mode of its file (path). */
    public static Object checkReturn(String function, PhpType type, Object value, PhpClass calledClass,
                                     String path, Node site) {
        Object accepted = type.fast(value);
        return accepted != null ? accepted : returnSlow(function, type, value, calledClass, path, site);
    }

    @TruffleBoundary
    public static Object returnSlow(String function, PhpType type, Object value, PhpClass calledClass,
                                    String path, Node site) {
        Object accepted = type.slow(value, PhpContext.get(null).getLanguage().isStrict(path), calledClass, site);
        if (accepted != null) {
            return accepted;
        }
        throw new PhpError("TypeError", returnName(function) + "(): Return value must be of type "
                + type.text(calledClass) + ", " + PhpType.valueName(value) + " returned");
    }

    /** zend_verify_return_error's "class::function": an anonymous class's name up to its NUL. */
    private static String returnName(String function) {
        int nul = function.indexOf('\0');
        return nul < 0 ? function : function.substring(0, nul) + function.substring(function.lastIndexOf("::"));
    }

    /** The end of a function with a return type: a never-returning one must not get there either. */
    @TruffleBoundary
    public static PhpError noneReturned(String function, PhpType type, PhpClass calledClass) {
        if (type.has(PhpType.NEVER)) {
            return new PhpError("TypeError", returnName(function) + "(): never-returning "
                    + (function.contains("::") ? "method" : "function") + " must not implicitly return");
        }
        return new PhpError("TypeError", returnName(function) + "(): Return value must be of type "
                + type.text(calledClass) + ", none returned");
    }

    /**
     * zend_verify_recv_arg_type for what fast checks leave: in the mode of the calling code (a built-in's
     * callback in coercive mode), else the TypeError naming the call's place in user code.
     */
    @TruffleBoundary
    public static Object argumentSlow(String function, PhpFunction.Param param, int index, Object value,
                                      PhpClass calledClass, Node site) {
        SourceLocation caller = null;
        boolean strict = false;
        PhpLanguage language = PhpContext.get(null).getLanguage();
        if (!language.noStrictFiles().isValid()) {
            caller = Backtrace.userCaller();
            strict = caller != null && language.isStrict(caller.file());
        }
        Object accepted = param.declared().slow(value, strict, calledClass, site);
        if (accepted != null) {
            return accepted;
        }
        if (caller == null) {
            caller = Backtrace.userCaller();
        }
        // a variadic parameter's arguments go unnamed (get_function_arg_name)
        // the active function's name as %s writes it: an anonymous class's method up to its NUL
        throw new PhpError("TypeError", function.split("\0", 2)[0] + "(): Argument #" + (index + 1)
                + (param.variadic() ? "" : " ($" + param.name() + ")") + " must be of type "
                + param.declared().text(calledClass) + ", " + PhpType.valueName(value)
                + " given" + (caller == null ? "" : ", called in " + caller.file() + " on line " + caller.line()));
    }

    /** A typed parameter's value: the reference's content for a by-reference one, converted in it. */
    public static Object checkArgument(String function, PhpFunction.Param param, int index, Object value,
                                       PhpClass calledClass, Node site) {
        if (value instanceof PhpRef ref && !param.byRef()) {
            value = ref.get();
        } else if (value instanceof PhpRef ref) {
            Object content = ref.get();
            Object accepted = param.declared().fast(content);
            if (accepted != content) {
                ref.set(accepted != null ? accepted : argumentSlow(function, param, index, content, calledClass, site));
            }
            return ref;
        }
        Object accepted = param.declared().fast(value);
        return accepted != null ? accepted : argumentSlow(function, param, index, value, calledClass, site);
    }

    /**
     * zend_missing_arg_error for a call of the function name that passed given arguments: where user code made
     * the call, and "exactly" when every parameter is required. The required ones are those up to the last
     * parameter without a default (required_num_args), the variadic one isn't counted (num_args).
     */
    @TruffleBoundary
    public static PhpError tooFewArguments(String name, PhpFunction.Param[] params, int given) {
        int required = 0;
        int count = 0;
        for (int i = 0; i < params.length; i++) {
            if (params[i].variadic()) {
                continue;
            }
            count++;
            if (params[i].defaultValue() == null) {
                required = i + 1;
            }
        }
        SourceLocation caller = pl.codeloom.php.runtime.Backtrace.userCaller();
        String where = caller == null ? "" : " in " + caller.file() + " on line " + caller.line();
        return new PhpError("ArgumentCountError", "Too few arguments to function " + name + "(), " + given
                + " passed" + where + " and " + (required == count ? "exactly" : "at least") + " " + required
                + " expected");
    }

    /** Not a boundary: inlined into the caller, the array can be escape-analyzed away (apply_filters' ...$args). */
    public static PhpArray collectVariadic(Object[] arguments, int from, int given, boolean byRef) {
        PhpArray rest = PhpArray.ofCapacity(given - from);
        for (int i = from; i < given; i++) {
            Object value = arguments[PhpFunction.ARG_FIRST + i];
            if (byRef) {
                // &...$rest: each element is the argument's reference
                rest.putRef((long) (i - from), value instanceof PhpRef ref ? ref : new PhpRef(value));
            } else {
                rest.append(PhpRef.deref(value));
            }
        }
        rest.addHolder();
        return rest;
    }


    /**
     * describeCall for any function's frame: its display name, the class it is declared in (or null), its
     * parameters, the frame's arguments and the current value of the variable of parameter i (null when unset).
     */
    public static void describeCall(String name, String className, PhpFunction.Param[] params, Object[] arguments,
                                    IntFunction<Object> parameter, PhpArray entry, int options) {
        String function = className != null && name.startsWith(className + "::")
                ? name.substring(className.length() + 2) : name;
        String scope = className;
        PhpObject self = null;
        PhpArray args = new PhpArray();
        if (arguments[PhpFunction.ARG_CLOSURE] instanceof PhpClosure closure) {
            scope = closure.getScope() == null ? null : closure.getScope().getName();
        }
        if (arguments[PhpFunction.ARG_THIS] instanceof PhpObject object) {
            self = object;
            if (scope == null) {
                scope = object.getPhpClass().getName();
            }
        }
        if ((options & Backtrace.IGNORE_ARGS) == 0) {
            int declared = params.length > 0 && params[params.length - 1].variadic() ? params.length - 1
                    : params.length;
            for (int i = 0; i < arguments.length - PhpFunction.ARG_FIRST; i++) {
                Object value = i < declared ? parameter.apply(i)
                        : arguments[PhpFunction.ARG_FIRST + i];
                value = PhpRef.deref(value);
                args.append(value == null ? PhpNull.NULL : value);
            }
        }
        Backtrace.describeCall(entry, function, scope, self, options, args);
    }

}
