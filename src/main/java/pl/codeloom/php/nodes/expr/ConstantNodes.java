package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.PhpContext;
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
     * changes once defined: while a site sees one value object (true, an int defined once, or one context's
     * string) compiled code uses it as a constant.
     */
    public static final class GlobalConstNode extends ExprNode {
        private final String name;
        private final int id;
        @Child private StableValueNode stable = ConstantNodesFactory.StableValueNodeGen.create();

        /** id is the name's {@link pl.codeloom.php.PhpLanguage#constantId}. */
        public GlobalConstNode(String name, int id) {
            this.name = name;
            this.id = id;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object value = PhpContext.get(this).constantAt(id);
            if (value == null) {
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("Undefined constant \"" + name + "\"");
            }
            return stable.execute(value);
        }
    }

    /** The value itself, as a compile-time constant while it is always the same object. */
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

    /** `Cls::NAME`; with a `static::` class reference the value is looked up per call. */
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
            if (cacheable && value != null) {
                return value;
            }
            PhpClass phpClass = classRef.resolve(frame);
            Object found = phpClass.getConstant(name);
            if (found == null) {
                CompilerDirectives.transferToInterpreter();
                throw new PhpError("Undefined constant " + phpClass.getName() + "::" + name);
            }
            if (cacheable) {
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
