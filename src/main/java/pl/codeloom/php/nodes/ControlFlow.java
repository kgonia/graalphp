package pl.codeloom.php.nodes;

import com.oracle.truffle.api.nodes.ControlFlowException;

/** break, continue and return unwind as exceptions; Graal turns them into jumps inside one compilation unit. */
public final class ControlFlow {
    private ControlFlow() {
    }

    /** `break n`: levels counts the loops (or switches) still to leave, 1 for the innermost. */
    public static final class Break extends ControlFlowException {
        private static final long serialVersionUID = 1L;
        public final int levels;

        public Break(int levels) {
            this.levels = levels;
        }
    }

    public static final class Continue extends ControlFlowException {
        private static final long serialVersionUID = 1L;
        public final int levels;

        public Continue(int levels) {
            this.levels = levels;
        }
    }

    /** A return; value is a PhpRef for functions returning by reference. */
    public static final class Return extends ControlFlowException {
        private static final long serialVersionUID = 1L;
        public final transient Object value;

        public Return(Object value) {
            this.value = value;
        }
    }
}
