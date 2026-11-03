package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.Str;

/**
 * Resolves a class reference: a name (self and parent are resolved to names by the translator), `static`
 * (the late-static-binding class passed in the frame), or an expression giving a name or an object.
 * A named class is looked up in the calling context by its name's language-level id; while the language serves
 * one context the class found first is a compile-time constant (classes can't be redeclared).
 */
public final class ClassRefNode extends Node {
    /** closureScope's modes: the scope or null, self, parent, and the names self::class and parent::class give. */
    public static final int SCOPE = 0;
    public static final int SELF = 1;
    public static final int PARENT = 2;
    public static final int SELF_NAME = 3;
    public static final int PARENT_NAME = 4;
    public static final int STATIC_NAME = 5;

    private final String name;
    private final boolean lateStatic;
    /** self or parent in a closure (SELF, PARENT), else -1. */
    private final int inClosure;
    @Child private ExprNode dynamic;
    @Child private Named named;

    private ClassRefNode(String name, boolean lateStatic, ExprNode dynamic, int inClosure) {
        this.name = name;
        this.lateStatic = lateStatic;
        this.dynamic = dynamic;
        this.inClosure = inClosure;
        this.named = name == null ? null : ClassRefNodeFactory.NamedNodeGen.create(name);
    }

    public static ClassRefNode named(String name) {
        return new ClassRefNode(name, false, null, -1);
    }

    /** `static::`; selfName is used when no late-static-binding class was passed (plain function context). */
    public static ClassRefNode lateStatic(String selfName) {
        return new ClassRefNode(selfName, true, null, -1);
    }

    public static ClassRefNode dynamic(ExprNode expression) {
        return new ClassRefNode(null, false, expression, -1);
    }

    /** self (SELF) or parent (PARENT) in a closure: its bound scope's, see closureScope. */
    public static ClassRefNode inClosure(int mode) {
        return new ClassRefNode(null, false, null, mode);
    }

    /**
     * self and parent in a closure: the scope it is bound to (zend_fetch_class's executed scope, which bindTo and
     * call change), or its parent; SCOPE: the scope or null (a closure declared in it gets it); *_NAME: the name
     * (static::class's the called class's).
     */
    public static Object closureScope(Frame frame, int mode) {
        PhpClass scope = frame.getArguments()[PhpFunction.ARG_CLOSURE] instanceof PhpClosure closure
                ? closure.getScope() : null;
        boolean parent = mode == PARENT || mode == PARENT_NAME;
        if (mode == STATIC_NAME && scope != null) {
            // static::class: the called scope (ZEND_FETCH_CLASS_NAME), which needs a scope too
            return Str.of(((PhpClass) frame.getArguments()[PhpFunction.ARG_STATIC]).getName());
        }
        if (mode == SCOPE || scope != null && (!parent || scope.getParent() != null)) {
            PhpClass found = parent ? scope.getParent() : scope;
            return mode == SELF_NAME || mode == PARENT_NAME ? Str.of(found.getName()) : found;
        }
        CompilerDirectives.transferToInterpreter();
        throw noScope(mode, scope != null);
    }

    @TruffleBoundary
    private static PhpError noScope(int mode, boolean scoped) {
        boolean name = mode >= SELF_NAME;
        String which = mode == STATIC_NAME ? "static" : mode == PARENT || mode == PARENT_NAME ? "parent" : "self";
        return new PhpError(scoped ? "Cannot " + (name ? "use" : "access") + " \"parent\" when current class scope has no parent"
                : name ? "Cannot use \"" + which + "\" in the global scope"
                : "Cannot access \"" + which + "\" when no class scope is active");
    }

    public String getName() {
        return name;
    }

    public PhpClass resolve(VirtualFrame frame) {
        if (inClosure >= 0) {
            return (PhpClass) closureScope(frame, inClosure);
        }
        if (lateStatic && frame.getArguments()[PhpFunction.ARG_STATIC] instanceof PhpClass phpClass) {
            return phpClass;
        }
        if (dynamic != null) {
            return classOf(this, dynamic.execute(frame));
        }
        return named.execute();
    }

    /** The class an expression names: an object's class, or the class of the name the value converts to. */
    public static PhpClass classOf(Node site, Object value) {
        if (value instanceof PhpObject object) {
            return object.getPhpClass();
        }
        return PhpContext.get(site).getClass(Str.java(Convert.toStr(value)));
    }

    /**
     * The class declared under a name's id in the context; else (not declared yet) by name, which autoloads or
     * throws. The miss stays in compiled code: in a server each request is a new context, where built-in classes
     * are loaded and autoloaded ones declared on first use, so a transfer to the interpreter here deoptimized once
     * per request.
     */
    public static PhpClass find(PhpContext context, int id, String name) {
        PhpClass phpClass = context.classAt(id);
        if (CompilerDirectives.injectBranchProbability(CompilerDirectives.SLOWPATH_PROBABILITY, phpClass == null)) {
            phpClass = context.getClass(name); // throws "not found"
        }
        return phpClass;
    }

    /** A class by name: with one context the class found first, else the calling context's by name id. */
    abstract static class Named extends Node {
        protected final String name;

        Named(String name) {
            this.name = name;
        }

        abstract PhpClass execute();

        @Specialization(assumptions = "singleContext()")
        protected PhpClass single(@Cached("find(classId())") PhpClass phpClass) {
            return phpClass;
        }

        @Specialization(replaces = "single")
        protected PhpClass perContext(@Cached("classId()") int id) {
            return find(id);
        }

        /** The class under the name's id ({@link ClassRefNode#find}). */
        protected PhpClass find(int id) {
            return ClassRefNode.find(PhpContext.get(this), id, name);
        }

        protected int classId() {
            return PhpLanguage.get(this).classId(Str.lower(name));
        }

        protected Assumption singleContext() {
            return PhpLanguage.get(this).singleContext();
        }
    }
}
