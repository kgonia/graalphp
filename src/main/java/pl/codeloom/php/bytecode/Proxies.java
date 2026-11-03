package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.bytecode.OperationProxy;
import pl.codeloom.php.nodes.ToBooleanNode;
import pl.codeloom.php.nodes.expr.ArithmeticNodes;
import pl.codeloom.php.nodes.expr.CompareNodes;
import pl.codeloom.php.nodes.expr.UnaryNodes;
import pl.codeloom.php.nodes.vars.ElementNodes;

/**
 * The tree's nodes as the bytecode interpreter proxies them: each a subclass with the node's specializations
 * (inherited, one copy). Those that report polymorphism (ExprNode, ToBooleanNode) report it from the bytecode
 * too, as on the tree: the released processors 25.1 to 25.4 generate code for that which doesn't compile
 * (https://github.com/oracle/graal/issues/14871), the patched one (truffle-dsl-processor/, PR #14872) compiles it.
 * The comparisons have their own classes ({@link pl.codeloom.php.nodes.expr.Comparisons}).
 */
final class Proxies {
    private Proxies() {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class Add extends ArithmeticNodes.AddNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class Sub extends ArithmeticNodes.SubNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class Mul extends ArithmeticNodes.MulNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class Div extends ArithmeticNodes.DivNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class Mod extends ArithmeticNodes.ModNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class Pow extends ArithmeticNodes.PowNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class BitAnd extends ArithmeticNodes.BitAndNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class BitOr extends ArithmeticNodes.BitOrNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class BitXor extends ArithmeticNodes.BitXorNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class ShiftLeft extends ArithmeticNodes.ShiftLeftNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class ShiftRight extends ArithmeticNodes.ShiftRightNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class Concat extends ArithmeticNodes.ConcatNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class Spaceship extends CompareNodes.SpaceshipNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class Negate extends UnaryNodes.NegateNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class Plus extends UnaryNodes.PlusNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class BitNot extends UnaryNodes.BitNotNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class ToBool extends ToBooleanNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class ReadElement extends ElementNodes.ElementReadNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class CastLong extends UnaryNodes.ToLongNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class CastDouble extends UnaryNodes.ToDoubleNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    public abstract static class CastString extends UnaryNodes.ToStringNode {
    }
}
