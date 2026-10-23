package pl.codeloom.php.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpRef;

/**
 * A place that can be assigned: a variable, array element, property or static property. execute() reads it
 * (null when missing, as PHP does after its warning; this runtime emits no warnings).
 */
public abstract class LValueNode extends ExprNode {
    public abstract void write(VirtualFrame frame, Object value);

    /** Writes an int; places that can store it unboxed (locals, properties) override this. */
    public void writeLong(VirtualFrame frame, long value) {
        write(frame, value);
    }

    /** Writes a float; places that can store it unboxed (locals) override this. */
    public void writeDouble(VirtualFrame frame, double value) {
        write(frame, value);
    }

    /** The place's reference, converting it into one if needed (`&$x`, by-reference arguments). */
    public abstract PhpRef makeRef(VirtualFrame frame);

    /** Binds the place to an existing reference (`$x = &$y`). */
    public abstract void bindRef(VirtualFrame frame, PhpRef ref);

    /**
     * The array stored here, exclusive to this place so it can be modified in place: copied if shared, created
     * if the place is unset or null. Throws for scalars that can't become arrays.
     */
    public abstract PhpArray arrayForWrite(VirtualFrame frame);

    public abstract void unset(VirtualFrame frame);

    /** isset(): the place exists and isn't null, evaluated without side effects on the container. */
    public abstract boolean isSet(VirtualFrame frame);

    /** True when evaluating the place twice has no side effects (compound assignment re-reads it). */
    public abstract boolean isPure();

    /** The value if isset() would be true, else null (Java null): `??` in one lookup where possible. */
    public Object readIfSet(VirtualFrame frame) {
        return isSet(frame) ? execute(frame) : null;
    }
}
