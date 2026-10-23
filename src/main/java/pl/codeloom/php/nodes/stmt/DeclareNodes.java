package pl.codeloom.php.nodes.stmt;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.PhpContext;
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

        @Override
        public void execute(VirtualFrame frame) {
            declare(PhpContext.get(this), template);
        }

        @TruffleBoundary
        private static void declare(PhpContext context, ClassTemplate template) {
            context.declareClass(template.instantiate(context));
        }
    }
}
