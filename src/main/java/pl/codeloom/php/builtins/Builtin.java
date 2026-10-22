package pl.codeloom.php.builtins;

/**
 * A built-in function body. It receives the frame arguments ([this, static, closure, args...]); helpers in
 * {@link Args} read the PHP arguments.
 */
@FunctionalInterface
public interface Builtin {
    Object call(Object[] arguments);
}
