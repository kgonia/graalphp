package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.MaterializedFrame;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpRef;

import java.util.HashMap;
import java.util.Map;

/**
 * A function call's variables by name, as the files it includes see them: php-src runs an included file in the
 * including call's symbol table (zend_attach_symbol_table), so the file's top-level variables are the function's.
 * The function's variables are its frame slots, reached through the materialized frame with the same semantics as
 * its own code ({@link LocalVarNode}); a name it has no slot for lives here, in the call's table, where later
 * includes of the same call find it. Rare code: everything runs behind boundaries.
 */
public final class IncludeScope {
    /** Where a file root's frame arguments carry the scope it runs in (a file has no closure); null: global. */
    public static final int ARG = PhpFunction.ARG_CLOSURE;

    private final MaterializedFrame frame;
    private final Map<String, Integer> slots;
    /** The including code's class scope (lowercase), which the included code has too; null outside classes. */
    private final String classScope;
    private final Map<String, LocalVarNode> places = new HashMap<>();
    /** Variables of the call that the function has no slot for. */
    private final Map<String, PhpRef> others = new HashMap<>();

    public IncludeScope(MaterializedFrame frame, Map<String, Integer> slots, String classScope) {
        this.frame = frame;
        this.slots = slots;
        this.classScope = classScope;
    }

    /** php-src gives an included op_array the including function's scope (ZEND_INCLUDE_OR_EVAL). */
    public String classScope() {
        return classScope;
    }

    /** The scope a file's top-level code runs in: null in the global scope. */
    public static IncludeScope of(Frame frame) {
        return frame.getArguments()[ARG] instanceof IncludeScope scope ? scope : null;
    }

    /** The function's variable, or null if it has no slot for the name. */
    private LocalVarNode place(String name) {
        LocalVarNode place = places.get(name);
        if (place == null) {
            Integer slot = slots.get(name);
            if (slot == null) {
                return null;
            }
            place = new LocalVarNode(slot, name);
            places.put(name, place);
        }
        return place;
    }

    /** The value, or null (Java) if the variable is undefined. */
    @TruffleBoundary
    public Object get(String name) {
        LocalVarNode place = place(name);
        if (place != null) {
            return place.isDefined(frame) ? place.execute(frame) : null;
        }
        PhpRef ref = others.get(name);
        return ref == null ? null : ref.get();
    }

    @TruffleBoundary
    public boolean isDefined(String name) {
        LocalVarNode place = place(name);
        return place != null ? place.isDefined(frame) : others.containsKey(name);
    }

    @TruffleBoundary
    public void set(String name, Object value) {
        LocalVarNode place = place(name);
        if (place != null) {
            place.write(frame, value);
        } else {
            ref(name).set(value);
        }
    }

    @TruffleBoundary
    public PhpRef ref(String name) {
        LocalVarNode place = place(name);
        if (place != null) {
            return place.makeRef(frame);
        }
        PhpRef ref = others.get(name);
        if (ref == null) {
            ref = new PhpRef(PhpNull.NULL);
            ref.bind();
            others.put(name, ref);
        }
        return ref;
    }

    @TruffleBoundary
    public void bind(String name, PhpRef ref) {
        LocalVarNode place = place(name);
        if (place != null) {
            place.bindRef(frame, ref);
            return;
        }
        ref.bind();
        PhpRef old = others.put(name, ref);
        if (old != null) {
            old.unbind();
        }
    }

    @TruffleBoundary
    public void unset(String name) {
        LocalVarNode place = place(name);
        if (place != null) {
            place.unset(frame);
            return;
        }
        PhpRef old = others.remove(name);
        if (old != null) {
            old.unbind();
        }
    }

    /** As {@link LocalVarNode#arrayForWrite}: null for an object (ArrayAccess), which the caller handles. */
    @TruffleBoundary
    public PhpArray arrayForWrite(String name) {
        LocalVarNode place = place(name);
        if (place != null) {
            return place.arrayForWrite(frame);
        }
        return Places.refArrayForWrite(ref(name));
    }
}
