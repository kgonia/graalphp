package pl.codeloom.php.nodes.stmt;

import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.RepeatingNode;
import pl.codeloom.php.nodes.ControlFlow;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.StmtNode;
import pl.codeloom.php.runtime.Convert;

/**
 * while, do-while, for and switch. `break n` / `continue n` unwind with a level count: each loop (and switch)
 * consumes one level.
 */
public final class LoopNodes {
    private LoopNodes() {
    }

    /** Outcome of running a loop body once. */
    static final int NEXT = 0;
    static final int EXIT = 1;

    /** Runs a loop body; returns NEXT or EXIT, rethrowing break/continue aimed at outer loops. */
    static int runBody(StmtNode body, VirtualFrame frame) {
        try {
            body.execute(frame);
            return NEXT;
        } catch (ControlFlow.Break b) {
            if (b.levels > 1) {
                throw new ControlFlow.Break(b.levels - 1);
            }
            return EXIT;
        } catch (ControlFlow.Continue c) {
            if (c.levels > 1) {
                throw new ControlFlow.Continue(c.levels - 1);
            }
            return NEXT;
        }
    }

    public static final class WhileNode extends StmtNode {
        @Child private ExprNode condition;
        @Child private LoopNode loop;
        private final boolean checkFirst;

        /** checkFirst false makes it do-while. */
        public WhileNode(ExprNode condition, StmtNode body, boolean checkFirst) {
            this.condition = condition;
            this.loop = Truffle.getRuntime().createLoopNode(new WhileBody(condition, body));
            this.checkFirst = checkFirst;
        }

        @Override
        public void execute(VirtualFrame frame) {
            if (checkFirst && !condition.executeBoolean(frame)) {
                return;
            }
            loop.execute(frame);
        }
    }

    /** One iteration of while/do-while after the first test: the body, then the condition. */
    private static final class WhileBody extends Node implements RepeatingNode {
        @Child private ExprNode condition;
        @Child private StmtNode body;

        WhileBody(ExprNode condition, StmtNode body) {
            this.condition = NodeUtil.cloneNode(condition);
            this.body = body;
        }

        @Override
        public boolean executeRepeating(VirtualFrame frame) {
            return runBody(body, frame) != EXIT && condition.executeBoolean(frame);
        }
    }

    /** for (init; cond; step): conditions are comma lists whose last expression decides. */
    public static final class ForNode extends StmtNode {
        @Children private final ExprNode[] init;
        @Child private LoopNode loop;

        public ForNode(ExprNode[] init, ExprNode[] condition, ExprNode[] step, StmtNode body) {
            this.init = init;
            this.loop = Truffle.getRuntime().createLoopNode(new ForBody(condition, step, body));
        }

        @Override
        public void execute(VirtualFrame frame) {
            runAll(init, frame);
            loop.execute(frame);
        }
    }

    /** One iteration of a for loop: test, body, step. */
    private static final class ForBody extends Node implements RepeatingNode {
        @Children private final ExprNode[] condition;
        @Children private final ExprNode[] step;
        @Child private StmtNode body;

        ForBody(ExprNode[] condition, ExprNode[] step, StmtNode body) {
            this.condition = condition;
            this.step = step;
            this.body = body;
        }

        @Override
        public boolean executeRepeating(VirtualFrame frame) {
            if (!test(frame) || runBody(body, frame) == EXIT) {
                return false;
            }
            runAll(step, frame);
            return true;
        }

        @ExplodeLoop
        private boolean test(VirtualFrame frame) {
            boolean result = true;
            for (ExprNode expression : condition) {
                result = expression.executeBoolean(frame);
            }
            return result;
        }
    }

    @ExplodeLoop
    private static void runAll(ExprNode[] expressions, VirtualFrame frame) {
        for (ExprNode expression : expressions) {
            expression.execute(frame);
        }
    }

    /**
     * switch: cases compare loosely (==) in order; matching falls through until break. `continue` inside a
     * switch acts on the switch like `break`, as PHP does.
     */
    public static final class SwitchNode extends StmtNode {
        @Child private ExprNode subject;
        @Children private final ExprNode[] cases;
        @Children private final StmtNode[] bodies;
        private final int defaultIndex;

        /** cases[i] is null for `default`; defaultIndex is its index or -1. */
        public SwitchNode(ExprNode subject, ExprNode[] cases, StmtNode[] bodies, int defaultIndex) {
            this.subject = subject;
            this.cases = cases;
            this.bodies = bodies;
            this.defaultIndex = defaultIndex;
        }

        @Override
        public void execute(VirtualFrame frame) {
            Object value = subject.execute(frame);
            int start = match(frame, value);
            if (start < 0) {
                return;
            }
            try {
                runFrom(frame, start);
            } catch (ControlFlow.Break b) {
                if (b.levels > 1) {
                    throw new ControlFlow.Break(b.levels - 1);
                }
            } catch (ControlFlow.Continue c) {
                if (c.levels > 1) {
                    throw new ControlFlow.Continue(c.levels - 1);
                }
            }
        }

        @ExplodeLoop
        private int match(VirtualFrame frame, Object value) {
            for (int i = 0; i < cases.length; i++) {
                if (cases[i] != null && Convert.looseEquals(value, cases[i].execute(frame))) {
                    return i;
                }
            }
            return defaultIndex;
        }

        @ExplodeLoop
        private void runFrom(VirtualFrame frame, int start) {
            for (int i = 0; i < bodies.length; i++) {
                if (i >= start) {
                    bodies[i].execute(frame);
                }
            }
        }
    }
}
