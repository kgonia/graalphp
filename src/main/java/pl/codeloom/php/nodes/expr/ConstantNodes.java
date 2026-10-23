package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.vars.ClassRefNode;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.Str;

/** Global and class constants. */
public final class ConstantNodes {
    private ConstantNodes() {
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
            PhpContext context = PhpContext.get(this);
            Object value = context.constantAt(id);
            if (value == null && fallbackId >= 0) {
                value = context.constantAt(fallbackId);
            }
            if (value == null) {
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("Undefined constant \"" + name + "\"");
            }
            return value;
        }

        protected Assumption singleContext() {
            return PhpLanguage.get(this).singleContext();
        }
    }

    /**
     * The value itself, as a compile-time constant while it is always the same object. Only immutable scalars and
     * strings are kept: the AST is shared by contexts, and an array or object belongs to the context that made it.
     */
    @ReportPolymorphism
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
            return lookup.execute(classRef.resolve(frame));
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

        abstract Object execute(PhpClass phpClass);

        @Specialization(guards = {"cacheable", "phpClass == cachedClass"}, limit = "1",
                assumptions = "singleContext()")
        protected Object single(PhpClass phpClass,
                                @Cached("phpClass") PhpClass cachedClass,
                                @Cached("find(cachedClass)") Object value) {
            return value;
        }

        @Specialization(replaces = "single")
        protected Object perCall(PhpClass phpClass) {
            return find(phpClass);
        }

        protected Object find(PhpClass phpClass) {
            Object found = phpClass.getConstant(name);
            if (found == null) {
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("Undefined constant " + phpClass.getName() + "::" + name);
            }
            return found;
        }

        protected Assumption singleContext() {
            return PhpLanguage.get(this).singleContext();
        }
    }

    /** `static::class` and `$obj::class`. */
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
