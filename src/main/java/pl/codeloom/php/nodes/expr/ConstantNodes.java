package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.vars.ClassRefNode;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.Str;

/** Global and class constants. Constants never change once defined, so each site caches the first value. */
public final class ConstantNodes {
    private ConstantNodes() {
    }

    public static final class GlobalConstNode extends ExprNode {
        private final String name;
        @CompilationFinal private Object value;

        public GlobalConstNode(String name) {
            this.name = name;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            if (value == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                Object found = PhpContext.get(this).findConstant(name);
                if (found == null) {
                    throw new PhpError("Undefined constant \"" + name + "\"");
                }
                value = found;
            }
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
