package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.builtins.Args;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
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
    public static final class ConstantObjectNode {
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
     * A deprecated predefined constant (#[\Deprecated], CONST_DEPRECATED): not folded, as php-src doesn't evaluate
     * one at compile time, and every fetch of it raises its deprecation, at the fetch's own line (a parameter's
     * default has no statement around it). In a namespace the name finds the namespace's own constant first, which
     * isn't deprecated.
     */
    public static final class DeprecatedConstNode {
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
        // the common case in one lookup: an evaluated constant of a class whose constants are all public
        Object value = phpClass.plainConstant(name);
        if (value != null) {
            return value;
        }
        checkConstantAccess(phpClass, name, scope);
        return phpClass.getConstant(name);
    }

    /**
     * classConstant in a constant expression (zend_get_class_constant_ex): a constant whose initializer is running
     * for another such lookup is a cycle, an Error naming the class as written.
     */
    public static Object classConstantInExpression(PhpClass phpClass, String name, String written, String scope) {
        checkConstantAccess(phpClass, name, scope);
        return phpClass.getConstantInExpression(name, written);
    }

    /** Before the initializer runs, as php-src checks: the constant exists, and scope may access it. */
    private static void checkConstantAccess(PhpClass phpClass, String name, String scope) {
        if (!phpClass.hasConstant(name)) {
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Undefined constant " + phpClass.getName() + "::" + name);
        }
        if (!phpClass.canAccessConstant(name, scope)) {
            CompilerDirectives.transferToInterpreter();
            throw phpClass.constantAccessError(name);
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

}
