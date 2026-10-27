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
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.ClassKey;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;
import pl.codeloom.php.runtime.Callables;
import com.oracle.truffle.api.strings.TruffleString;

import java.util.concurrent.locks.Lock;

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
        this.lowerName = Str.lower(methodName);
        this.dispatch = MethodCallNodeFactory.DispatchNodeGen.create(lowerName, Str.of(methodName));
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object target = receiver.execute(frame);
        if (target instanceof PhpObject object) {
            return dispatch.execute(frame, object, args);
        }
        if (target instanceof PhpClosure closure) {
            if (lowerName.equals("__invoke")) {
                CallableDispatchNode call = closureCall;
                if (call == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    call = closureCall();
                }
                return call.execute(closure, args.values(frame));
            }
            return closureMethod(closure, args.values(frame));
        }
        throw memberOnNonObject(target);
    }

    /** The Closure class's other methods (bindTo, call) on a closure value. */
    @TruffleBoundary
    private Object closureMethod(PhpClosure closure, Object[] values) {
        PhpFunction method = PhpContext.get(this).getClass("Closure").findMethod(lowerName);
        if (method == null) {
            throw new PhpError("Call to undefined method Closure::" + lowerName + "()");
        }
        return Backtrace.callFrom(this, () -> method.getCallTarget().call(
                ArgumentsNode.frameArguments(closure, null, null, values)));
    }

    /** Inserted under the AST lock: the AST is shared by contexts that may run on several threads. */
    private CallableDispatchNode closureCall() {
        Lock lock = getLock();
        lock.lock();
        try {
            if (closureCall == null) {
                closureCall = insert(CallableDispatchNode.create());
            }
            return closureCall;
        } finally {
            lock.unlock();
        }
    }

    @TruffleBoundary
    private PhpError memberOnNonObject(Object target) {
        return new PhpError("Call to a member function " + lowerName + "() on " + Convert.typeName(target));
    }

    /**
     * Finds and calls the method for the receiver's class; a class without it but with __call gets
     * __call(name, arguments), the name as called.
     */
    @ReportPolymorphism
    public abstract static class Dispatch extends Node {
        protected final String lowerName;
        protected final TruffleString calledName;

        protected Dispatch(String lowerName, TruffleString calledName) {
            this.lowerName = lowerName;
            this.calledName = calledName;
        }

        public abstract Object execute(VirtualFrame frame, PhpObject receiver, ArgumentsNode args);

        /** Keyed on the class's language-level identity: the same cache entry serves every context. */
        @Specialization(guards = "receiver.getPhpClass().getKey() == cachedKey", limit = "4")
        protected Object cached(VirtualFrame frame, PhpObject receiver, ArgumentsNode args,
                                @Cached("receiver.getPhpClass().getKey()") ClassKey cachedKey,
                                @Cached("find(receiver.getPhpClass())") PhpFunction method,
                                @Cached("isMagic(receiver.getPhpClass())") boolean magic,
                                @Cached("create(method.getCallTarget())") DirectCallNode call) {
            PhpClass phpClass = receiver.getPhpClass();
            if (magic) {
                return PhpRef.deref(call.call(ArgumentsNode.frameArguments(receiver, phpClass, null,
                        Callables.magicValues(calledName, args.values(frame)))));
            }
            Object self = method.isStatic() ? null : receiver;
            return PhpRef.deref(call.call(args.build(frame, method, self, phpClass, null)));
        }

        @Specialization(replaces = "cached")
        protected Object generic(VirtualFrame frame, PhpObject receiver, ArgumentsNode args,
                                 @Cached IndirectCallNode call) {
            PhpClass phpClass = receiver.getPhpClass();
            PhpFunction method = find(phpClass);
            if (isMagic(phpClass)) {
                return PhpRef.deref(call.call(method.getCallTarget(), ArgumentsNode.frameArguments(receiver,
                        phpClass, null, Callables.magicValues(calledName, args.values(frame)))));
            }
            Object self = method.isStatic() ? null : receiver;
            return PhpRef.deref(call.call(method.getCallTarget(), args.build(frame, method, self, phpClass, null)));
        }

        /** The method, or __call standing in for it. */
        @TruffleBoundary
        protected PhpFunction find(PhpClass phpClass) {
            PhpFunction method = phpClass.findMethod(lowerName);
            if (method == null) {
                method = phpClass.findMethod("__call");
            }
            if (method == null) {
                throw new PhpError("Call to undefined method " + phpClass.getName() + "::" + lowerName + "()");
            }
            return method;
        }

        @TruffleBoundary
        protected boolean isMagic(PhpClass phpClass) {
            return phpClass.findMethod(lowerName) == null;
        }
    }
}
