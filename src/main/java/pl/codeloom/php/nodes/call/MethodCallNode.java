package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.ClassKey;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;

/** `$obj->name(args)`, inline-cached on the receiver's class. */
public final class MethodCallNode extends ExprNode {
    @Child private ExprNode receiver;
    @Child private ArgumentsNode args;
    @Child private Dispatch dispatch;
    @Child private CallableDispatchNode closureCall;
    private final String lowerName;

    public MethodCallNode(ExprNode receiver, String methodName, ArgumentsNode args) {
        this.receiver = receiver;
        this.args = args;
        this.lowerName = methodName.toLowerCase();
        this.dispatch = MethodCallNodeFactory.DispatchNodeGen.create(lowerName);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object target = receiver.execute(frame);
        if (target instanceof PhpObject object) {
            return dispatch.execute(frame, object, args);
        }
        if (target instanceof PhpClosure closure && lowerName.equals("__invoke")) {
            if (closureCall == null) {
                closureCall = insert(CallableDispatchNodeGen.create());
            }
            return closureCall.execute(closure, args.values(frame));
        }
        throw memberOnNonObject(target);
    }

    @TruffleBoundary
    private PhpError memberOnNonObject(Object target) {
        return new PhpError("Call to a member function " + lowerName + "() on " + Convert.typeName(target));
    }

    /** Finds and calls the method for the receiver's class. */
    public abstract static class Dispatch extends Node {
        protected final String lowerName;

        protected Dispatch(String lowerName) {
            this.lowerName = lowerName;
        }

        public abstract Object execute(VirtualFrame frame, PhpObject receiver, ArgumentsNode args);

        /** Keyed on the class's language-level identity: the same cache entry serves every context. */
        @Specialization(guards = "receiver.getPhpClass().getKey() == cachedKey", limit = "4")
        protected Object cached(VirtualFrame frame, PhpObject receiver, ArgumentsNode args,
                                @Cached("receiver.getPhpClass().getKey()") ClassKey cachedKey,
                                @Cached("find(receiver.getPhpClass())") PhpFunction method,
                                @Cached("create(method.getCallTarget())") DirectCallNode call) {
            Object self = method.isStatic() ? null : receiver;
            return PhpRef.deref(call.call(args.build(frame, method, self, receiver.getPhpClass(), null)));
        }

        @Specialization(replaces = "cached")
        protected Object generic(VirtualFrame frame, PhpObject receiver, ArgumentsNode args,
                                 @Cached IndirectCallNode call) {
            PhpClass phpClass = receiver.getPhpClass();
            PhpFunction method = find(phpClass);
            Object self = method.isStatic() ? null : receiver;
            return PhpRef.deref(call.call(method.getCallTarget(), args.build(frame, method, self, phpClass, null)));
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
