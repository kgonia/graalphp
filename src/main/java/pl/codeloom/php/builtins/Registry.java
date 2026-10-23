package pl.codeloom.php.builtins;

import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.nodes.call.BuiltinRootNode;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpFunction;

/** Declares built-in functions and methods in a context. */
public final class Registry {
    private final PhpContext context;
    private final PhpLanguage language;

    Registry(PhpContext context) {
        this.context = context;
        this.language = context.getLanguage();
    }

    public PhpContext context() {
        return context;
    }

    /** A built-in function with by-value parameters. */
    public void fn(String name, Builtin body) {
        fn(name, "", body);
    }

    /**
     * A built-in function. refMask marks by-reference parameters by position ('1' = by reference), and a trailing
     * '*' makes all further parameters by reference.
     */
    public void fn(String name, String refMask, Builtin body) {
        context.declareFunction(make(name, refMask, body, false));
    }

    /** A built-in method on a built-in class. */
    public void method(PhpClass phpClass, String name, boolean isStatic, Builtin body) {
        PhpFunction method = make(name, "", body, isStatic);
        method.setDeclaringClass(phpClass);
        phpClass.addMethod(method);
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
}
