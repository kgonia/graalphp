package pl.codeloom.php.nodes.stmt;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.MaterializedFrame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ControlFlowException;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.RootNode;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.nodes.ConditionNode;
import pl.codeloom.php.nodes.ControlFlow;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.nodes.StmtNode;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpException;
import pl.codeloom.php.runtime.PhpGenerator;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;

/**
 * Generators. Calling a generator function binds its parameters and returns a Generator holding the frame; the
 * body runs on that frame from {@link BodyRootNode}. A yield records its key and value and unwinds to the root
 * with {@link Yield}. Resuming runs the body again: every statement on the path to a yield is a resumable variant
 * that keeps its progress in a hidden frame slot (which statement of a block, which branch of an if, whether a
 * loop is inside its body), so it skips what already ran and re-enters the statement that yielded. That
 * statement's expression is evaluated again, and the yield there, seeing its own slot set, returns the sent value
 * instead of yielding: side effects in the same statement before the yield run twice. A slot is reset when its
 * statement completes or exits other than by a yield.
 */
public final class GeneratorNodes {
    private GeneratorNodes() {
    }

    /** Unwinds a yield to the body's root. */
    public static final class Yield extends ControlFlowException {
        static final Yield INSTANCE = new Yield();
    }

    /** A slot's progress value: 0 when the statement isn't suspended. */
    static long state(Frame frame, int slot) {
        return frame.isLong(slot) ? frame.getLong(slot) : 0;
    }

    /** Runs a generator body on the frame the call made (the first argument). */
    public static final class BodyRootNode extends RootNode {
        @Child private StmtNode body;
        private final String name;

        /** As FunctionRootNode's: the declaring class's lowercase name, or null. */
        private final String scopeName;

        public BodyRootNode(PhpLanguage language, FrameDescriptor descriptor, StmtNode body, String name,
                            String scopeName) {
            super(language, descriptor);
            this.body = body;
            this.name = name;
            this.scopeName = scopeName;
        }

        public String getScopeName() {
            return scopeName;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            MaterializedFrame generatorFrame = (MaterializedFrame) frame.getArguments()[0];
            try {
                body.execute(generatorFrame);
                return PhpNull.NULL;
            } catch (ControlFlow.Return ret) {
                return ret.value;
            } catch (Yield yield) {
                return PhpGenerator.SUSPENDED;
            }
        }

        @Override
        public boolean isCloningAllowed() {
            return true;
        }

        @Override
        public String getName() {
            return name;
        }
    }

    /** `yield`, `yield value`, `yield key => value`: the sent value once resumed. */
    public static final class YieldNode extends ExprNode {
        @Child private ExprNode key;
        @Child private ExprNode value;
        private final int stateSlot;
        private final int generatorSlot;

        public YieldNode(ExprNode key, ExprNode value, int stateSlot, int generatorSlot) {
            this.key = key;
            this.value = value;
            this.stateSlot = stateSlot;
            this.generatorSlot = generatorSlot;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            PhpGenerator generator = (PhpGenerator) frame.getObject(generatorSlot);
            if (state(frame, stateSlot) == 1) {
                frame.setLong(stateSlot, 0);
                RuntimeException thrown = generator.takeThrow();
                if (thrown != null) {
                    throw thrown;
                }
                return generator.takeSent();
            }
            Object yieldedKey = key == null ? null : key.execute(frame);
            Object yieldedValue = value == null ? PhpNull.NULL : value.execute(frame);
            generator.yielded(yieldedKey, yieldedValue);
            frame.setLong(stateSlot, 1);
            throw Yield.INSTANCE;
        }
    }

    /** `yield from` an array or a Traversable (a Generator included): yields its elements, then its return value. */
    public static final class YieldFromNode extends ExprNode {
        @Child private ExprNode source;
        private final int stateSlot;
        private final int sourceSlot;
        private final int positionSlot;
        private final int generatorSlot;

        public YieldFromNode(ExprNode source, int stateSlot, int sourceSlot, int positionSlot, int generatorSlot) {
            this.source = source;
            this.stateSlot = stateSlot;
            this.sourceSlot = sourceSlot;
            this.positionSlot = positionSlot;
            this.generatorSlot = generatorSlot;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            PhpGenerator generator = (PhpGenerator) frame.getObject(generatorSlot);
            Object inner;
            if (state(frame, stateSlot) == 0) {
                inner = source.execute(frame);
                if (inner instanceof PhpArray array) {
                    frame.setLong(positionSlot, array.firstPosition());
                } else if (inner instanceof PhpObject object) {
                    inner = iterator(object);
                    ((PhpObject) inner).callMethod("rewind");
                } else {
                    throw new PhpError("Can use \"yield from\" only with arrays and Traversables");
                }
                frame.setObject(sourceSlot, inner);
            } else {
                inner = frame.getObject(sourceSlot);
                generator.takeSent();
                if (inner instanceof PhpArray array) {
                    frame.setLong(positionSlot, array.nextPosition((int) frame.getLong(positionSlot)));
                } else {
                    ((PhpObject) inner).callMethod("next");
                }
            }
            if (inner instanceof PhpArray array) {
                int position = (int) frame.getLong(positionSlot);
                if (position >= 0) {
                    generator.yielded(array.keyAt(position), array.valueAt(position));
                    frame.setLong(stateSlot, 1);
                    throw Yield.INSTANCE;
                }
                frame.setLong(stateSlot, 0);
                return PhpNull.NULL;
            }
            PhpObject iterator = (PhpObject) inner;
            if (Convert.toBool(iterator.callMethod("valid"))) {
                generator.yielded(ArrayKey.of(iterator.callMethod("key")), iterator.callMethod("current"));
                frame.setLong(stateSlot, 1);
                throw Yield.INSTANCE;
            }
            frame.setLong(stateSlot, 0);
            return iterator.getInternal() instanceof PhpGenerator delegate ? delegate.getReturn() : PhpNull.NULL;
        }

        private static PhpObject iterator(PhpObject traversable) {
            Object current = traversable;
            while (current instanceof PhpObject object && object.getPhpClass().isSubclassOf("iteratoraggregate")) {
                current = object.callMethod("getiterator");
            }
            if (!(current instanceof PhpObject object) || !object.getPhpClass().isSubclassOf("iterator")) {
                throw new PhpError("Can use \"yield from\" only with arrays and Traversables");
            }
            return object;
        }
    }

    /** A statement list: the slot holds the index of the statement that yielded. */
    public static final class BlockNode extends StmtNode {
        @Children private final StmtNode[] statements;
        private final int slot;

        public BlockNode(StmtNode[] statements, int slot) {
            this.statements = statements;
            this.slot = slot;
        }

        @Override
        public void execute(VirtualFrame frame) {
            boolean yielded = false;
            try {
                runFrom(frame, (int) state(frame, slot));
            } catch (Yield y) {
                yielded = true;
                throw y;
            } finally {
                if (!yielded) {
                    frame.setLong(slot, 0);
                }
            }
        }

        @ExplodeLoop
        private void runFrom(VirtualFrame frame, int start) {
            for (int i = 0; i < statements.length; i++) {
                if (i >= start) {
                    frame.setLong(slot, i);
                    statements[i].execute(frame);
                }
            }
        }
    }

    /** if/else: the slot holds the branch taken (1 then, 2 else), so the condition isn't evaluated again. */
    public static final class IfNode extends StmtNode {
        @Child private ExprNode condition;
        @Child private StmtNode then;
        @Child private StmtNode otherwise;
        private final int slot;

        public IfNode(ExprNode condition, StmtNode then, StmtNode otherwise, int slot) {
            this.condition = ConditionNode.of(condition);
            this.then = then;
            this.otherwise = otherwise;
            this.slot = slot;
        }

        @Override
        public void execute(VirtualFrame frame) {
            boolean yielded = false;
            try {
                long branch = state(frame, slot);
                if (branch == 0) {
                    branch = condition.executeCondition(frame) ? 1 : 2;
                    frame.setLong(slot, branch);
                }
                StmtNode chosen = branch == 1 ? then : otherwise;
                if (chosen != null) {
                    chosen.execute(frame);
                }
            } catch (Yield y) {
                yielded = true;
                throw y;
            } finally {
                if (!yielded) {
                    frame.setLong(slot, 0);
                }
            }
        }
    }

    /** while and do-while: the slot is 1 while inside the body, so resuming skips the condition. */
    public static final class WhileNode extends StmtNode {
        @Child private ExprNode condition;
        @Child private StmtNode body;
        private final boolean checkFirst;
        private final int slot;

        public WhileNode(ExprNode condition, StmtNode body, boolean checkFirst, int slot) {
            this.condition = ConditionNode.of(condition);
            this.body = body;
            this.checkFirst = checkFirst;
            this.slot = slot;
        }

        @Override
        public void execute(VirtualFrame frame) {
            boolean yielded = false;
            try {
                boolean resuming = state(frame, slot) == 1;
                boolean first = !resuming;
                while (true) {
                    if (!resuming && (checkFirst || !first) && !condition.executeCondition(frame)) {
                        break;
                    }
                    resuming = false;
                    first = false;
                    frame.setLong(slot, 1);
                    if (LoopNodes.runBody(body, frame) == LoopNodes.EXIT) {
                        break;
                    }
                    frame.setLong(slot, 0);
                }
            } catch (Yield y) {
                yielded = true;
                throw y;
            } finally {
                if (!yielded) {
                    frame.setLong(slot, 0);
                }
            }
        }
    }

    /** for (init; cond; step): the slot is 1 while inside the body; init runs only on a fresh start. */
    public static final class ForNode extends StmtNode {
        @Children private final ExprNode[] init;
        @Children private final ExprNode[] condition;
        @Children private final ExprNode[] step;
        @Child private StmtNode body;
        private final int slot;

        public ForNode(ExprNode[] init, ExprNode[] condition, ExprNode[] step, StmtNode body, int slot) {
            this.init = init;
            this.condition = condition;
            if (condition.length > 0) {
                condition[condition.length - 1] = ConditionNode.of(condition[condition.length - 1]);
            }
            this.step = step;
            this.body = body;
            this.slot = slot;
        }

        @Override
        public void execute(VirtualFrame frame) {
            boolean yielded = false;
            try {
                boolean resuming = state(frame, slot) == 1;
                if (!resuming) {
                    LoopNodes.runAll(init, frame);
                }
                while (true) {
                    if (!resuming && !test(frame)) {
                        break;
                    }
                    resuming = false;
                    frame.setLong(slot, 1);
                    if (LoopNodes.runBody(body, frame) == LoopNodes.EXIT) {
                        break;
                    }
                    frame.setLong(slot, 0);
                    LoopNodes.runAll(step, frame);
                }
            } catch (Yield y) {
                yielded = true;
                throw y;
            } finally {
                if (!yielded) {
                    frame.setLong(slot, 0);
                }
            }
        }

        @ExplodeLoop
        private boolean test(VirtualFrame frame) {
            int last = condition.length - 1;
            for (int i = 0; i < last; i++) {
                condition[i].execute(frame);
            }
            return last < 0 || condition[last].executeCondition(frame);
        }
    }

    /**
     * foreach by value over an array (a snapshot, as ForeachNode) or an Iterator: the subject and the position
     * live in hidden slots, the state slot is 1 while inside the body.
     */
    public static final class ForeachNode extends StmtNode {
        @Child private ExprNode subject;
        @Child private LValueNode keyTarget;
        @Child private LValueNode valueTarget;
        @Child private StmtNode body;
        private final int slot;
        private final int subjectSlot;
        private final int positionSlot;

        public ForeachNode(ExprNode subject, LValueNode keyTarget, LValueNode valueTarget, StmtNode body, int slot,
                           int subjectSlot, int positionSlot) {
            this.subject = subject;
            this.keyTarget = keyTarget;
            this.valueTarget = valueTarget;
            this.body = body;
            this.slot = slot;
            this.subjectSlot = subjectSlot;
            this.positionSlot = positionSlot;
        }

        @Override
        public void execute(VirtualFrame frame) {
            boolean yielded = false;
            try {
                boolean resuming = state(frame, slot) == 1;
                if (!resuming && !begin(frame)) {
                    return;
                }
                Object iterated = frame.getObject(subjectSlot);
                while (true) {
                    if (!resuming) {
                        if (!bindCurrent(frame, iterated)) {
                            break;
                        }
                        frame.setLong(slot, 1);
                    }
                    resuming = false;
                    if (LoopNodes.runBody(body, frame) == LoopNodes.EXIT) {
                        break;
                    }
                    advance(frame, iterated);
                }
            } catch (Yield y) {
                yielded = true;
                throw y;
            } finally {
                if (!yielded) {
                    frame.setLong(slot, 0);
                    frame.setObject(subjectSlot, PhpNull.NULL);
                }
            }
        }

        /** Evaluates the subject; false if there's nothing to iterate. */
        private boolean begin(VirtualFrame frame) {
            Object value = subject.execute(frame);
            if (value instanceof PhpArray array) {
                array.addHolder();
                frame.setObject(subjectSlot, array);
                frame.setLong(positionSlot, array.firstPosition());
                return true;
            }
            if (value instanceof PhpObject object) {
                PhpObject iterator = object;
                while (iterator.getPhpClass().isSubclassOf("iteratoraggregate")) {
                    iterator = (PhpObject) iterator.callMethod("getiterator");
                }
                if (!iterator.getPhpClass().isSubclassOf("iterator")) {
                    throw new PhpError("foreach over an object's properties in a generator is not supported");
                }
                iterator.callMethod("rewind");
                frame.setObject(subjectSlot, iterator);
                return true;
            }
            return false;
        }

        private boolean bindCurrent(VirtualFrame frame, Object iterated) {
            Object key;
            Object value;
            if (iterated instanceof PhpArray array) {
                int position = (int) frame.getLong(positionSlot);
                if (position < 0) {
                    return false;
                }
                key = array.keyAt(position);
                value = array.valueAt(position);
            } else {
                PhpObject iterator = (PhpObject) iterated;
                if (!Convert.toBool(iterator.callMethod("valid"))) {
                    return false;
                }
                value = iterator.callMethod("current");
                key = keyTarget == null ? null : iterator.callMethod("key");
            }
            if (value instanceof PhpArray array) {
                array.addHolder();
            }
            valueTarget.write(frame, value);
            if (keyTarget != null) {
                keyTarget.write(frame, key);
            }
            return true;
        }

        private void advance(VirtualFrame frame, Object iterated) {
            if (iterated instanceof PhpArray array) {
                frame.setLong(positionSlot, array.nextPosition((int) frame.getLong(positionSlot)));
            } else {
                ((PhpObject) iterated).callMethod("next");
            }
        }
    }

    /**
     * try/catch/finally: the slot is 1 inside the try block and 2 + i inside catch i. A yield unwinding through
     * doesn't run the finally block; leaving the statement any other way does.
     */
    public static final class TryNode extends StmtNode {
        @Child private StmtNode body;
        @CompilationFinal(dimensions = 2) private final String[][] catchTypes;
        @Children private final LValueNode[] catchTargets;
        @Children private final StmtNode[] catchBodies;
        @Child private StmtNode finallyBody;
        private final int slot;

        public TryNode(StmtNode body, String[][] catchTypes, LValueNode[] catchTargets, StmtNode[] catchBodies,
                       StmtNode finallyBody, int slot) {
            this.body = body;
            this.catchTypes = catchTypes;
            this.catchTargets = catchTargets;
            this.catchBodies = catchBodies;
            this.finallyBody = finallyBody;
            this.slot = slot;
        }

        @Override
        public void execute(VirtualFrame frame) {
            boolean yielded = false;
            try {
                long state = state(frame, slot);
                if (state >= 2) {
                    resumeCatch(frame, (int) state - 2);
                    return;
                }
                frame.setLong(slot, 1);
                try {
                    body.execute(frame);
                } catch (PhpException e) {
                    handle(frame, e, e.getThrowable());
                } catch (PhpError e) {
                    handle(frame, e, pl.codeloom.php.nodes.stmt.TryNode.toErrorObject(PhpContext.get(this), e));
                }
            } catch (Yield y) {
                yielded = true;
                throw y;
            } finally {
                if (!yielded) {
                    frame.setLong(slot, 0);
                    if (finallyBody != null) {
                        finallyBody.execute(frame);
                    }
                }
            }
        }

        @ExplodeLoop
        private void resumeCatch(VirtualFrame frame, int index) {
            for (int i = 0; i < catchBodies.length; i++) {
                if (i == index) {
                    catchBodies[i].execute(frame);
                }
            }
        }

        @ExplodeLoop
        private void handle(VirtualFrame frame, RuntimeException original, PhpObject thrown) {
            for (int i = 0; i < catchBodies.length; i++) {
                if (pl.codeloom.php.nodes.stmt.TryNode.matches(thrown, catchTypes[i])) {
                    if (catchTargets[i] != null) {
                        catchTargets[i].write(frame, thrown);
                    }
                    frame.setLong(slot, 2 + i);
                    catchBodies[i].execute(frame);
                    return;
                }
            }
            throw original;
        }
    }
}
