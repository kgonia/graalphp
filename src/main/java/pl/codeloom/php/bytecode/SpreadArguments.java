package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.runtime.PhpFunction;

import java.util.ArrayList;
import java.util.List;

/**
 * The arguments of a call that unpacks some (`f($a, ...$b)`), gathered as they are evaluated: their number is known
 * only once the unpacked arrays are (ArgumentsNode.buildWithSpread's list). The call takes them as frame arguments.
 */
public final class SpreadArguments {
    private final List<Object> values = new ArrayList<>();

    /** The list ArgumentsNode's spread helpers add to. */
    List<Object> values() {
        return values;
    }

    @TruffleBoundary
    void add(Object value) {
        values.add(value);
    }

    /** The position the next argument lands on. */
    @TruffleBoundary
    int size() {
        return values.size();
    }

    /** The arguments, without the frame's first slots. */
    @TruffleBoundary
    Object[] toValues() {
        return values.toArray();
    }

    /** The frame arguments of the call: the first slots for the callee's $this, called class and closure. */
    @TruffleBoundary
    Object[] frameArguments() {
        Object[] arguments = new Object[PhpFunction.ARG_FIRST + values.size()];
        for (int i = 0; i < values.size(); i++) {
            arguments[PhpFunction.ARG_FIRST + i] = values.get(i);
        }
        return arguments;
    }
}
