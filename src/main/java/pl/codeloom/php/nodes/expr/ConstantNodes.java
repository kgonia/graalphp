package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.vars.ClassRefNode;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;
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
        private final String name;
        private final int id;
        /** An unqualified name in a namespace falls back to the global constant: its id, else -1. */
        private final int fallbackId;
        @Child private StableValueNode stable = ConstantNodesFactory.StableValueNodeGen.create();
        @CompilationFinal private Object singleContextValue;

        /** id is the name's {@link pl.codeloom.php.PhpLanguage#constantId}. */
        public GlobalConstNode(String name, int id, int fallbackId) {
            this.name = name;
            this.id = id;
            this.fallbackId = fallbackId;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object value = singleContextValue;
            if (value != null && PhpLanguage.get(this).singleContext().isValid()) {
                return value;
            }
            PhpContext context = PhpContext.get(this);
            value = context.constantAt(id);
            if (value == null && fallbackId >= 0) {
                value = context.constantAt(fallbackId);
            }
            if (value == null) {
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("Undefined constant \"" + name + "\"");
            }
            if (singleContextValue == null && PhpLanguage.get(this).singleContext().isValid()) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                singleContextValue = value;
            }
            return stable.execute(value);
        }
    }

    /** The value itself, as a compile-time constant while it is always the same object. */
    @ReportPolymorphism
    public abstract static class StableValueNode extends Node {
        public abstract Object execute(Object value);

        @Specialization(guards = "value == cachedValue", limit = "1")
        protected static Object cached(Object value, @Cached("value") Object cachedValue) {
            return cachedValue;
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
        private final String name;
        private final boolean cacheable;
        @CompilationFinal private Object value;

        public ClassConstNode(ClassRefNode classRef, String name, boolean cacheable) {
            this.classRef = classRef;
            this.name = name;
            this.cacheable = cacheable;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (cacheable && value != null && PhpLanguage.get(this).singleContext().isValid()) {
                return value;
            }
            PhpClass phpClass = classRef.resolve(frame);
            Object found = phpClass.getConstant(name);
            if (found == null) {
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("Undefined constant " + phpClass.getName() + "::" + name);
            }
            if (cacheable && value == null && PhpLanguage.get(this).singleContext().isValid()) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                value = found;
            }
            return found;
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
