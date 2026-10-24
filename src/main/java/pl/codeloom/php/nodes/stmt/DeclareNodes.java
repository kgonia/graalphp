package pl.codeloom.php.nodes.stmt;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.StmtNode;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.runtime.ClassTemplate;
import pl.codeloom.php.runtime.PhpBailout;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

/**
 * Function and class declarations. The translator makes the language-level declaration (a PhpFunction or a
 * ClassTemplate); these nodes declare it in the context whose code runs them.
 */
public final class DeclareNodes {
    private DeclareNodes() {
    }

    public static final class DeclareFunctionNode extends StmtNode {
        private final PhpFunction function;
        /** A top-level declaration, which php-src binds when it compiles the file. */
        private final boolean compileTime;

        public DeclareFunctionNode(PhpFunction function, boolean compileTime) {
            this.function = function;
            this.compileTime = compileTime;
        }

        public PhpFunction getFunction() {
            return function;
        }

        @Override
        public void execute(VirtualFrame frame) {
            declare(PhpContext.get(this));
        }

        /**
         * do_bind_function_error when the name is taken: E_COMPILE_ERROR for a top-level declaration (before the
         * file runs: its trace starts below the file), E_ERROR for one that runs later.
         */
        @TruffleBoundary
        private void declare(PhpContext context) {
            PhpFunction previous = context.findFunction(Str.lower(function.getName()));
            if (previous == null) {
                context.declareFunction(function);
                return;
            }
            SourceLocation declared = previous.getDeclaredAt();
            String message = "Cannot redeclare function " + function.getName() + "()" + (declared != null
                    ? " (previously declared in " + declared.file() + ":" + declared.line() + ")" : "");
            throw ErrorFunctions.fatal(context, compileTime ? ErrorFunctions.E_COMPILE_ERROR : ErrorFunctions.E_ERROR,
                    Str.of(message), SourceLocation.of(this), compileTime ? 1 : 0);
        }
    }

    /**
     * zend_class_redeclaration_error when the name is taken (E_COMPILE_ERROR), before the class is linked: named
     * by what the declared one is (class, interface, trait).
     */
    private static PhpBailout redeclared(PhpContext context, PhpClass previous, ClassTemplate template,
                                         StmtNode site) {
        ClassTemplate old = previous.getDeclaration() instanceof ClassTemplate declaration ? declaration : null;
        SourceLocation declared = old == null ? null : old.getDeclaredAt();
        String kind = previous.isInterface() ? "interface" : old != null && old.isTrait() ? "trait" : "class";
        String message = "Cannot redeclare " + kind + " " + template.getName() + (declared != null
                ? " (previously declared in " + declared.file() + ":" + declared.line() + ")" : "");
        return ErrorFunctions.fatal(context, ErrorFunctions.E_COMPILE_ERROR, Str.of(message), SourceLocation.of(site),
                0);
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
            declare(PhpContext.get(this));
        }

        @TruffleBoundary
        private void declare(PhpContext context) {
            PhpClass previous = context.findClass(Str.lower(template.getName()));
            if (previous != null) {
                throw redeclared(context, previous, template, this);
            }
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
            this.lowerName = Str.lower(template.getName());
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
