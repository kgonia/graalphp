package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.SubclassTestNode;
import pl.codeloom.php.nodes.vars.ClassRefNode;
import pl.codeloom.php.runtime.ClassKey;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.Str;

/**
 * `Cls::m()`, `self::m()`, `parent::m()`, `static::m()`. Forwarding calls (self, parent, static) keep the
 * caller's late-static-binding class; a non-static method called this way receives the caller's $this when it
 * is an instance of the class, as PHP does for `parent::__construct()`.
 */
public final class StaticCallNode extends ExprNode {
    @Child private ClassRefNode classRef;
    @Child private ArgumentsNode args;
    @Child private Dispatch dispatch;
    @Child private SubclassTestNode subclassTest = SubclassTestNode.create();
    private final boolean forwarding;

    public StaticCallNode(ClassRefNode classRef, String methodName, ArgumentsNode args, boolean forwarding) {
        this.classRef = classRef;
        this.args = args;
        this.forwarding = forwarding;
        this.dispatch = StaticCallNodeFactory.DispatchNodeGen.create(Str.lower(methodName), Str.of(methodName));
    }

    @Override
    public Object execute(VirtualFrame frame) {
        PhpClass phpClass = classRef.resolve(frame);
        Object[] callerArgs = frame.getArguments();
        Object self = callerArgs[PhpFunction.ARG_THIS];
        if (self instanceof PhpObject object && !subclassTest.execute(object.getPhpClass(), phpClass)) {
            self = null;
        }
        PhpClass staticClass = phpClass;
        if (forwarding && callerArgs[PhpFunction.ARG_STATIC] instanceof PhpClass callerStatic) {
            staticClass = callerStatic;
        }
        return dispatch.execute(frame, phpClass, self, staticClass, args);
    }

    /**
     * Finds a method in a class, inline-cached on the class's key. A missing method goes to __call when the
     * caller's $this is an instance of the class, else to __callStatic (zend_std_get_static_method).
     */
    @ReportPolymorphism
    public abstract static class Dispatch extends Node {
        protected final String lowerName;
        protected final TruffleString calledName;

        protected Dispatch(String lowerName, TruffleString calledName) {
            this.lowerName = lowerName;
            this.calledName = calledName;
        }

        public abstract Object execute(VirtualFrame frame, PhpClass phpClass, Object self, PhpClass staticClass,
                                       ArgumentsNode args);

        /** Keyed on the class's language-level identity: the same cache entry serves every context. */
        @Specialization(guards = "phpClass.getKey() == cachedKey", limit = "4")
        protected Object cached(VirtualFrame frame, PhpClass phpClass, Object self, PhpClass staticClass,
                                ArgumentsNode args,
                                @Cached("phpClass.getKey()") ClassKey cachedKey,
                                @Cached("find(phpClass)") PhpFunction method,
                                @Cached("createCall(method)") DirectCallNode call,
                                @Cached IndirectCallNode magicCall) {
            if (method == null) {
                return magic(frame, phpClass, self, staticClass, args, magicCall);
            }
            Object thisObject = method.isStatic() ? null : self;
            return PhpRef.deref(call.call(args.build(frame, method, thisObject, staticClass, null)));
        }

        @Specialization(replaces = "cached")
        protected Object generic(VirtualFrame frame, PhpClass phpClass, Object self, PhpClass staticClass,
                                 ArgumentsNode args, @Cached IndirectCallNode call) {
            PhpFunction method = find(phpClass);
            if (method == null) {
                return magic(frame, phpClass, self, staticClass, args, call);
            }
            Object thisObject = method.isStatic() ? null : self;
            return PhpRef.deref(call.call(method.getCallTarget(),
                    args.build(frame, method, thisObject, staticClass, null)));
        }

        /** __call on the caller's $this (an instance of the class) or __callStatic, with the name as called. */
        private Object magic(VirtualFrame frame, PhpClass phpClass, Object self, PhpClass staticClass,
                             ArgumentsNode args, IndirectCallNode call) {
            Object[] values = Callables.magicValues(calledName, args.values(frame));
            PhpFunction viaCall = self != null ? magicMethod(phpClass, "__call") : null;
            if (viaCall != null) {
                return PhpRef.deref(call.call(viaCall.getCallTarget(),
                        ArgumentsNode.frameArguments(self, staticClass, null, values)));
            }
            PhpFunction viaStatic = magicMethod(phpClass, "__callstatic");
            if (viaStatic == null) {
                throw undefined(phpClass);
            }
            return PhpRef.deref(call.call(viaStatic.getCallTarget(),
                    ArgumentsNode.frameArguments(null, staticClass, null, values)));
        }

        protected static DirectCallNode createCall(PhpFunction method) {
            return method == null ? null : DirectCallNode.create(method.getCallTarget());
        }

        @TruffleBoundary
        private static PhpFunction magicMethod(PhpClass phpClass, String lowerName) {
            return phpClass.findMethod(lowerName);
        }

        /** The method, or null when __call or __callStatic stands in for it. */
        @TruffleBoundary
        protected PhpFunction find(PhpClass phpClass) {
            PhpFunction method = phpClass.findMethod(lowerName);
            if (method == null && phpClass.findMethod("__call") == null
                    && phpClass.findMethod("__callstatic") == null) {
                throw undefined(phpClass);
            }
            return method;
        }

        @TruffleBoundary
        private PhpError undefined(PhpClass phpClass) {
            return new PhpError("Call to undefined method " + phpClass.getName() + "::" + lowerName + "()");
        }
    }
}
