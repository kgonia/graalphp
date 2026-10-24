package pl.codeloom.php.nodes.stmt;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import pl.codeloom.php.nodes.StmtNode;
import pl.codeloom.php.runtime.PhpError;

/**
 * goto. PHP jumps only to a label in the same statement list or an enclosing one (not into a loop or switch), so
 * a goto unwinds as an exception to the statement list holding its label, which runs again from the label's
 * statement: forward out of a switch, or backward as a loop.
 */
public final class GotoNodes {
    private GotoNodes() {
    }

    /** Unwinds to the statement list that declares label. */
    public static final class Jump extends ControlFlowException {
        private static final long serialVersionUID = 1L;
        final String label;

        Jump(String label) {
            this.label = label;
        }
    }

    public static final class GotoNode extends StmtNode {
        private final String label;

        public GotoNode(String label) {
            this.label = label.intern();
        }

        @Override
        public void execute(VirtualFrame frame) {
            throw new Jump(label);
        }
    }

    /** A statement list with labels: labels[i] names the statement it precedes (statements[targets[i]]). */
    public static final class LabeledBlockNode extends StmtNode {
        @Children private final StmtNode[] statements;
        @CompilationFinal(dimensions = 1) private final String[] labels;
        @CompilationFinal(dimensions = 1) private final int[] targets;

        public LabeledBlockNode(StmtNode[] statements, String[] labels, int[] targets) {
            this.statements = statements;
            this.labels = labels;
            this.targets = targets;
            for (int i = 0; i < labels.length; i++) {
                labels[i] = labels[i].intern();
            }
        }

        @Override
        public void execute(VirtualFrame frame) {
            int start = 0;
            while (true) {
                try {
                    runFrom(frame, start);
                    return;
                } catch (Jump jump) {
                    start = target(jump);
                }
            }
        }

        @ExplodeLoop
        private void runFrom(VirtualFrame frame, int start) {
            int i = 0;
            try {
                for (; i < statements.length; i++) {
                    if (i >= start) {
                        statements[i].execute(frame);
                    }
                }
            } catch (PhpError e) {
                surfaced(e, statements[i]);
                throw e;
            }
        }

        @ExplodeLoop
        private int target(Jump jump) {
            for (int i = 0; i < labels.length; i++) {
                if (labels[i] == jump.label) {
                    return targets[i];
                }
            }
            throw jump;
        }
    }
}
