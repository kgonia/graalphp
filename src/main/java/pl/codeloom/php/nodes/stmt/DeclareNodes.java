package pl.codeloom.php.nodes.stmt;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.StmtNode;
import pl.codeloom.php.runtime.ClassTemplate;
import pl.codeloom.php.runtime.PhpFunction;

/**
 * Function and class declarations. The translator makes the language-level declaration (a PhpFunction or a
 * ClassTemplate); these nodes declare it in the context whose code runs them.
 */
public final class DeclareNodes {
    private DeclareNodes() {
    }

    public static final class DeclareFunctionNode extends StmtNode {
        private final PhpFunction function;

        public DeclareFunctionNode(PhpFunction function) {
            this.function = function;
        }

        public PhpFunction getFunction() {
            return function;
        }

        @Override
        public void execute(VirtualFrame frame) {
            declare(PhpContext.get(this), function);
        }

        @TruffleBoundary
        private static void declare(PhpContext context, PhpFunction function) {
            context.declareFunction(function);
        }
    }

    public static final class DeclareClassNode extends StmtNode {
        private final ClassTemplate template;

        public DeclareClassNode(ClassTemplate template) {
            this.template = template;
        }

        public ClassTemplate getTemplate() {
            return template;
        }

        @Override
        public void execute(VirtualFrame frame) {
            declare(PhpContext.get(this), template);
        }

        @TruffleBoundary
        private static void declare(PhpContext context, ClassTemplate template) {
            context.declareClass(template.instantiate(context));
        }
    }

    /**
     * `new class(...) {...}`: declares the anonymous class the first time the context runs the expression, then
     * instantiates it. The name, as in php-src, is "class@anonymous", a NUL and the declaration's place.
     */
    public static final class NewAnonymousClassNode extends ExprNode {
        private final ClassTemplate template;
        private final String lowerName;
        @Child private ExprNode instantiate;

        public NewAnonymousClassNode(ClassTemplate template, ExprNode instantiate) {
            this.template = template;
            this.lowerName = template.getName().toLowerCase();
            this.instantiate = instantiate;
        }

        public ClassTemplate getTemplate() {
            return template;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            declareOnce(PhpContext.get(this), template, lowerName);
            return instantiate.execute(frame);
        }

        @TruffleBoundary
        private static void declareOnce(PhpContext context, ClassTemplate template, String lowerName) {
            if (context.findClass(lowerName) == null) {
                context.declareClass(template.instantiate(context));
            }
        }
    }
}
