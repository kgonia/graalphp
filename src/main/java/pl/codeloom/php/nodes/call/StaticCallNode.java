package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.nodes.ExprNode;
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
    /** `Cls::$name(args)`: the name's expression; dispatch is null then. */
    @Child private ExprNode nameExpr;
    @Child private SubclassTestNode subclassTest = SubclassTestNode.create();
    private final boolean forwarding;

    public StaticCallNode(ClassRefNode classRef, String methodName, ArgumentsNode args, boolean forwarding) {
        this.classRef = classRef;
        this.args = args;
        this.forwarding = forwarding;
        this.dispatch = new Dispatch(methodName);
    }

    /** `Cls::$name(args)`. */
    public StaticCallNode(ClassRefNode classRef, ExprNode nameExpr, ArgumentsNode args, boolean forwarding) {
        this.classRef = classRef;
        this.args = args;
        this.forwarding = forwarding;
        this.nameExpr = nameExpr;
    }

    @Override
    public Object executeRef(VirtualFrame frame) {
        PhpClass phpClass = classRef.resolve(frame);
        Object[] callerArgs = frame.getArguments();
        if (nameExpr != null) {
            PhpClass called = forwarding && callerArgs[PhpFunction.ARG_STATIC] instanceof PhpClass callerStatic
                    ? callerStatic : phpClass;
            return CallableCallNode.call(frame, target(phpClass, nameExpr.execute(frame),
                    callerArgs[PhpFunction.ARG_THIS], called, MethodCallNode.scope(frame, this)), args,
                    IndirectCallNode.getUncached());
        }
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

        Dispatch(String name) {
            this.lowerName = Str.lower(name);
            this.calledName = Str.of(name);
            this.lookup = MethodLookupNode.create(name, true);
        }

        public Object execute(VirtualFrame frame, PhpClass phpClass, Object self, PhpClass staticClass,
                              ArgumentsNode args) {
            PhpFunction method = lowerName.equals("__construct") ? constructorOf(phpClass)
                    : lookup.execute(phpClass, MethodCallNode.scope(frame, this));
            if (method != null) {
                if (!method.isStatic()) {
                    if (lowerName.equals("__construct")) {
                        checkPrivateConstructor(phpClass, method, frame.getArguments()[PhpFunction.ARG_THIS]);
                    }
                    if (self == null) {
                        throw PhpClass.nonStaticCall(method);
                    }
                }
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
                throw phpClass.callError(Str.java(calledName), MethodCallNode.scope(frame, this));
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

    }

    /**
     * What `Cls::$name(args)` calls (INIT_STATIC_METHOD_CALL of a name computed at run time): the method as
     * zend_std_get_static_method finds it from scope, a non-static one on the caller's $this (an instance of the
     * class), else __call on that $this or __callStatic standing in; staticClass is the call's called class.
     */
    @TruffleBoundary
    public static Callables.Target target(PhpClass phpClass, Object name, Object callerThis, PhpClass staticClass,
                                          String scope) {
        if (!(name instanceof TruffleString method)) {
            throw MethodCallNode.nameNotString();
        }
        String text = Str.java(method);
        PhpObject self = callerThis instanceof PhpObject object
                && object.getPhpClass().isSubclassOf(phpClass.getLowerName()) ? object : null;
        PhpFunction function = phpClass.staticMethodFrom(text, scope);
        if (function == null) {
            PhpFunction viaCall = self != null ? phpClass.findMethod("__call") : null;
            if (viaCall != null) {
                return new Callables.Target(viaCall, self, self.getPhpClass(), null, method);
            }
            PhpFunction viaStatic = phpClass.findMethod("__callstatic");
            if (viaStatic == null) {
                throw phpClass.callError(text, scope);
            }
            return new Callables.Target(viaStatic, null, staticClass, null, method);
        }
        if (function.isStatic()) {
            return new Callables.Target(function, null, staticClass, null);
        }
        if (self == null) {
            throw PhpClass.nonStaticCall(function);
        }
        return new Callables.Target(function, self, self.getPhpClass(), null);
    }

    /** `Cls::__construct()`: the class's constructor, or php-src's Error for a class without one. */
    @TruffleBoundary
    public static PhpFunction constructorOf(PhpClass phpClass) {
        PhpFunction constructor = phpClass.getConstructor();
        if (constructor == null) {
            throw new PhpError("Cannot call constructor");
        }
        return constructor;
    }

    /** A private constructor called through `Cls::__construct()` on an object of another class. */
    @TruffleBoundary
    public static void checkPrivateConstructor(PhpClass phpClass, PhpFunction constructor, Object self) {
        if ((constructor.getModifiers() & PhpFunction.ACC_PRIVATE) != 0 && self instanceof PhpObject object
                && !object.getPhpClass().getName().equalsIgnoreCase(constructor.getClassName())) {
            throw new PhpError("Cannot call private " + phpClass.getName() + "::__construct()");
        }
    }
}
