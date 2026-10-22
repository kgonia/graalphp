package pl.codeloom.php.nodes;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;

/** A statement. */
public abstract class StmtNode extends Node {
    public abstract void execute(VirtualFrame frame);
}
