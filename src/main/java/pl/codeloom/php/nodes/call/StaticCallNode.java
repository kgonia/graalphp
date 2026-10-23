package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.vars.ClassRefNode;
import pl.codeloom.php.runtime.ClassKey;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;

/**
 * `Cls::m()`, `self::m()`, `parent::m()`, `static::m()`. Forwarding calls (self, parent, static) keep the
 * caller's late-static-binding class; a non-static method called this way receives the caller's $this when it
 * is an instance of the class, as PHP does for `parent::__construct()`.
 */
public final class StaticCallNode extends ExprNode {
    @Child private ClassRefNode classRef;
    @Child private ArgumentsNode args;
    @Child private Dispatch dispatch;
    private final boolean forwarding;

    public StaticCallNode(ClassRefNode classRef, String methodName, ArgumentsNode args, boolean forwarding) {
        this.classRef = classRef;
        this.args = args;
        this.forwarding = forwarding;
        this.dispatch = StaticCallNodeFactory.DispatchNodeGen.create(methodName.toLowerCase());
    }

    @Override
    public Object execute(VirtualFrame frame) {
        PhpClass phpClass = classRef.resolve(frame);
        Object[] callerArgs = frame.getArguments();
        Object self = callerArgs[PhpFunction.ARG_THIS];
        if (self instanceof PhpObject object && !object.getPhpClass().isSubclassOf(phpClass.getLowerName())) {
            self = null;
        }
        PhpClass staticClass = phpClass;
        if (forwarding && callerArgs[PhpFunction.ARG_STATIC] instanceof PhpClass callerStatic) {
            staticClass = callerStatic;
        }
        return dispatch.execute(frame, phpClass, self, staticClass, args);
    }

    /** Finds a method in a class, inline-cached on the class's key. */
    @ReportPolymorphism
    public abstract static class Dispatch extends Node {
        protected final String lowerName;

        protected Dispatch(String lowerName) {
            this.lowerName = lowerName;
        }

        public abstract Object execute(VirtualFrame frame, PhpClass phpClass, Object self, PhpClass staticClass,
                                       ArgumentsNode args);

        /** Keyed on the class's language-level identity: the same cache entry serves every context. */
        @Specialization(guards = "phpClass.getKey() == cachedKey", limit = "4")
        protected Object cached(VirtualFrame frame, PhpClass phpClass, Object self, PhpClass staticClass,
                                ArgumentsNode args,
                                @Cached("phpClass.getKey()") ClassKey cachedKey,
                                @Cached("find(phpClass)") PhpFunction method,
                                @Cached("create(method.getCallTarget())") DirectCallNode call) {
            Object thisObject = method.isStatic() ? null : self;
            return PhpRef.deref(call.call(args.build(frame, method, thisObject, staticClass, null)));
        }

        @Specialization(replaces = "cached")
        protected Object generic(VirtualFrame frame, PhpClass phpClass, Object self, PhpClass staticClass,
                                 ArgumentsNode args, @Cached IndirectCallNode call) {
            PhpFunction method = find(phpClass);
            Object thisObject = method.isStatic() ? null : self;
            return PhpRef.deref(call.call(method.getCallTarget(),
                    args.build(frame, method, thisObject, staticClass, null)));
        }

        @TruffleBoundary
        protected PhpFunction find(PhpClass phpClass) {
            PhpFunction method = phpClass.findMethod(lowerName);
            if (method == null) {
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("Call to undefined method " + phpClass.getName() + "::" + lowerName + "()");
            }
            return method;
        }
    }
}
