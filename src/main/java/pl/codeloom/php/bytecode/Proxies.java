package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import pl.codeloom.php.nodes.ToBooleanNode;
import pl.codeloom.php.nodes.expr.ArithmeticNodes;
import pl.codeloom.php.nodes.expr.CompareNodes;
import pl.codeloom.php.nodes.expr.UnaryNodes;
import pl.codeloom.php.nodes.vars.ElementNodes;

/**
 * The tree's nodes as the bytecode interpreter proxies them: each a subclass with the node's specializations
 * (inherited, one copy) that leaves polymorphism reporting out. The tree's nodes report polymorphism (ExprNode,
 * ToBooleanNode, ElementReadNode), and from Truffle 25.1 on, a proxied node that does generates code that doesn't
 * compile: the specialization state moved into the bytecode array, and the generated checkForPolymorphicSpecialize
 * reads it without the array in scope. The comparisons have their own classes ({@link
 * pl.codeloom.php.nodes.expr.Comparisons}).
 */
final class Proxies {
    private Proxies() {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    @ReportPolymorphism.Exclude
    public abstract static class Add extends ArithmeticNodes.AddNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    @ReportPolymorphism.Exclude
    public abstract static class Sub extends ArithmeticNodes.SubNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    @ReportPolymorphism.Exclude
    public abstract static class Mul extends ArithmeticNodes.MulNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    @ReportPolymorphism.Exclude
    public abstract static class Div extends ArithmeticNodes.DivNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    @ReportPolymorphism.Exclude
    public abstract static class Mod extends ArithmeticNodes.ModNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    @ReportPolymorphism.Exclude
    public abstract static class Pow extends ArithmeticNodes.PowNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    @ReportPolymorphism.Exclude
    public abstract static class BitAnd extends ArithmeticNodes.BitAndNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    @ReportPolymorphism.Exclude
    public abstract static class BitOr extends ArithmeticNodes.BitOrNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    @ReportPolymorphism.Exclude
    public abstract static class BitXor extends ArithmeticNodes.BitXorNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    @ReportPolymorphism.Exclude
    public abstract static class ShiftLeft extends ArithmeticNodes.ShiftLeftNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    @ReportPolymorphism.Exclude
    public abstract static class ShiftRight extends ArithmeticNodes.ShiftRightNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    @ReportPolymorphism.Exclude
    public abstract static class Concat extends ArithmeticNodes.ConcatNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    @ReportPolymorphism.Exclude
    public abstract static class Spaceship extends CompareNodes.SpaceshipNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    @ReportPolymorphism.Exclude
    public abstract static class Negate extends UnaryNodes.NegateNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    @ReportPolymorphism.Exclude
    public abstract static class Plus extends UnaryNodes.PlusNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    @ReportPolymorphism.Exclude
    public abstract static class BitNot extends UnaryNodes.BitNotNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    @ReportPolymorphism.Exclude
    public abstract static class ToBool extends ToBooleanNode {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    @ReportPolymorphism.Exclude
    public abstract static class ReadElement extends ElementNodes.ElementReadNode {
    }
}
