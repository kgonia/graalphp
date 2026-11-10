package pl.codeloom.php.nodes;

import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * A root that knows whether its code is large: thousands of AST nodes, as WP_Query::get_posts (9,360) and
 * wp-settings.php's top level (7,057). HotSpot installs at most 640 KB of machine code for one compilation
 * (JVMCINMethodSizeLimit), about 60,000 IR nodes, which binds only such functions; in them, a site that runs once
 * per call (outside any loop) takes the form with the least code, even where another form is faster
 * (ElementNodes.SlotCacheNode).
 *
 * <p>A root of any size also gives out its rich sites: the forms that are faster and several times larger than a
 * site's first one go to the {@link #RICH} sites that prove they need one first, which are the ones that run most
 * (a loop's). Without the bound a function of 200 element accesses whose keys vary compiled to 1.7 times the
 * code (src/test/resources/compile/query-builder.php, wiki/ceiling.md).
 */
public interface LargeCode {
    /** The nodes over which a root is large; Gate 1's kernels' functions are all smaller. */
    int LARGE = 3000;

    /** The rich sites of one root: about 1 KB of machine code each over the form they replace. */
    int RICH = 8;

    /** Whether the root is large; asked in the interpreter only, so it may count its nodes once. */
    boolean isLarge();

    /** Takes one of the root's {@link #RICH} rich sites; false when they are taken. Asked in the interpreter. */
    boolean takeRich();

    /** Counts root's nodes (a walk of its tree): for a root's isLarge, once. */
    static boolean countLarge(RootNode root) {
        return NodeUtil.countNodes(root) >= LARGE;
    }
}
