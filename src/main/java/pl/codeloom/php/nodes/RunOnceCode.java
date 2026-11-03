package pl.codeloom.php.nodes;

import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.RootNode;
import pl.codeloom.php.nodes.stmt.ForeachNode;
import pl.codeloom.php.nodes.stmt.GotoNodes;

/**
 * Code that runs a few times a request at most, a number bounded by the program's text, and that compilation can't
 * pay back, so it is never compiled ({@link RootNode#prepareForCompilation}): a file's top level that no include
 * site has run twice in one context (each site runs it at most once a request: Jetpack's class map is required by
 * two sites, the autoloader's version check and its manifest reader), and a constant expression's initializer
 * (ThunkRootNode, evaluated once per class and context), when it has no loop and is {@link LargeCode large}.
 * <p>
 * Without a loop every node of the code runs at most once per run, so compiling it saves the interpreter's cost of
 * each node a few times a request (tens of nanoseconds), against a compilation that costs about a millisecond per
 * node (WordPress's largest functions: 0.7-0.9 ms; straight-line literals and calls more, superlinearly): it would
 * pay back after thousands of requests. And large straight-line code is what doesn't compile at all: WooCommerce's
 * Jetpack class map, a property default of a thousand class constants and its country list (27,000, 6,400 and
 * 19,000 AST nodes) failed after 40-360 s each (a timeout, an oopmap limit, code too large), holding a compiler
 * thread and gigabytes of libgraal's heap (wiki/pe-round4-diagnosis.md), and ran interpreted all the same. Small
 * run-once code compiles in milliseconds and is left to Truffle; code with a loop, or run twice by one include site in
 * one request (a template part in a loop), runs a number of times that depends on the data, and is compiled as any
 * function.
 */
public final class RunOnceCode {
    private RunOnceCode() {
    }

    /** What a root's tree is, counted once: 0 not yet, 1 small or with a loop, 2 large without a loop. */
    public static byte classify(RootNode root) {
        int[] count = {0};
        boolean[] loop = {false};
        NodeUtil.forEachChild(root, new com.oracle.truffle.api.nodes.NodeVisitor() {
            @Override
            public boolean visit(Node node) {
                count[0]++;
                if (node instanceof LoopNode || node instanceof ForeachNode
                        || node instanceof GotoNodes.LabeledBlockNode) {
                    loop[0] = true;
                    return false;
                }
                return NodeUtil.forEachChild(node, this);
            }
        });
        return !loop[0] && count[0] >= LargeCode.LARGE ? (byte) 2 : (byte) 1;
    }
}
