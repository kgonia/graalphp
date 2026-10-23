package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.ClassKey;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

/**
 * Calls a callable value with already-evaluated arguments: `$f(...)`, call_user_func(_array). Inline caches
 * per callable shape (closure function, function name, [object class, method name], [class name, method name])
 * make the hot cases direct calls; WP_Hook's dispatch of `$the_['function']` goes through here.
 */
public abstract class CallableDispatchNode extends Node {
    static final int LIMIT = 6;

    public static CallableDispatchNode create() {
        return CallableDispatchNodeGen.create();
    }

    public abstract Object execute(Object callable, Object[] values);

    @Specialization(guards = "callable.getFunction() == cachedFunction", limit = "LIMIT")
    protected Object closure(PhpClosure callable, Object[] values,
                             @Cached("callable.getFunction()") PhpFunction cachedFunction,
                             @Cached("create(cachedFunction.getCallTarget())") DirectCallNode call) {
        PhpObject self = callable.getBoundThis();
        PhpClass scope = self != null ? self.getPhpClass() : callable.getScope();
        return PhpRef.deref(call.call(ArgumentsNode.frameArguments(self, scope, callable, values)));
    }

    /**
     * A function name. The name is resolved once; each call checks that the calling context declares that same
     * (language-level) function under it. "Cls::m" strings take the generic path.
     */
    @Specialization(guards = {"equal.execute(callable, cachedName, ENC)", "functionId >= 0",
            "functionAt(functionId) == target.function()"}, limit = "LIMIT")
    protected Object named(TruffleString callable, Object[] values,
                           @Cached("callable") TruffleString cachedName,
                           @Cached TruffleString.EqualNode equal,
                           @Cached("resolve(callable)") Callables.Target target,
                           @Cached("functionId(target)") int functionId,
                           @Cached("create(target.function().getCallTarget())") DirectCallNode call) {
        return PhpRef.deref(call.call(target.frameArguments(values)));
    }

    /** `[$object, 'method']`, keyed on the object's class key: the same entry serves every context. */
    @Specialization(guards = {"isObjectMethod(callable)", "classOf(callable).getKey() == cachedKey",
            "equal.execute(methodName(callable), cachedMethod, ENC)"}, limit = "LIMIT")
    protected Object objectMethod(PhpArray callable, Object[] values,
                                  @Cached("classOf(callable).getKey()") ClassKey cachedKey,
                                  @Cached("methodName(callable)") TruffleString cachedMethod,
                                  @Cached TruffleString.EqualNode equal,
                                  @Cached("resolve(callable)") Callables.Target target,
                                  @Cached("create(target.function().getCallTarget())") DirectCallNode call) {
        Object self = target.function().isStatic() ? null : callable.get(0L);
        return PhpRef.deref(call.call(ArgumentsNode.frameArguments(self, classOf(callable), null, values)));
    }

    /**
     * `['Cls', 'method']`: the class is the calling context's, looked up by name id and checked by key. A
     * 'parent::method' name resolves to another class and takes the generic path.
     */
    @Specialization(guards = {"isClassMethod(callable)", "equalClass.execute(className(callable), cachedClassName, ENC)",
            "equalMethod.execute(methodName(callable), cachedMethod, ENC)", "classId >= 0",
            "keyAt(classId) == cachedKey"}, limit = "LIMIT")
    protected Object classMethod(PhpArray callable, Object[] values,
                                 @Cached("className(callable)") TruffleString cachedClassName,
                                 @Cached("methodName(callable)") TruffleString cachedMethod,
                                 @Cached TruffleString.EqualNode equalClass,
                                 @Cached TruffleString.EqualNode equalMethod,
                                 @Cached("resolve(callable)") Callables.Target target,
                                 @Cached("classId(cachedClassName, cachedMethod)") int classId,
                                 @Cached("keyAt(classId)") ClassKey cachedKey,
                                 @Cached("create(target.function().getCallTarget())") DirectCallNode call) {
        return PhpRef.deref(call.call(ArgumentsNode.frameArguments(target.thisObject(),
                PhpContext.get(this).classAt(classId), null, values)));
    }

    @Specialization(replaces = {"closure", "named", "objectMethod", "classMethod"})
    protected Object generic(Object callable, Object[] values,
                             @Cached IndirectCallNode call) {
        Callables.Target target = Callables.resolveOrThrow(PhpContext.get(this), callable);
        return PhpRef.deref(call.call(target.function().getCallTarget(), target.frameArguments(values)));
    }

    protected Callables.Target resolve(Object callable) {
        return Callables.resolveOrThrow(PhpContext.get(this), callable);
    }

    /** The function name id of a plain function target, or -1 for a method or closure. */
    protected int functionId(Callables.Target target) {
        if (target.thisObject() != null || target.staticClass() != null || target.closure() != null) {
            return -1;
        }
        return PhpLanguage.get(this).functionId(target.function().getName().toLowerCase());
    }

    protected PhpFunction functionAt(int id) {
        return PhpContext.get(this).functionAt(id);
    }

    /** The class name's id, or -1 when the method name qualifies another class ('parent::m'). */
    protected int classId(TruffleString className, TruffleString methodName) {
        if (Str.java(methodName).contains("::")) {
            return -1;
        }
        return PhpLanguage.get(this).classId(Callables.className(className));
    }

    /** The key of the class the calling context declares under a name id, or null. */
    protected ClassKey keyAt(int classId) {
        PhpClass phpClass = PhpContext.get(this).classAt(classId);
        return phpClass == null ? null : phpClass.getKey();
    }

    protected static final TruffleString.Encoding ENC = Str.ENC;

    protected static boolean isObjectMethod(PhpArray callable) {
        return callable.size() == 2 && callable.get(0L) instanceof PhpObject
                && callable.get(1L) instanceof TruffleString;
    }

    protected static boolean isClassMethod(PhpArray callable) {
        return callable.size() == 2 && callable.get(0L) instanceof TruffleString
                && callable.get(1L) instanceof TruffleString;
    }

    protected static TruffleString className(PhpArray callable) {
        return (TruffleString) callable.get(0L);
    }

    protected static PhpClass classOf(PhpArray callable) {
        return ((PhpObject) callable.get(0L)).getPhpClass();
    }

    protected static TruffleString methodName(PhpArray callable) {
        return (TruffleString) callable.get(1L);
    }
}
