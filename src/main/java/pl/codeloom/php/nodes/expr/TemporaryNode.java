package pl.codeloom.php.nodes.expr;

import com.oracle.truffle.api.frame.VirtualFrame;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpRef;

/**
 * A value evaluated into a hidden slot before the expression that uses it ({@link Read}): the object of a `?->`
 * (ZEND_JMP_NULL: nullsafe, null short-circuits the rest of the chain, whose value is null), the operand of `|>`.
 */
public final class TemporaryNode extends ExprNode {
    @Child private ExprNode object;
    @Child private ExprNode chain;
    private final int slot;
    private final boolean nullsafe;

    public TemporaryNode(ExprNode object, ExprNode chain, int slot, boolean nullsafe) {
        this.object = object;
        this.chain = chain;
        this.slot = slot;
        this.nullsafe = nullsafe;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object value = object.execute(frame);
        if (nullsafe && value == PhpNull.NULL) {
            return PhpNull.NULL;
        }
        frame.setObject(slot, value);
        return chain.execute(frame);
    }

    /** The value, where the expression uses it. */
    public static final class Read extends ExprNode {
        private final int slot;

        public Read(int slot) {
            this.slot = slot;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            return frame.getObject(slot);
        }
    }

    /**
     * A `?->` chain in isset(), empty() and `??` (BP_VAR_IS): the object and the chain are read as isset() reads them,
     * and short-circuiting is "not set". Never written: php-src refuses `?->` in a write context at compile time.
     */
    public static final class IfSet extends LValueNode {
        @Child private ExprNode object;
        @Child private ExprNode chain;
        private final int slot;

        public IfSet(ExprNode object, ExprNode chain, int slot) {
            this.object = object;
            this.chain = chain;
            this.slot = slot;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object value = object.execute(frame);
            if (value == PhpNull.NULL) {
                return PhpNull.NULL;
            }
            frame.setObject(slot, value);
            return chain.execute(frame);
        }

        @Override
        public Object readIfSet(VirtualFrame frame) {
            Object value = object instanceof LValueNode place ? place.readIfSet(frame) : object.execute(frame);
            if (value == null || value == PhpNull.NULL) {
                return null;
            }
            frame.setObject(slot, value);
            return chain instanceof LValueNode place ? place.readIfSet(frame) : chain.execute(frame);
        }

        @Override
        public boolean isSet(VirtualFrame frame) {
            Object value = readIfSet(frame);
            return value != null && value != PhpNull.NULL;
        }

        @Override
        public boolean isPure() {
            return false;
        }

        @Override
        public void write(VirtualFrame frame, Object value) {
            throw new IllegalStateException("?-> written");
        }

        @Override
        public PhpRef makeRef(VirtualFrame frame) {
            throw new IllegalStateException("?-> written");
        }

        @Override
        public void bindRef(VirtualFrame frame, PhpRef ref) {
            throw new IllegalStateException("?-> written");
        }

        @Override
        public PhpArray arrayForWrite(VirtualFrame frame) {
            throw new IllegalStateException("?-> written");
        }

        @Override
        public void unset(VirtualFrame frame) {
            throw new IllegalStateException("?-> written");
        }
    }
}
