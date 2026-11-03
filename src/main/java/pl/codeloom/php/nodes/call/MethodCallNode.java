package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.builtins.Args;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.Str;
import com.oracle.truffle.api.strings.TruffleString;

import java.util.concurrent.locks.Lock;

/** `$obj->name(args)`: the method looked up per receiver class and called per method found (Dispatch). */
public final class MethodCallNode extends CallNode {
    @Child private ExprNode receiver;
    @Child private ArgumentsNode args;
    @Child private Dispatch dispatch;
    @Child private CallableDispatchNode closureCall;
    private final String lowerName;

    public MethodCallNode(ExprNode receiver, String methodName, ArgumentsNode args) {
        this.receiver = receiver;
        this.args = args;
        this.lowerName = Str.lower(methodName);
        this.dispatch = new Dispatch(lowerName, Str.of(methodName));
    }

    @Override
    public Object executeRef(VirtualFrame frame) {
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
                return call.execute(closure, closure.getMagicName() == null
                        ? args.values(frame, closure.getFunction()) : args.values(frame));
            }
            return closureMethod(this, lowerName, closure, args.values(frame));
        }
        throw memberOnNonObject(lowerName, target);
    }

    /** The Closure class's other methods (bindTo, call) on a closure value, called from site. */
    @TruffleBoundary
    public static Object closureMethod(Node site, String lowerName, PhpClosure closure, Object[] values) {
        PhpFunction method = PhpContext.get(site).getClass("Closure").findMethod(lowerName);
        if (method == null) {
            throw new PhpError("Call to undefined method Closure::" + lowerName + "()");
        }
        return Backtrace.callFrom(site, () -> method.getCallTarget().call(
                ArgumentsNode.frameArguments(closure, null, null, values)));
    }

    /** Inserted under the AST lock: the AST is shared by contexts that may run on several threads. */
    private CallableDispatchNode closureCall() {
        Lock lock = getLock();
        lock.lock();
        try {
            if (closureCall == null) {
                closureCall = insert(CallableDispatchNode.create(null, true));
            }
            return closureCall;
        } finally {
            lock.unlock();
        }
    }

    /** A method called on a value that isn't an object: the value as zend_zval_value_name names it. */
    @TruffleBoundary
    public static PhpError memberOnNonObject(String lowerName, Object target) {
        return new PhpError("Call to a member function " + lowerName + "() on " + Args.valueName(target));
    }

    /**
     * Finds and calls the method for the receiver's class; a class without it but with __call gets
     * __call(name, arguments), the name as called. The method is looked up per class and called per method
     * ({@link MethodLookupNode}, {@link MethodDispatchNode}): the receiver is $this and its class the called class,
     * whatever class the method was found in.
     */
    public static final class Dispatch extends Node {
        private final String lowerName;
        private final TruffleString calledName;
        @Child private MethodLookupNode lookup;
        @Child private MethodDispatchNode call = MethodDispatchNode.create();
        /** __call's lookup and call, made when a class without the method first comes. */
        @Child private MethodLookupNode magicLookup;
        @Child private MethodDispatchNode magicCall;

        Dispatch(String lowerName, TruffleString calledName) {
            this.lowerName = lowerName;
            this.calledName = calledName;
            this.lookup = MethodLookupNode.create(lowerName);
        }

        public Object execute(VirtualFrame frame, PhpObject receiver, ArgumentsNode args) {
            PhpClass phpClass = receiver.getPhpClass();
            PhpFunction method = lookup.execute(phpClass);
            if (method != null) {
                return call.execute(frame, method, receiver, phpClass, args);
            }
            if (magicCall == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                insertMagic();
            }
            PhpFunction viaCall = magicLookup.execute(phpClass);
            if (viaCall == null) {
                throw undefined(phpClass);
            }
            return magicCall.execute(frame, viaCall, receiver, phpClass, args);
        }

        /** Inserted under the AST lock: the AST is shared by contexts that may run on several threads. */
        private void insertMagic() {
            Lock lock = getLock();
            lock.lock();
            try {
                if (magicCall == null) {
                    magicLookup = insert(MethodLookupNode.create("__call"));
                    magicCall = insert(MethodDispatchNode.createMagic(calledName));
                }
            } finally {
                lock.unlock();
            }
        }

        /** The method of a class an object's method call calls, or __call standing in for it; else an Error. */
        @TruffleBoundary
        public static PhpFunction findMethod(PhpClass phpClass, String lowerName) {
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
        private PhpError undefined(PhpClass phpClass) {
            return new PhpError("Call to undefined method " + phpClass.getName() + "::" + lowerName + "()");
        }
    }
}
