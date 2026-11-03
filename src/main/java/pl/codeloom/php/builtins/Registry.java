package pl.codeloom.php.builtins;

import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.nodes.call.BuiltinRootNode;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.Str;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the language-level built-in functions and methods, once per language: every context declares the same
 * PhpFunction objects, so their call targets (and compiled code) are shared.
 */
public final class Registry {
    /**
     * The argument counts php-src checks each internal function and method against (its arginfo), lower-case
     * "name" or "class::name" to {required, maximum}: arity.txt, made by tools/builtin-arity.php. An inherited
     * method's entry names the class declaring it, as php-src's messages do.
     */
    private static final Map<String, int[]> ARITY = new HashMap<>();
    private static final Map<String, String> DECLARED = new HashMap<>();

    static {
        String table = new String(StringFunctions.resource("arity.txt"), StandardCharsets.US_ASCII);
        for (String line : table.split("\n")) {
            String[] parts = line.split(" ");
            int max = Integer.parseInt(parts[2]);
            ARITY.put(Str.lower(parts[0]), new int[] {Integer.parseInt(parts[1]), max < 0 ? Integer.MAX_VALUE : max});
            if (parts.length > 3) {
                DECLARED.put(Str.lower(parts[0]), parts[3]);
            }
        }
    }

    /**
     * php-src's {required, maximum} argument counts of an internal function ("name") or method ("Class::name"),
     * or null for one the reference PHP doesn't have: its calls aren't checked.
     */
    public static int[] arity(String name) {
        return ARITY.get(Str.lower(name));
    }

    /** The name php-src's messages give a function or method: "Class::name" of the class declaring it. */
    public static String declaredName(String name) {
        return DECLARED.getOrDefault(Str.lower(name), name);
    }

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
     * A built-in function. refMask marks by-reference parameters by position ('1' = by reference, '2' = by
     * reference if a variable is passed: ZEND_SEND_PREFER_REF), and a trailing '*' makes all further parameters by
     * reference (prefer-ref after a '2').
     */
    public void fn(String name, String refMask, Builtin body) {
        functions.add(make(name, name, refMask, body, false, false));
    }

    public void fn(String name, String refMask, ContextBuiltin body) {
        fn(name, refMask, (Builtin) arguments -> body.call(PhpContext.get(null), arguments));
    }

    /** A built-in method, declared by className in php-src, for built-in classes to add in each context. */
    public PhpFunction method(String className, String name, boolean isStatic, Builtin body) {
        return make(className + "::" + name, name, "", body, isStatic, true);
    }

    private PhpFunction make(String key, String name, String refMask, Builtin body, boolean isStatic, boolean method) {
        boolean variadicRef = refMask.endsWith("*");
        String mask = variadicRef ? refMask.substring(0, refMask.length() - 1) : refMask;
        int count = mask.length() + (variadicRef ? 1 : 0);
        PhpFunction.Param[] params = new PhpFunction.Param[count];
        for (int i = 0; i < mask.length(); i++) {
            params[i] = new PhpFunction.Param("arg" + i, mask.charAt(i) != '0', false, Boolean.TRUE, null, true,
                    null);
        }
        if (variadicRef) {
            params[count - 1] = new PhpFunction.Param("rest", true, true, Boolean.TRUE, null, true, null);
        }
        int[] arity = arity(key);
        int min = arity == null ? 0 : arity[0];
        int max = arity == null ? Integer.MAX_VALUE : arity[1];
        PhpFunction function = new PhpFunction(name, params, false, isStatic, true, false,
                () -> new BuiltinRootNode(language, name, body, method, min, max).getCallTarget());
        function.setBuiltin(body);
        if (mask.indexOf('2') >= 0) {
            function.setPreferRef();
        }
        return function;
    }

    /** A built-in body that receives the calling context; Registry looks it up at each call. */
    @FunctionalInterface
    public interface ContextBuiltin {
        Object call(PhpContext context, Object[] arguments);
    }
}
