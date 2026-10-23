package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.dsl.ReportPolymorphism;
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
@ReportPolymorphism
public abstract class CallableDispatchNode extends Node {
    static final int LIMIT = 6;

    /** The built-in parameter the callable was given to, for its TypeError; null for a direct call `$f()`. */
    protected final Callables.Parameter parameter;

    protected CallableDispatchNode(Callables.Parameter parameter) {
        this.parameter = parameter;
    }

    public static CallableDispatchNode create() {
        return CallableDispatchNodeGen.create(null);
    }

    public static CallableDispatchNode create(Callables.Parameter parameter) {
        return CallableDispatchNodeGen.create(parameter);
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
            "functionAt(functionId) == function"}, limit = "LIMIT")
    protected Object named(TruffleString callable, Object[] values,
                           @Cached("callable") TruffleString cachedName,
                           @Cached TruffleString.EqualNode equal,
                           @Cached("resolve(callable).function()") PhpFunction function,
                           @Cached("functionId(callable)") int functionId,
                           @Cached("create(function.getCallTarget())") DirectCallNode call) {
        return PhpRef.deref(call.call(ArgumentsNode.frameArguments(null, null, null, values)));
    }

    /**
     * `[$object, 'method']`, keyed on the object's class key: the same entry serves every context. The entry
     * keeps the method only (a language-level object); the receiver and its class are the current call's.
     */
    @Specialization(guards = {"isObjectMethod(callable)", "classOf(callable).getKey() == cachedKey",
            "equal.execute(methodName(callable), cachedMethod, ENC)"}, limit = "LIMIT")
    protected Object objectMethod(PhpArray callable, Object[] values,
                                  @Cached("classOf(callable).getKey()") ClassKey cachedKey,
                                  @Cached("methodName(callable)") TruffleString cachedMethod,
                                  @Cached TruffleString.EqualNode equal,
                                  @Cached("resolve(callable).function()") PhpFunction method,
                                  @Cached("resolve(callable).magicName()") TruffleString magicName,
                                  @Cached("create(method.getCallTarget())") DirectCallNode call) {
        Object self = method.isStatic() ? null : callable.get(0L);
        Object[] arguments = magicName == null ? values : Callables.magicValues(magicName, values);
        return PhpRef.deref(call.call(ArgumentsNode.frameArguments(self, classOf(callable), null, arguments)));
    }

    /**
     * `['Cls', 'method']`: the class is the calling context's, looked up by name id and checked by key; there is
     * no receiver. A 'parent::method' name resolves to another class and takes the generic path.
     */
    @Specialization(guards = {"isClassMethod(callable)", "equalClass.execute(className(callable), cachedClassName, ENC)",
            "isStaticCall(method, magicName)",
            "equalMethod.execute(methodName(callable), cachedMethod, ENC)", "classId >= 0",
            "keyAt(classId) == cachedKey"}, limit = "LIMIT")
    protected Object classMethod(PhpArray callable, Object[] values,
                                 @Cached("className(callable)") TruffleString cachedClassName,
                                 @Cached("methodName(callable)") TruffleString cachedMethod,
                                 @Cached TruffleString.EqualNode equalClass,
                                 @Cached TruffleString.EqualNode equalMethod,
                                 @Cached("resolve(callable).function()") PhpFunction method,
                                 @Cached("resolve(callable).magicName()") TruffleString magicName,
                                 @Cached("classId(cachedClassName, cachedMethod)") int classId,
                                 @Cached("keyAt(classId)") ClassKey cachedKey,
                                 @Cached("create(method.getCallTarget())") DirectCallNode call) {
        Object[] arguments = magicName == null ? values : Callables.magicValues(magicName, values);
        return PhpRef.deref(call.call(ArgumentsNode.frameArguments(null,
                PhpContext.get(this).classAt(classId), null, arguments)));
    }

    @Specialization(replaces = {"closure", "named", "objectMethod", "classMethod"})
    protected Object generic(Object callable, Object[] values,
                             @Cached IndirectCallNode call) {
        Callables.Target target = resolve(callable);
        return PhpRef.deref(call.call(target.function().getCallTarget(), target.frameArguments(values)));
    }

    /**
     * A static method (or __callStatic) binds no $this, so it can be cached; a non-static one or __call takes
     * the caller's $this, resolved per call by the generic path.
     */
    protected static boolean isStaticCall(PhpFunction method, TruffleString magicName) {
        return method.isStatic();
    }

    /** A direct call `$f()` (no parameter) takes names literally; a built-in's callable resolves against its caller. */
    protected Callables.Target resolve(Object callable) {
        PhpContext context = PhpContext.get(this);
        return parameter == null ? Callables.resolveCall(context, callable)
                : Callables.resolveOrThrow(context, callable, parameter);
    }

    /** The function name id of a callable naming a plain function, or -1 for a method ("Cls::m"). */
    protected int functionId(Object callable) {
        Callables.Target target = resolve(callable);
        if (target.thisObject() != null || target.staticClass() != null || target.closure() != null) {
            return -1;
        }
        return PhpLanguage.get(this).functionId(Str.lower(target.function().getName()));
    }

    protected PhpFunction functionAt(int id) {
        return PhpContext.get(this).functionAt(id);
    }

    /**
     * The class name's id, or -1 when the class depends on the calling code: a method name qualifying another
     * class ('parent::m'), or a callable's "self", "parent", "static".
     */
    protected int classId(TruffleString className, TruffleString methodName) {
        String name = Callables.className(className);
        if (Str.java(methodName).contains("::") || name.equals("self") || name.equals("parent")
                || name.equals("static")) {
            return -1;
        }
        return PhpLanguage.get(this).classId(name);
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
