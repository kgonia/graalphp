package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.GenerateBytecode;
import com.oracle.truffle.api.bytecode.OperationProxy;
import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.nodes.RootNode;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.nodes.PhpTypes;
import pl.codeloom.php.nodes.expr.ArithmeticNodes;

/** A PHP function body as Truffle Bytecode DSL bytecode; its operations are the tree's nodes (OperationProxy). */
@GenerateBytecode(languageClass = PhpLanguage.class, enableYield = true, enableUncachedInterpreter = true,
        boxingEliminationTypes = {long.class, double.class, boolean.class})
@TypeSystemReference(PhpTypes.class)
@OperationProxy(value = ArithmeticNodes.AddNode.class, name = "Add")
public abstract class PhpBytecodeRootNode extends RootNode implements BytecodeRootNode {
    protected PhpBytecodeRootNode(PhpLanguage language, FrameDescriptor.Builder frameDescriptor) {
        super(language, frameDescriptor.build());
    }
}
