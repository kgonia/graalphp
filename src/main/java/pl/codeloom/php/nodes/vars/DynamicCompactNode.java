package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.util.Map;

/**
 * `compact($names)` with names computed at run time (WP_Query's `compact($pieces)`). A function's variables are
 * its frame slots, looked up by name in the scope's map (complete once the body is translated); at the top level
 * they are the globals, or those of the call the file is included from ({@link IncludeScope}). Rare code: the frame is materialized and the lookup runs behind a boundary.
 */
public final class DynamicCompactNode extends ExprNode {
    @Children private final ExprNode[] arguments;
    /** Name to frame slot, or null at the top level. */
    private final Map<String, Integer> slots;

    public DynamicCompactNode(ExprNode[] arguments, Map<String, Integer> slots) {
        this.arguments = arguments;
        this.slots = slots;
    }

    @Override
    @ExplodeLoop
    public Object execute(VirtualFrame frame) {
        Object[] names = new Object[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            names[i] = arguments[i].execute(frame);
        }
        IncludeScope included = slots == null ? IncludeScope.of(frame) : null;
        return compact(this, frame.materialize(), names, slots, included,
                slots == null ? PhpContext.get(this) : null);
    }

    @TruffleBoundary
    private static PhpArray compact(Node site, MaterializedFrame frame, Object[] names, Map<String, Integer> slots,
                                    IncludeScope included, PhpContext context) {
        PhpArray result = new PhpArray();
        for (Object name : names) {
            add(site, result, frame, name, slots, included, context);
        }
        return result;
    }

    private static void add(Node site, PhpArray result, MaterializedFrame frame, Object name,
                            Map<String, Integer> slots, IncludeScope included, PhpContext context) {
        if (name instanceof PhpArray nested) {
            for (int p = nested.firstPosition(); p >= 0; p = nested.nextPosition(p)) {
                add(site, result, frame, nested.valueAt(p), slots, included, context);
            }
            return;
        }
        String variable = Str.java(Convert.toStr(name));
        Object value = included != null ? included.get(variable)
                : slots == null ? global(context, variable) : local(frame, slots.get(variable));
        if (value != null) {
            result.put(ArrayKey.of(Str.of(variable)), value);
        } else {
            undefined(site, variable);
        }
    }

    /** php-src's warning for a name compact() finds no variable of. */
    @TruffleBoundary
    static void undefined(Node site, String name) {
        ErrorFunctions.raise(PhpContext.get(site), ErrorFunctions.E_WARNING,
                Str.of("compact(): Undefined variable $" + name), SourceLocation.of(site));
    }

    private static Object global(PhpContext context, String name) {
        PhpRef ref = context.findGlobal(name);
        return ref == null ? null : ref.get();
    }

    /** The slot's value, or null if the variable is undefined (as {@link LocalVarNode} reads it). */
    private static Object local(MaterializedFrame frame, Integer slot) {
        if (slot == null) {
            return null;
        }
        if (frame.isLong(slot)) {
            return frame.getLong(slot);
        }
        if (frame.isDouble(slot)) {
            return frame.getDouble(slot);
        }
        if (!frame.isObject(slot) || frame.getObject(slot) == null) {
            return null;
        }
        Object value = frame.getObject(slot);
        return value instanceof PhpRef ref ? ref.get() : value;
    }
}
