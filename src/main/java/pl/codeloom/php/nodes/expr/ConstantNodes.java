package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.builtins.Args;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.call.MethodCallNode;
import pl.codeloom.php.nodes.vars.ClassRefNode;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.Str;

/** Global and class constants. */
public final class ConstantNodes {
    private ConstantNodes() {
    }

    /**
     * The object of `->` or of `[]` in a constant expression (zend_ast_evaluate): only an enum case's properties
     * may be read, and no object's elements.
     */
    public static final class ConstantObjectNode extends ExprNode {
        @Child private ExprNode value;
        private final boolean element;

        public ConstantObjectNode(ExprNode value, boolean element) {
            this.value = value;
            this.element = element;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return check(value.execute(frame), element);
        }

        public static Object check(Object value, boolean element) {
            if (value instanceof PhpObject object && (element || !object.getPhpClass().isEnum())) {
                CompilerDirectives.transferToInterpreter();
                throw new PhpError(element ? "Cannot use [] on objects in constant expression"
                        : "Fetching properties on non-enums in constant expressions is not allowed");
            }
            return value;
        }
    }

    /**
     * A global constant, looked up in the calling context by the name's language-level id. A constant never
     * changes once defined: while the language serves one context, the value found first is a compile-time
     * constant; with several, a site that sees one value object (true, a shared literal) still folds it.
     */
    public static final class GlobalConstNode extends ExprNode {
        @Child private GlobalConstLookup lookup;

        /** id is the name's {@link pl.codeloom.php.PhpLanguage#constantId}. */
        public GlobalConstNode(String name, int id, int fallbackId) {
            this.lookup = ConstantNodesFactory.GlobalConstLookupNodeGen.create(name, id, fallbackId);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return lookup.execute();
        }
    }

    /**
     * A deprecated predefined constant (#[\Deprecated], CONST_DEPRECATED): not folded, as php-src doesn't evaluate
     * one at compile time, and every fetch of it raises its deprecation, at the fetch's own line (a parameter's
     * default has no statement around it). In a namespace the name finds the namespace's own constant first, which
     * isn't deprecated.
     */
    public static final class DeprecatedConstNode extends ExprNode {
        private final String name;
        /** The name's id: the predefined constant's, or with a fallback the namespace's constant's. */
        private final int id;
        /** The predefined constant's id when the name is a namespace's unqualified one, else -1. */
        private final int fallbackId;
        private final TruffleString deprecation;
        private final int line;

        public DeprecatedConstNode(String name, int id, int fallbackId, String deprecation, int line) {
            this.name = name;
            this.id = id;
            this.fallbackId = fallbackId;
            this.deprecation = Str.of(deprecation);
            this.line = line;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return fetch(this, name, id, fallbackId, deprecation, line);
        }

        /** The constant's value, the deprecation raised at the fetch's line (also the bytecode's DeprecatedConstant). */
        @CompilerDirectives.TruffleBoundary
        public static Object fetch(Node site, String name, int id, int fallbackId, TruffleString deprecation,
                                   int line) {
            PhpContext context = PhpContext.get(site);
            Object value = context.constantAt(id);
            if (value != null && fallbackId >= 0) {
                return value;
            }
            if (value == null && fallbackId >= 0) {
                value = context.constantAt(fallbackId);
            }
            if (value == null) {
                throw new PhpError("Undefined constant \"" + name + "\"");
            }
            SourceLocation at = SourceLocation.of(site);
            if (SourceLocation.isUserCode(site.getRootNode())) {
                at = new SourceLocation(at.file(), line);
            }
            ErrorFunctions.raise(context, ErrorFunctions.E_DEPRECATED, deprecation, at);
            return value;
        }
    }

    abstract static class GlobalConstLookup extends Node {
        private final String name;
        private final int id;
        /** An unqualified name in a namespace falls back to the global constant: its id, else -1. */
        private final int fallbackId;

        GlobalConstLookup(String name, int id, int fallbackId) {
            this.name = name;
            this.id = id;
            this.fallbackId = fallbackId;
        }

        abstract Object execute();

        @Specialization(assumptions = "singleContext()")
        protected Object single(@Cached("find()") Object value) {
            return value;
        }

        @Specialization(replaces = "single")
        protected Object perContext(@Cached StableValueNode stable) {
            return stable.execute(find());
        }

        protected Object find() {
            return globalConstant(PhpContext.get(this), name, id, fallbackId);
        }

        protected Assumption singleContext() {
            return PhpLanguage.get(this).singleContext();
        }
    }

    /**
     * `defined('NAME')` as php-src compiles it (zend_compile_func_defined into ZEND_DEFINED): a literal name with no
     * namespace and no class, in a call that names the global function. The name's id is resolved here, and the
     * test is the context's slot, an exact match as zend_quick_check_constant's: true, false and null are folded by
     * the translator. A constant is never undefined, so, as ZEND_DEFINED's cache slot keeps the constant found,
     * the site is true from then on while the language serves one context.
     */
    public static final class DefinedNode extends ExprNode {
        private final int id;
        /** The constant was found while the language served one context. */
        @CompilerDirectives.CompilationFinal private boolean found;

        /** id is the name's {@link pl.codeloom.php.PhpLanguage#constantId}. */
        public DefinedNode(int id) {
            this.id = id;
        }

        @Override
        public boolean isBooleanValued() {
            return true;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return executeCondition(frame);
        }

        @Override
        public boolean executeBoolean(VirtualFrame frame) {
            return executeCondition(frame);
        }

        @Override
        public boolean executeCondition(VirtualFrame frame) {
            Assumption single = PhpLanguage.get(this).singleContext();
            if (found && single.isValid()) {
                return true;
            }
            if (PhpContext.get(this).constantAt(id) == null) {
                return false;
            }
            if (!found && single.isValid()) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                found = true;
            }
            return true;
        }
    }

    /**
     * The value itself, as a compile-time constant while it is always the same object. Only immutable scalars and
     * strings are kept: the AST is shared by contexts, and an array or object belongs to the context that made it.
     * It reports no polymorphism: a second value comes from another context or a redefinition, not from the caller,
     * so a copy per caller meets it too (get_theme_root's and wp_get_theme's copies on the tree: 47 of 47 went
     * generic again, wiki/bytecode-dsl.md "Which splits pay").
     */
    public abstract static class StableValueNode extends Node {
        public static StableValueNode create() {
            return ConstantNodesFactory.StableValueNodeGen.create();
        }

        public abstract Object execute(Object value);

        @Specialization(guards = {"value == cachedValue", "isImmutable(cachedValue)"}, limit = "1")
        protected static Object cached(Object value, @Cached("value") Object cachedValue) {
            return cachedValue;
        }

        protected static boolean isImmutable(Object value) {
            return value instanceof TruffleString || value instanceof Long || value instanceof Double
                    || value instanceof Boolean || value == PhpNull.NULL;
        }

        @Specialization(replaces = "cached")
        protected static Object generic(Object value) {
            return value;
        }
    }

    /**
     * `Cls::NAME`; with a `static::` class reference the value is looked up per call. Class constants are per
     * context (initializers may differ), so a site keeps its value as a constant only while the language serves
     * one context.
     */
    public static final class ClassConstNode extends ExprNode {
        @Child private ClassRefNode classRef;
        @Child private ClassConstLookup lookup;

        public ClassConstNode(ClassRefNode classRef, String name, boolean cacheable) {
            this.classRef = classRef;
            this.lookup = ConstantNodesFactory.ClassConstLookupNodeGen.create(name, cacheable);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return lookup.execute(classRef.resolve(frame), MethodCallNode.scope(frame, this));
        }
    }

    /**
     * A class constant's value. A cacheable site names one class, which with one context is one PhpClass (itself
     * a compile-time constant there), and keeps the value it found as a compile-time constant.
     */
    abstract static class ClassConstLookup extends Node {
        private final String name;
        protected final boolean cacheable;

        ClassConstLookup(String name, boolean cacheable) {
            this.name = name;
            this.cacheable = cacheable;
        }

        abstract Object execute(PhpClass phpClass, String scope);

        /** A constant that isn't public is kept for the scope the site saw. */
        @Specialization(guards = {"cacheable", "phpClass == cachedClass", "scope == cachedScope"}, limit = "1",
                assumptions = "singleContext()")
        protected Object single(PhpClass phpClass, String scope,
                                @Cached("phpClass") PhpClass cachedClass,
                                @Cached("scope") String cachedScope,
                                @Cached("find(cachedClass, scope)") Object value) {
            return value;
        }

        @Specialization(replaces = "single")
        protected Object perCall(PhpClass phpClass, String scope) {
            return find(phpClass, scope);
        }

        protected Object find(PhpClass phpClass, String scope) {
            return classConstant(phpClass, name, scope);
        }

        protected Assumption singleContext() {
            return PhpLanguage.get(this).singleContext();
        }
    }

    /**
     * The value of the global constant name (id its constantId; fallbackId the global one's for an unqualified name
     * in a namespace, else -1) in context; an Error if it isn't defined.
     */
    public static Object globalConstant(PhpContext context, String name, int id, int fallbackId) {
        Object value;
        if (fallbackId < 0) {
            value = context.requiredConstantAt(id);
        } else {
            value = context.constantAt(id);
            if (value == null) {
                value = context.constantAt(fallbackId);
            }
        }
        if (value == null) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Undefined constant \"" + name + "\"");
        }
        return value;
    }

    /**
     * The value of a class's constant from scope (lowercase, or null); an Error if it has none of the name or the
     * scope may not access it.
     */
    public static Object classConstant(PhpClass phpClass, String name, String scope) {
        Object found = phpClass.getConstant(name);
        if (found == null) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Undefined constant " + phpClass.getName() + "::" + name);
        }
        if (!phpClass.canAccessConstant(name, scope)) {
            CompilerDirectives.transferToInterpreter();
            throw phpClass.constantAccessError(name);
        }
        return found;
    }

    /**
     * `$obj::class` (ZEND_FETCH_CLASS_NAME of a value): the object's class name; anything but an object is a
     * TypeError.
     */
    public static final class ObjectClassNameNode extends ExprNode {
        @Child private ExprNode object;

        public ObjectClassNameNode(ExprNode object) {
            this.object = object;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return objectClassName(object.execute(frame));
        }
    }

    /** The class name `$value::class` gives: a Closure's is Closure; a value that isn't an object is a TypeError. */
    public static TruffleString objectClassName(Object value) {
        if (value instanceof PhpObject object) {
            return Str.of(object.getPhpClass().getName());
        }
        if (value instanceof PhpClosure) {
            return Str.of("Closure");
        }
        CompilerDirectives.transferToInterpreter();
        throw new PhpError("TypeError", "Cannot use \"::class\" on " + Args.valueName(value));
    }

    /** `static::class`. */
    public static final class ClassNameNode extends ExprNode {
        @Child private ClassRefNode classRef;

        public ClassNameNode(ClassRefNode classRef) {
            this.classRef = classRef;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return Str.of(classRef.resolve(frame).getName());
        }
    }
}
