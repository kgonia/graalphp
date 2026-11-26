package pl.codeloom.php.ir;

import java.util.function.Function;

/** zend_exception_set_previous: find the append point without linking either chain to itself. */
public final class PhpExceptionChain {
    private PhpExceptionChain() {}

    public static <T> T appendPoint(T thrown, T pending, Function<T, T> previous) {
        for (T tail = thrown; tail != null; tail = previous.apply(tail)) {
            for (T ancestor = pending; ancestor != null; ancestor = previous.apply(ancestor)) {
                if (tail == ancestor) return null;
            }
            if (previous.apply(tail) == null) return tail;
        }
        return null;
    }
}
