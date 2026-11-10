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
 * version; a site that has seen more versions (code that runs while globals are rebound and unset, as a page's
 * render does on every request) reads the context's table of globals by name id, as a site that serves several
 * contexts does. A site never keeps the absence of a global: one may be created without a new version.
 */
public abstract class GlobalRefNode extends Node {
    /** Versions a site keeps as constants before it reads the table. */
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

    /**
     * A site whose globals keep changing, or one that serves several contexts (each has its own globals): the
     * context keeps its globals' references by the name's id too, so a global that exists is an array load; one
     * that doesn't is looked up (and with create made) by name.
     */
    @Specialization(replaces = "constant")
    protected PhpRef bySlot(PhpContext context, boolean create, @Cached("globalId()") int id) {
        PhpRef ref = context.globalAt(id);
        return ref != null ? ref : lookup(context, create);
    }

    protected PhpRef lookup(PhpContext context, boolean create) {
        return create ? context.globalRef(name) : context.findGlobal(name);
    }

    protected int globalId() {
        return PhpLanguage.get(this).globalId(name);
    }

    protected Assumption singleContext() {
        return PhpLanguage.get(this).singleContext();
    }
}
