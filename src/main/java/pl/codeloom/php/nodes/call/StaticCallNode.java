package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.nodes.SubclassTestNode;
import pl.codeloom.php.nodes.vars.ClassRefNode;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpObject;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.Str;

import java.util.concurrent.locks.Lock;

/**
 * `Cls::m()`, `self::m()`, `parent::m()`, `static::m()`. Forwarding calls (self, parent, static) keep the
 * caller's late-static-binding class; a non-static method called this way receives the caller's $this when it
 * is an instance of the class, as PHP does for `parent::__construct()`.
 */
public final class StaticCallNode extends CallNode {
    @Child private ClassRefNode classRef;
    @Child private ArgumentsNode args;
    @Child private Dispatch dispatch;
    @Child private SubclassTestNode subclassTest = SubclassTestNode.create();
    private final boolean forwarding;

    public StaticCallNode(ClassRefNode classRef, String methodName, ArgumentsNode args, boolean forwarding) {
        this.classRef = classRef;
        this.args = args;
        this.forwarding = forwarding;
        this.dispatch = new Dispatch(Str.lower(methodName), Str.of(methodName));
    }

    @Override
    public Object executeRef(VirtualFrame frame) {
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
     * Finds a method in a class and calls it, looked up per class and called per method found
     * ({@link MethodLookupNode}, {@link MethodDispatchNode}). A missing method goes to __call when the caller's
     * $this is an instance of the class, else to __callStatic (zend_std_get_static_method); with neither, the call
     * is an error before its arguments are evaluated.
     */
    public static final class Dispatch extends Node {
        private final String lowerName;
        private final TruffleString calledName;
        @Child private MethodLookupNode lookup;
        @Child private MethodDispatchNode call = MethodDispatchNode.create();
        /** For a missing method: __call and __callStatic called indirectly, made when first needed. */
        @Child private IndirectCallNode magicCall;

        Dispatch(String lowerName, TruffleString calledName) {
            this.lowerName = lowerName;
            this.calledName = calledName;
            this.lookup = MethodLookupNode.create(lowerName);
        }

        public Object execute(VirtualFrame frame, PhpClass phpClass, Object self, PhpClass staticClass,
                              ArgumentsNode args) {
            PhpFunction method = lookup.execute(phpClass);
            if (method != null) {
                return call.execute(frame, method, self, staticClass, args);
            }
            if (magicCall == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                insertMagic();
            }
            return magic(frame, phpClass, self, staticClass, args);
        }

        /** __call on the caller's $this (an instance of the class) or __callStatic, with the name as called. */
        private Object magic(VirtualFrame frame, PhpClass phpClass, Object self, PhpClass staticClass,
                             ArgumentsNode args) {
            PhpFunction viaCall = self != null ? magicMethod(phpClass, "__call") : null;
            PhpFunction viaStatic = viaCall == null ? magicMethod(phpClass, "__callstatic") : null;
            if (viaCall == null && viaStatic == null) {
                throw undefined(phpClass);
            }
            Object[] values = Callables.magicValues(calledName, args.values(frame));
            if (viaCall != null) {
                return magicCall.call(viaCall.getCallTarget(),
                        ArgumentsNode.frameArguments(self, staticClass, null, values));
            }
            return magicCall.call(viaStatic.getCallTarget(),
                    ArgumentsNode.frameArguments(null, staticClass, null, values));
        }

        /** Inserted under the AST lock: the AST is shared by contexts that may run on several threads. */
        private void insertMagic() {
            Lock lock = getLock();
            lock.lock();
            try {
                if (magicCall == null) {
                    magicCall = insert(IndirectCallNode.create());
                }
            } finally {
                lock.unlock();
            }
        }

        @TruffleBoundary
        private static PhpFunction magicMethod(PhpClass phpClass, String lowerName) {
            return phpClass.findMethod(lowerName);
        }

        /**
         * A static call's method (the name as written) in a class, or null when __call or __callStatic stands in
         * for it; else an Error.
         */
        @TruffleBoundary
        public static PhpFunction findStatic(PhpClass phpClass, String name) {
            PhpFunction method = phpClass.findMethod(Str.lower(name));
            if (method == null && phpClass.findMethod("__call") == null
                    && phpClass.findMethod("__callstatic") == null) {
                throw MethodCallNode.undefinedMethod(phpClass, name);
            }
            return method;
        }

        @TruffleBoundary
        private PhpError undefined(PhpClass phpClass) {
            return MethodCallNode.undefinedMethod(phpClass, Str.java(calledName));
        }
    }
}
