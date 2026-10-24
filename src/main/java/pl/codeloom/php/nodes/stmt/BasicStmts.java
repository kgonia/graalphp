package pl.codeloom.php.nodes.stmt;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.CountingConditionProfile;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.ConditionNode;
import pl.codeloom.php.nodes.ControlFlow;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.nodes.StmtNode;
import pl.codeloom.php.nodes.vars.GlobalRefNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpException;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;

/** Straight-line statements. */
public final class BasicStmts {
    private BasicStmts() {
    }

    public static final class BlockNode extends StmtNode {
        @Children private final StmtNode[] statements;
        private final BranchProfile errorSurfaced = BranchProfile.create();

        public BlockNode(StmtNode[] statements) {
            this.statements = statements;
        }

        @Override
        @ExplodeLoop
        public void execute(VirtualFrame frame) {
            int i = 0;
            try {
                for (; i < statements.length; i++) {
                    statements[i].execute(frame);
                }
            } catch (PhpError e) {
                errorSurfaced.enter();
                surfaced(e, statements[i]);
                throw e;
            }
        }
    }

    public static final class ExprStmtNode extends StmtNode {
        @Child private ExprNode expression;

        public ExprStmtNode(ExprNode expression) {
            this.expression = expression;
        }

        @Override
        public void execute(VirtualFrame frame) {
            expression.execute(frame);
        }
    }

    public static final class EchoNode extends StmtNode {
        @Child private ExprNode value;

        public EchoNode(ExprNode value) {
            this.value = value;
        }

        @Override
        public void execute(VirtualFrame frame) {
            Object result = value.execute(frame);
            TruffleString text = result instanceof TruffleString s ? s : Convert.toStr(result);
            PhpContext.get(this).write(text);
        }
    }

    public static final class IfNode extends StmtNode {
        @Child private ExprNode condition;
        @Child private StmtNode then;
        @Child private StmtNode otherwise;
        private final CountingConditionProfile profile = CountingConditionProfile.create();

        public IfNode(ExprNode condition, StmtNode then, StmtNode otherwise) {
            this.condition = ConditionNode.of(condition);
            this.then = then;
            this.otherwise = otherwise;
        }

        @Override
        public void execute(VirtualFrame frame) {
            if (profile.profile(condition.executeCondition(frame))) {
                then.execute(frame);
            } else if (otherwise != null) {
                otherwise.execute(frame);
            }
        }
    }

    public static final class ReturnNode extends StmtNode {
        @Child private ExprNode value;

        public ReturnNode(ExprNode value) {
            this.value = value;
        }

        @Override
        public void execute(VirtualFrame frame) {
            throw new ControlFlow.Return(value == null ? PhpNull.NULL : value.execute(frame));
        }
    }

    public static final class BreakNode extends StmtNode {
        private final int levels;
        private final boolean isContinue;

        public BreakNode(int levels, boolean isContinue) {
            this.levels = levels;
            this.isContinue = isContinue;
        }

        @Override
        public void execute(VirtualFrame frame) {
            if (isContinue) {
                throw new ControlFlow.Continue(levels);
            }
            throw new ControlFlow.Break(levels);
        }
    }

    /** `global $x`: binds the local slot to the global's reference (cached by {@link GlobalRefNode}). */
    public static final class GlobalNode extends StmtNode {
        @Child private LValueNode local;
        @Child private GlobalRefNode global;

        public GlobalNode(LValueNode local, String name) {
            this.local = local;
            this.global = GlobalRefNode.create(name);
        }

        @Override
        public void execute(VirtualFrame frame) {
            local.bindRef(frame, global.execute(PhpContext.get(this), true));
        }
    }

    /** `static $x = init;`: binds the local slot to the function's persistent reference. */
    public static final class StaticVarNode extends StmtNode {
        @Child private LValueNode local;
        @Child private ExprNode initial;
        private final PhpFunction function;
        private final String name;

        public StaticVarNode(LValueNode local, ExprNode initial, PhpFunction function, String name) {
            this.local = local;
            this.initial = initial;
            this.function = function;
            this.name = name;
        }

        @Override
        public void execute(VirtualFrame frame) {
            PhpContext context = PhpContext.get(this);
            PhpRef ref = context.findStaticVariable(function, name);
            if (ref == null) {
                ref = context.createStaticVariable(function, name,
                        initial == null ? PhpNull.NULL : initial.execute(frame));
            }
            local.bindRef(frame, ref);
        }
    }

    public static final class UnsetNode extends StmtNode {
        @Child private LValueNode place;

        public UnsetNode(LValueNode place) {
            this.place = place;
        }

        @Override
        public void execute(VirtualFrame frame) {
            place.unset(frame);
        }
    }

    public static final class ThrowNode extends StmtNode {
        @Child private ExprNode value;

        public ThrowNode(ExprNode value) {
            this.value = value;
        }

        @Override
        public void execute(VirtualFrame frame) {
            Object thrown = value.execute(frame);
            if (thrown instanceof PhpObject object && object.getPhpClass().isSubclassOf("throwable")) {
                throw new PhpException(object);
            }
            CompilerDirectives.transferToInterpreter();
            throw new PhpError("Can only throw objects");
        }
    }

    /** A statement that does nothing (declarations already hoisted, `declare(strict_types=1)`). */
    public static final class NopNode extends StmtNode {
        @Override
        public void execute(VirtualFrame frame) {
        }
    }

    /** Runs an expression for its side effect in statement position, e.g. a hoisted declaration's runtime part. */
    /** Constructor property promotion: `$this->name = $name` before the constructor body runs. */
    public static final class PromoteNode extends StmtNode {
        private final String name;
        @Child private ExprNode value;

        public PromoteNode(String name, ExprNode value) {
            this.name = name;
            this.value = value;
        }

        @Override
        public void execute(VirtualFrame frame) {
            store((PhpObject) frame.getArguments()[PhpFunction.ARG_THIS], name, value.execute(frame));
        }

        @TruffleBoundary
        private static void store(PhpObject self, String name, Object value) {
            if (value instanceof PhpArray array) {
                array.addHolder();
            }
            self.setProperty(name, value);
        }
    }
}
