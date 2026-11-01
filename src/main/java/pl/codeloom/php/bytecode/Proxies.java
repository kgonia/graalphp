package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import pl.codeloom.php.nodes.expr.ArithmeticNodes;

/**
 * The tree's nodes as the bytecode interpreter proxies them: each a subclass with the node's specializations
 * (inherited, one copy) that leaves polymorphism reporting out. The tree's nodes report polymorphism (ExprNode),
 * and from Truffle 25.1 on, a proxied node that does generates code that doesn't compile: the specialization
 * state moved into the bytecode array, and the generated checkForPolymorphicSpecialize reads it without the array
 * in scope.
 */
final class Proxies {
    private Proxies() {
    }

    @OperationProxy.Proxyable(allowUncached = true)
    @ReportPolymorphism.Exclude
    public abstract static class Add extends ArithmeticNodes.AddNode {
    }
}
