package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

/**
 * Calls a callable value with already-evaluated arguments: `$f(...)`, call_user_func(_array). Inline caches
 * per callable shape (closure function, function name, [object class, method name]) make the hot cases direct
 * calls; WP_Hook's dispatch of `$the_['function']` goes through here.
 */
public abstract class CallableDispatchNode extends Node {
    static final int LIMIT = 6;

    public abstract Object execute(Object callable, Object[] values);

    @Specialization(guards = "callable.getFunction() == cachedFunction", limit = "LIMIT")
    protected Object closure(PhpClosure callable, Object[] values,
                             @Cached("callable.getFunction()") PhpFunction cachedFunction,
                             @Cached("create(cachedFunction.getCallTarget())") DirectCallNode call) {
        PhpObject self = callable.getBoundThis();
        PhpClass scope = self != null ? self.getPhpClass() : callable.getScope();
        return PhpRef.deref(call.call(ArgumentsNode.frameArguments(self, scope, callable, values)));
    }

    @Specialization(guards = "equal.execute(callable, cachedName, ENC)", limit = "LIMIT")
    protected Object named(TruffleString callable, Object[] values,
                           @Cached("callable") TruffleString cachedName,
                           @Cached TruffleString.EqualNode equal,
                           @Cached("resolve(callable)") Callables.Target target,
                           @Cached("create(target.function().getCallTarget())") DirectCallNode call) {
        return PhpRef.deref(call.call(target.frameArguments(values)));
    }

    @Specialization(guards = {"isObjectMethod(callable)", "classOf(callable) == cachedClass",
            "equal.execute(methodName(callable), cachedMethod, ENC)"}, limit = "LIMIT")
    protected Object objectMethod(PhpArray callable, Object[] values,
                                  @Cached("classOf(callable)") PhpClass cachedClass,
                                  @Cached("methodName(callable)") TruffleString cachedMethod,
                                  @Cached TruffleString.EqualNode equal,
                                  @Cached("resolve(callable)") Callables.Target target,
                                  @Cached("create(target.function().getCallTarget())") DirectCallNode call) {
        Object self = target.function().isStatic() ? null : callable.get(0L);
        return PhpRef.deref(call.call(ArgumentsNode.frameArguments(self, cachedClass, null, values)));
    }

    @Specialization(replaces = {"closure", "named", "objectMethod"})
    protected Object generic(Object callable, Object[] values,
                             @Cached IndirectCallNode call) {
        Callables.Target target = Callables.resolveOrThrow(PhpContext.get(this), callable);
        return PhpRef.deref(call.call(target.function().getCallTarget(), target.frameArguments(values)));
    }

    protected Callables.Target resolve(Object callable) {
        return Callables.resolveOrThrow(PhpContext.get(this), callable);
    }

    protected static final TruffleString.Encoding ENC = Str.ENC;

    protected static boolean isObjectMethod(PhpArray callable) {
        return callable.size() == 2 && callable.get(0L) instanceof PhpObject
                && callable.get(1L) instanceof TruffleString;
    }

    protected static PhpClass classOf(PhpArray callable) {
        return ((PhpObject) callable.get(0L)).getPhpClass();
    }

    protected static TruffleString methodName(PhpArray callable) {
        return (TruffleString) callable.get(1L);
    }
}
