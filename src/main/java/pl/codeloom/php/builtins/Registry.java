package pl.codeloom.php.builtins;

import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.nodes.call.BuiltinRootNode;
import pl.codeloom.php.runtime.PhpFunction;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the language-level built-in functions and methods, once per language: every context declares the same
 * PhpFunction objects, so their call targets (and compiled code) are shared.
 */
public final class Registry {
    private final PhpLanguage language;
    private final List<PhpFunction> functions = new ArrayList<>();

    Registry(PhpLanguage language) {
        this.language = language;
    }

    List<PhpFunction> functions() {
        return functions;
    }

    /** A built-in function with by-value parameters. */
    public void fn(String name, Builtin body) {
        fn(name, "", body);
    }

    /** A built-in function that needs the calling context (its output, declarations or per-context state). */
    public void fn(String name, ContextBuiltin body) {
        fn(name, "", body);
    }

    /**
     * A built-in function. refMask marks by-reference parameters by position ('1' = by reference), and a trailing
     * '*' makes all further parameters by reference.
     */
    public void fn(String name, String refMask, Builtin body) {
        functions.add(make(name, refMask, body, false));
    }

    public void fn(String name, String refMask, ContextBuiltin body) {
        fn(name, refMask, (Builtin) arguments -> body.call(PhpContext.get(null), arguments));
    }

    /** A built-in method, for built-in classes to add in each context. */
    public PhpFunction method(String name, boolean isStatic, Builtin body) {
        return make(name, "", body, isStatic);
    }

    private PhpFunction make(String name, String refMask, Builtin body, boolean isStatic) {
        boolean variadicRef = refMask.endsWith("*");
        String mask = variadicRef ? refMask.substring(0, refMask.length() - 1) : refMask;
        int count = mask.length() + (variadicRef ? 1 : 0);
        PhpFunction.Param[] params = new PhpFunction.Param[count];
        for (int i = 0; i < mask.length(); i++) {
            params[i] = new PhpFunction.Param("arg" + i, mask.charAt(i) == '1', false, Boolean.TRUE, (char) 0, true);
        }
        if (variadicRef) {
            params[count - 1] = new PhpFunction.Param("rest", true, true, Boolean.TRUE, (char) 0, true);
        }
        PhpFunction function = new PhpFunction(name, params, false, isStatic, true, false,
                () -> new BuiltinRootNode(language, name, body).getCallTarget());
        function.setBuiltin(body);
        return function;
    }

    /** A built-in body that receives the calling context; Registry looks it up at each call. */
    @FunctionalInterface
    public interface ContextBuiltin {
        Object call(PhpContext context, Object[] arguments);
    }
}
