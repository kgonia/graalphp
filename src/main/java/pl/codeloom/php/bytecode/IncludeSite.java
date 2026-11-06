package pl.codeloom.php.bytecode;

import pl.codeloom.php.runtime.Backtrace;

/**
 * An include in a function, the constant operand of its Include instruction: which of include, require,
 * include_once and require_once it is. Traces find it in the instruction the included file was called from, and
 * list the file as that include (as the tree's IncludeNode).
 */
public final class IncludeSite implements Backtrace.CallSite {
    private final String kind;
    /** include_once, require_once: known when the site is made, not looked for in kind where it runs. */
    private final boolean once;

    public IncludeSite(String kind) {
        this.kind = kind;
        this.once = kind.endsWith("_once");
    }

    boolean once() {
        return once;
    }

    @Override
    public String includeKind() {
        return kind;
    }

    @Override
    public String toString() {
        return kind;
    }
}
