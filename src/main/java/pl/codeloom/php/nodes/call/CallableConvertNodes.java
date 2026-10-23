package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.vars.ClassRefNode;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.Str;

/**
 * The first-class callable syntax, `f(...)`, `$o->m(...)`, `A::m(...)`, `$f(...)`: a Closure of what the call
 * would call, with the call's own lookup and errors (zend_closure_from_frame). A method missing where the call
 * would take __call or __callStatic gives a closure that calls the magic method with the name.
 */
public final class CallableConvertNodes {
    private static final Object[] NO_CAPTURES = new Object[0];

    private CallableConvertNodes() {
    }

    private static PhpClosure closure(PhpFunction function, PhpObject self, PhpClass scope, TruffleString magicName) {
        return new PhpClosure(function, self, scope, NO_CAPTURES, magicName);
    }

    /** `f(...)`: the function the name resolves to, the namespaced one first. */
    public static final class FunctionNode extends ExprNode {
        private final String displayName;
        private final int id;
        private final int fallbackId;

        public FunctionNode(String displayName, int id, int fallbackId) {
            this.displayName = displayName;
            this.id = id;
            this.fallbackId = fallbackId;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return convert(PhpContext.get(this));
        }

        @TruffleBoundary
        private Object convert(PhpContext context) {
            PhpFunction function = context.functionAt(id);
            if (function == null && fallbackId >= 0) {
                function = context.functionAt(fallbackId);
            }
            if (function == null) {
                throw new PhpError("Call to undefined function " + displayName + "()");
            }
            return closure(function, null, null, null);
        }
    }

    /** `$o->m(...)` and `$o->$name(...)`: the object's method, bound to it. */
    public static final class MethodNode extends ExprNode {
        @Child private ExprNode object;
        @Child private ExprNode nameExpr;
        private final String name;

        /** A constant name, or null and the expression giving it. */
        public MethodNode(ExprNode object, String name, ExprNode nameExpr) {
            this.object = object;
            this.name = name;
            this.nameExpr = nameExpr;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object target = object.execute(frame);
            String method = name != null ? name : Str.java(Convert.toStr(nameExpr.execute(frame)));
            return convert(target, method);
        }

        @TruffleBoundary
        private static Object convert(Object target, String name) {
            if (target instanceof PhpClosure closure) {
                if (Str.lower(name).equals("__invoke")) {
                    return closure;
                }
                throw new PhpError("Call to undefined method Closure::" + name + "()");
            }
            if (!(target instanceof PhpObject self)) {
                throw new PhpError("Call to a member function " + name + "() on " + Convert.debugType(target));
            }
            PhpClass phpClass = self.getPhpClass();
            PhpFunction method = phpClass.findMethod(Str.lower(name));
            if (method != null) {
                return closure(method, method.isStatic() ? null : self, phpClass, null);
            }
            PhpFunction magic = phpClass.findMethod("__call");
            if (magic == null) {
                throw new PhpError("Call to undefined method " + phpClass.getName() + "::" + name + "()");
            }
            return closure(magic, self, phpClass, Str.of(name));
        }
    }

    /**
     * `A::m(...)`, `self::m(...)`, `parent::m(...)`, `static::m(...)`: as StaticCallNode, a non-static method takes
     * the caller's $this when it is an instance of the class, and forwarding calls keep the called class.
     */
    public static final class StaticMethodNode extends ExprNode {
        @Child private ClassRefNode classRef;
        private final String name;
        private final boolean forwarding;

        public StaticMethodNode(ClassRefNode classRef, String name, boolean forwarding) {
            this.classRef = classRef;
            this.name = name;
            this.forwarding = forwarding;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            PhpClass phpClass = classRef.resolve(frame);
            Object[] callerArgs = frame.getArguments();
            Object caller = callerArgs[PhpFunction.ARG_THIS];
            Object callerStatic = callerArgs[PhpFunction.ARG_STATIC];
            return convert(phpClass, caller, forwarding && callerStatic instanceof PhpClass called ? called : phpClass);
        }

        @TruffleBoundary
        private Object convert(PhpClass phpClass, Object caller, PhpClass staticClass) {
            PhpObject self = caller instanceof PhpObject object
                    && object.getPhpClass().isSubclassOf(phpClass.getLowerName()) ? object : null;
            PhpFunction method = phpClass.findMethod(Str.lower(name));
            if (method != null) {
                if (method.isStatic()) {
                    return closure(method, null, staticClass, null);
                }
                if (self == null) {
                    throw new PhpError("Non-static method " + phpClass.getName() + "::" + method.getName()
                            + "() cannot be called statically");
                }
                return closure(method, self, self.getPhpClass(), null);
            }
            PhpFunction magicCall = phpClass.findMethod("__call");
            if (self != null && magicCall != null) {
                return closure(magicCall, self, self.getPhpClass(), Str.of(name));
            }
            PhpFunction magicStatic = phpClass.findMethod("__callstatic");
            if (magicStatic != null) {
                return closure(magicStatic, null, staticClass, Str.of(name));
            }
            throw new PhpError("Call to undefined method " + phpClass.getName() + "::" + name + "()");
        }
    }

    /** `$f(...)`, `[$o, 'm'](...)`, `'A::m'(...)`: what the direct call `$f()` would call; a Closure stays itself. */
    public static final class ValueNode extends ExprNode {
        @Child private ExprNode callable;

        public ValueNode(ExprNode callable) {
            this.callable = callable;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return convert(PhpContext.get(this), callable.execute(frame));
        }

        @TruffleBoundary
        private static Object convert(PhpContext context, Object value) {
            if (value instanceof PhpClosure closure) {
                return closure;
            }
            Callables.Target target = Callables.resolveCall(context, value);
            PhpObject self = target.thisObject() instanceof PhpObject object ? object : null;
            return closure(target.function(), self, target.staticClass(), target.magicName());
        }
    }
}
