package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import pl.codeloom.php.nodes.stmt.GeneratorNodes;
import pl.codeloom.php.nodes.vars.PropNode;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.ThunkRootNode;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.builtins.Args;
import pl.codeloom.php.runtime.Callables;
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
    private final String name;
    private final String lowerName;

    public MethodCallNode(ExprNode receiver, String methodName, ArgumentsNode args) {
        this.receiver = receiver;
        this.args = args;
        this.name = methodName;
        this.lowerName = Str.lower(methodName);
        this.dispatch = new Dispatch(methodName);
    }

    /**
     * php-src's executed scope at a tree node (lowercase, or null): the class bound in the frame (a closure's, an
     * included file's), else the class of the function the node is in. ponytail: a method run through a closure
     * made of it takes that closure's class.
     */
    public static String scope(VirtualFrame frame, Node site) {
        RootNode root = site.getRootNode();
        if (root instanceof ThunkRootNode) {
            // a constant expression is evaluated in its class's scope, the class it was bound to
            return frame.getArguments()[PhpFunction.ARG_STATIC] instanceof PhpClass owner ? owner.getLowerName() : null;
        }
        return PropNode.callingScope(frame, root instanceof FunctionRootNode function ? function.getScopeName()
                : root instanceof GeneratorNodes.BodyRootNode body ? body.getScopeName() : null);
    }

    @Override
    public Object executeRef(VirtualFrame frame) {
        Object target = receiver.execute(frame);
        if (target instanceof PhpObject object) {
            return dispatch.execute(frame, object, args);
        }
        if (target instanceof PhpClosure closure) {
            if (lowerName.equals("__invoke")) {
                return CallableCallNode.call(frame, Callables.resolveCall(PhpContext.get(this), closure, null), args,
                        IndirectCallNode.getUncached());
            }
            return closureMethod(this, name, closure, args.values(frame));
        }
        throw memberOnNonObject(name, target);
    }

    /** The Closure class's other methods (bindTo, call) on a closure value, called from site. */
    @TruffleBoundary
    public static Object closureMethod(Node site, String name, PhpClosure closure, Object[] values) {
        PhpFunction method = PhpContext.get(site).getClass("Closure").findMethod(Str.lower(name));
        if (method == null) {
            throw new PhpError("Call to undefined method Closure::" + name + "()");
        }
        return Backtrace.callFrom(site, () -> method.getCallTarget().call(
                ArgumentsNode.frameArguments(closure, null, null, values)));
    }

    /**
     * A method called on a value that isn't an object (zend_invalid_method_call): the name as written, the value as
     * zend_zval_value_name names it.
     */
    @TruffleBoundary
    public static PhpError memberOnNonObject(String name, Object target) {
        return new PhpError("Call to a member function " + name + "() on " + Args.valueName(target));
    }

    /** `$obj->$name()` with a name that isn't a string (ZEND_INIT_METHOD_CALL). */
    @TruffleBoundary
    public static PhpError nameNotString() {
        return new PhpError("Method name must be a string");
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

        Dispatch(String name) {
            this.lowerName = Str.lower(name);
            this.calledName = Str.of(name);
            this.lookup = MethodLookupNode.create(name, false);
        }

        public Object execute(VirtualFrame frame, PhpObject receiver, ArgumentsNode args) {
            PhpClass phpClass = receiver.getPhpClass();
            PhpFunction method = lookup.execute(phpClass, scope(frame, this));
            if (method != null) {
                return call.execute(frame, method, receiver, phpClass, args);
            }
            if (magicCall == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                insertMagic();
            }
            PhpFunction viaCall = magicLookup.execute(phpClass, null);
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
                    magicLookup = insert(MethodLookupNode.create("__call", false));
                    magicCall = insert(MethodDispatchNode.createMagic(calledName));
                }
            } finally {
                lock.unlock();
            }
        }

        @TruffleBoundary
        private PhpError undefined(PhpClass phpClass) {
            return undefinedMethod(phpClass, Str.java(calledName));
        }
    }

    /** zend_undefined_method: the class and the method name as written. */
    @TruffleBoundary
    public static PhpError undefinedMethod(PhpClass phpClass, String name) {
        return new PhpError("Call to undefined method " + phpClass.getName() + "::" + name + "()");
    }
}
