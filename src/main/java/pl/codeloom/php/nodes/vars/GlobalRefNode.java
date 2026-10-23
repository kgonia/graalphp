package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.runtime.PhpRef;

/**
 * A global variable's reference: with create, made (holding null) if the global doesn't exist; without, null
 * then. A global keeps its reference until it is rebound or unset, which changes the context's globals version.
 * While the language serves one context, a site keeps the reference it found as a compile-time constant for that
 * version; a site that has seen more versions (code that runs while top-level code still rebinds and unsets
 * globals) keeps the last reference it found, checked against the version on each access. A site never keeps
 * the absence of a global: one may be created without a new version.
 */
public abstract class GlobalRefNode extends Node {
    /** Versions a site keeps as constants before it switches to the checked reference. */
    static final int LIMIT = 3;

    protected final String name;

    protected GlobalRefNode(String name) {
        this.name = name;
    }

    public static GlobalRefNode create(String name) {
        return GlobalRefNodeGen.create(name);
    }

    public abstract PhpRef execute(PhpContext context, boolean create);

    @Specialization(guards = "context.globalsVersion() == cachedVersion", limit = "LIMIT",
            assumptions = "singleContext()")
    protected PhpRef constant(PhpContext context, boolean create,
                              @Cached("context.globalsVersion()") int cachedVersion,
                              @Cached("lookup(context, create)") PhpRef cachedRef) {
        return cachedRef != null ? cachedRef : lookup(context, create);
    }

    @Specialization(replaces = "constant", assumptions = "singleContext()")
    protected PhpRef checked(PhpContext context, boolean create, @Cached("newLastRef()") LastRef last) {
        int version = context.globalsVersion();
        PhpRef ref = last.ref;
        if (ref == null || last.version != version) {
            ref = lookup(context, create);
            if (ref != null) {
                last.ref = ref;
                last.version = version;
            }
        }
        return ref;
    }

    /** Several contexts: each has its own globals. */
    @Specialization(replaces = {"constant", "checked"})
    protected PhpRef uncached(PhpContext context, boolean create) {
        return lookup(context, create);
    }

    protected PhpRef lookup(PhpContext context, boolean create) {
        return create ? context.globalRef(name) : context.findGlobal(name);
    }

    protected Assumption singleContext() {
        return PhpLanguage.get(this).singleContext();
    }

    protected static LastRef newLastRef() {
        return new LastRef();
    }

    /** The reference a site found last and the globals version it was found in. */
    protected static final class LastRef {
        PhpRef ref;
        int version;
    }
}
