package pl.codeloom.php.nodes.stmt;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.PhpContext;
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

    public static final class DeclareFunctionNode {
        /**
         * do_bind_function_error when the name is taken: E_COMPILE_ERROR for a top-level declaration (before the
         * file runs: its trace starts below the file), E_ERROR for one that runs later; at site.
         */
        @TruffleBoundary
        public static void declare(PhpContext context, PhpFunction function, boolean compileTime, Node site) {
            PhpFunction previous = context.findFunction(Str.lower(function.getName()));
            if (previous == null) {
                context.declareFunction(function);
                return;
            }
            SourceLocation declared = previous.getDeclaredAt();
            String message = "Cannot redeclare function " + function.getName() + "()" + (declared != null
                    ? " (previously declared in " + declared.file() + ":" + declared.line() + ")" : "");
            throw ErrorFunctions.fatal(context, compileTime ? ErrorFunctions.E_COMPILE_ERROR : ErrorFunctions.E_ERROR,
                    Str.of(message), SourceLocation.of(site), compileTime ? 1 : 0);
        }
    }

    /**
     * zend_class_redeclaration_error when the name is taken, before the class is linked: named by what the declared
     * one is (class, interface, trait) and its name.
     */
    private static PhpBailout redeclared(PhpContext context, int type, PhpClass previous, SourceLocation at,
                                         int skip) {
        ClassTemplate old = previous.getDeclaration() instanceof ClassTemplate declaration ? declaration : null;
        SourceLocation declared = old == null ? null : old.getDeclaredAt();
        String kind = previous.isInterface() ? "interface" : old != null && old.isTrait() ? "trait" : "class";
        String message = "Cannot redeclare " + kind + " " + previous.getName() + (declared != null
                ? " (previously declared in " + declared.file() + ":" + declared.line() + ")" : "");
        return ErrorFunctions.fatal(context, type, Str.of(message), at, skip);
    }

    /**
     * What linking a class reports (zend_do_link_class): implementing Serializable without both __serialize() and
     * __unserialize() is deprecated (zend_implement_serializable), unless the class is declared abstract.
     */
    private static PhpClass linked(PhpContext context, PhpClass phpClass, Node site) {
        if (!phpClass.isInterface() && !phpClass.isAbstract() && phpClass.isDeclaredSubclassOf("serializable")
                && (phpClass.findMethod("__serialize") == null || phpClass.findMethod("__unserialize") == null)) {
            // the name as %s prints it: an anonymous class's up to its NUL
            String name = phpClass.getName().split("\0", 2)[0];
            ErrorFunctions.raise(context, ErrorFunctions.E_DEPRECATED, Str.of(name + " implements the "
                    + "Serializable interface, which is deprecated. Implement __serialize() and __unserialize() "
                    + "instead (or in addition, if support for old PHP versions is necessary)"),
                    site != null ? SourceLocation.of(site) : SourceLocation.current());
        }
        return phpClass;
    }

    public static final class DeclareClassNode {
        /** ZEND_DECLARE_CLASS: a name taken is an E_COMPILE_ERROR here (zend_bind_class_in_slot). */
        @TruffleBoundary
        public static void declare(PhpContext context, ClassTemplate template, Node site) {
            PhpClass previous = context.findClass(Str.lower(template.getName()));
            if (previous != null) {
                throw redeclared(context, ErrorFunctions.E_COMPILE_ERROR, previous, SourceLocation.of(site), 0);
            }
            context.declareClass(linked(context, template.instantiate(context), site));
        }
    }

    /**
     * A top-level class bound before its file runs, as php-src with opcache binds it when it loads the compiled
     * file (zend_accel_load_script). One the compile linked (boundSlot -1) is copied into the class table, a name
     * taken an E_ERROR at the class's line before any of the file runs (zend_accel_class_hash_copy). The others,
     * after them, are bound if their name is free and their parent declared by now
     * (zend_accel_do_delayed_early_binding), which the class's statement then skips (ZEND_DECLARE_CLASS_DELAYED).
     */
    public static final class EarlyClassNode {
        @TruffleBoundary
        public static boolean bind(PhpContext context, ClassTemplate template, boolean delayed, Node site) {
            PhpClass previous = context.findClass(Str.lower(template.getName()));
            // a parent not bound early (its own checks unresolved) leaves the class to the delayed binding too
            String parent = template.getParentName();
            if (delayed && previous != null || parent != null && context.findClass(Str.lower(parent)) == null) {
                return false;
            }
            PhpClass phpClass = template.instantiate(context, true);
            if (phpClass == null) {
                return false;
            }
            if (previous != null) {
                // the trace starts below the file, which hasn't started
                SourceLocation at = template.getDeclaredAt();
                throw redeclared(context, ErrorFunctions.E_ERROR, previous, at != null ? at : SourceLocation.of(site),
                        1);
            }
            context.declareClass(linked(context, phpClass, site));
            return true;
        }
    }

    /**
     * `new class(...) {...}`: declares the anonymous class the first time the context runs the expression, then
     * instantiates it. The name, as in php-src, is "class@anonymous", a NUL and the declaration's place.
     */
    public static final class NewAnonymousClassNode {
        @TruffleBoundary
        public static void declareOnce(PhpContext context, ClassTemplate template, String lowerName) {
            if (context.findClass(lowerName) == null) {
                context.declareClass(linked(context, template.instantiate(context), null));
            }
        }
    }
}
