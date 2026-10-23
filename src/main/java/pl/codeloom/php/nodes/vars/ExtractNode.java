package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * `extract($array, $flags, $prefix)`: the array's string keys become variables of the calling scope. A
 * function's variables are its frame slots by name (see {@link DynamicCompactNode}); a name the function never
 * mentions has no slot and nothing can read it, so it is skipped. At the top level the variables are globals.
 * EXTR_REFS binds values, not references.
 */
public final class ExtractNode extends ExprNode {
    private static final Pattern NAME = Pattern.compile("[a-zA-Z_\\x80-\\xff][a-zA-Z0-9_\\x80-\\xff]*");
    private static final long OVERWRITE = 0;
    private static final long SKIP = 1;
    private static final long PREFIX_SAME = 2;
    private static final long PREFIX_ALL = 3;
    private static final long PREFIX_INVALID = 4;
    private static final long IF_EXISTS = 6;
    private static final long PREFIX_IF_EXISTS = 5;

    @Child private ExprNode array;
    @Child private ExprNode flags;
    @Child private ExprNode prefix;
    /** Name to frame slot, or null at the top level. */
    private final Map<String, Integer> slots;

    public ExtractNode(ExprNode array, ExprNode flags, ExprNode prefix, Map<String, Integer> slots) {
        this.array = array;
        this.flags = flags;
        this.prefix = prefix;
        this.slots = slots;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object source = array.execute(frame);
        long mode = flags == null ? OVERWRITE : Convert.toLong(flags.execute(frame)) & 0xff;
        String prefixText = prefix == null ? "" : Str.java(Convert.toStr(prefix.execute(frame)));
        if (!(source instanceof PhpArray values)) {
            throw new PhpError("TypeError", "extract(): Argument #1 ($array) must be of type array");
        }
        return extract(frame.materialize(), values, mode, prefixText, slots,
                slots == null ? PhpContext.get(this) : null);
    }

    @TruffleBoundary
    private static long extract(MaterializedFrame frame, PhpArray values, long mode, String prefix,
                                Map<String, Integer> slots, PhpContext context) {
        long count = 0;
        for (int p = values.firstPosition(); p >= 0; p = values.nextPosition(p)) {
            Object key = values.keyAt(p);
            String name = Str.java(Convert.toStr(key));
            boolean exists = exists(frame, name, slots, context);
            if (mode == SKIP && exists || mode == IF_EXISTS && !exists || mode == PREFIX_IF_EXISTS && !exists) {
                continue;
            }
            if (mode == PREFIX_ALL || mode == PREFIX_SAME && exists || mode == PREFIX_IF_EXISTS
                    || mode == PREFIX_INVALID && !(key instanceof com.oracle.truffle.api.strings.TruffleString
                    && NAME.matcher(name).matches())) {
                name = prefix + "_" + name;
            }
            if (!NAME.matcher(name).matches() || name.equals("this")) {
                continue;
            }
            Object value = values.valueAt(p);
            if (slots == null) {
                if (value instanceof PhpArray array) {
                    array.addHolder();
                }
                context.globalRef(name).set(value);
                count++;
            } else {
                Integer slot = slots.get(name);
                if (slot != null) {
                    write(frame, slot, value);
                }
                count++;
            }
        }
        return count;
    }

    private static boolean exists(MaterializedFrame frame, String name, Map<String, Integer> slots,
                                  PhpContext context) {
        if (slots == null) {
            return context.findGlobal(name) != null;
        }
        Integer slot = slots.get(name);
        return slot != null && LocalVarNode.raw(frame, slot) != null;
    }

    /** As LocalVarNode writes: through the slot's reference if it holds one, else into the slot. */
    private static void write(MaterializedFrame frame, int slot, Object value) {
        Object old = LocalVarNode.raw(frame, slot);
        if (old instanceof PhpRef ref) {
            ref.set(value);
            return;
        }
        if (value instanceof PhpArray array) {
            array.addHolder();
        }
        FrameSlots.set(frame, slot, value);
        if (old instanceof PhpArray array) {
            array.removeHolder();
        }
    }
}
