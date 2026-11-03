package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.nodes.LValueNode;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

/**
 * `$$name`, `${expr}` (php-src's ZEND_FETCH_* of a name that isn't a constant, ZEND_FETCH_LOCAL): the variable the
 * name's string names in the running code's symbol table, its call's ({@link IncludeScope}: a function on bytecode
 * passes its BytecodeScope, a file included from a function the including call's) or else the globals. php-src
 * fetches it by what the place is used for (BP_VAR_R, W, RW, IS, UNSET; isset() and empty() look it up without
 * fetching): that decides the warning for an undefined one, whether it is made, and what "this" is, which is never
 * in a symbol table. This node is a file's top-level `$$name`; the static methods are what both interpreters run.
 * Rare code, behind boundaries.
 */
public final class VariableVariableNode extends LValueNode {
    /** php-src's fetch types, and ISSET for ZEND_ISSET_ISEMPTY_VAR, which doesn't fall back to $this. */
    public static final int R = 0;
    public static final int W = 1;
    public static final int RW = 2;
    public static final int IS = 3;
    public static final int UNSET = 4;
    public static final int ISSET = 5;

    @Child private ExprNode name;
    /** How readIfSet fetches: IS (`??`, a container's isset()), or ISSET for empty($$name). */
    private final int ifSet;

    public VariableVariableNode(ExprNode name, int ifSet) {
        this.name = name;
        this.ifSet = ifSet;
    }

    /** The variable's name (zval_try_get_tmp_string): an array's with php-src's warning, an object's __toString(). */
    @TruffleBoundary
    public static String name(Node node, Object value) {
        return Str.java(Convert.toStr(node, PhpRef.deref(value)));
    }

    /**
     * The variable's value fetched for type R, IS or ISSET, or as the object of a property place for W, RW or
     * UNSET ({@link #ref}): an undefined one is null, after php-src's warning for R; "this", undefined, is $this
     * (zend_fetch_this_var) except for isset().
     */
    @TruffleBoundary
    public static Object read(Node node, IncludeScope scope, Object thisObject, String name, int type) {
        if (type == W || type == RW || type == UNSET) {
            return ref(node, scope, name, type) instanceof PhpRef ref ? ref.get() : PhpNull.NULL;
        }
        Object value = scope != null ? scope.get(name) : globalValue(node, name);
        if (value != null) {
            return value;
        }
        if (type != ISSET && name.equals("this")) {
            if (thisObject instanceof PhpObject) {
                return thisObject;
            }
            if (type == R) {
                warn(node, "Undefined variable $this");
            }
            return PhpNull.NULL;
        }
        if (type == R) {
            warn(node, "Undefined variable $" + name);
        }
        return PhpNull.NULL;
    }

    /**
     * The variable's reference fetched for type W, RW or UNSET: an undefined one is made (null), after the warning
     * for RW; null for UNSET. "this", undefined, is php-src's Error.
     */
    @TruffleBoundary
    public static Object ref(Node node, IncludeScope scope, String name, int type) {
        boolean defined = scope != null ? scope.isDefined(name) : PhpContext.get(node).findGlobal(name) != null;
        if (!defined) {
            if (name.equals("this")) {
                throw new PhpError("Error", type == UNSET ? "Cannot unset $this" : "Cannot re-assign $this");
            }
            if (type == UNSET) {
                return PhpNull.NULL;
            }
            if (type == RW) {
                warn(node, "Undefined variable $" + name);
            }
        }
        return scope != null ? scope.ref(name) : PhpContext.get(node).globalRef(name);
    }

    /** `$$name = &place`: the variable becomes the reference (FETCH_W, then ASSIGN_REF). */
    @TruffleBoundary
    public static void bind(Node node, IncludeScope scope, String name, PhpRef ref) {
        boolean defined = scope != null ? scope.isDefined(name) : PhpContext.get(node).findGlobal(name) != null;
        if (!defined && name.equals("this")) {
            throw new PhpError("Error", "Cannot re-assign $this");
        }
        if (scope != null) {
            scope.bind(name, ref);
        } else {
            PhpContext.get(node).bindGlobal(name, ref);
        }
    }

    /** `unset($$name)` (ZEND_UNSET_VAR): "this" isn't in the symbol table, so there is nothing to unset. */
    @TruffleBoundary
    public static void unset(Node node, IncludeScope scope, String name) {
        if (scope != null) {
            scope.unset(name);
        } else {
            PhpContext.get(node).unsetGlobal(name);
        }
    }

    private static Object globalValue(Node node, String name) {
        PhpRef ref = PhpContext.get(node).findGlobal(name);
        return ref == null ? null : ref.get();
    }

    private static void warn(Node node, String message) {
        ErrorFunctions.raise(PhpContext.get(node), ErrorFunctions.E_WARNING, Str.of(message), SourceLocation.of(node));
    }

    private String name(VirtualFrame frame) {
        return name(this, name.execute(frame));
    }

    private static Object thisObject(VirtualFrame frame) {
        return frame.getArguments()[PhpFunction.ARG_THIS];
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return fetch(frame, R);
    }

    /** The value fetched as type says (see {@link #read}): PropNode fetches the object it writes to for W. */
    public Object fetch(VirtualFrame frame, int type) {
        return read(this, IncludeScope.of(frame), thisObject(frame), name(frame), type);
    }

    @Override
    public void write(VirtualFrame frame, Object value) {
        ((PhpRef) ref(this, IncludeScope.of(frame), name(frame), W)).set(value);
    }

    @Override
    public PhpRef makeRef(VirtualFrame frame) {
        return (PhpRef) ref(this, IncludeScope.of(frame), name(frame), W);
    }

    @Override
    public void bindRef(VirtualFrame frame, PhpRef ref) {
        bind(this, IncludeScope.of(frame), name(frame), ref);
    }

    @Override
    public PhpArray arrayForWrite(VirtualFrame frame) {
        return Places.refArrayForWrite(makeRef(frame));
    }

    @Override
    public void unset(VirtualFrame frame) {
        unset(this, IncludeScope.of(frame), name(frame));
    }

    @Override
    public boolean isSet(VirtualFrame frame) {
        return fetch(frame, ISSET) != PhpNull.NULL;
    }

    @Override
    public Object readIfSet(VirtualFrame frame) {
        Object value = fetch(frame, ifSet);
        return value == PhpNull.NULL ? null : value;
    }

    @Override
    public boolean isPure() {
        return name.constantValue() != null || name instanceof LocalVarNode || name instanceof FileVarNode
                || name instanceof GlobalVarNode;
    }
}
