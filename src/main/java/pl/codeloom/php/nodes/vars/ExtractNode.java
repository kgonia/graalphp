package pl.codeloom.php.nodes.vars;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.Args;
import pl.codeloom.php.nodes.ExprNode;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * `extract($array, $flags, $prefix)`: the array's string keys become variables of the calling scope. A
 * function's variables are its frame slots by name (see {@link DynamicCompactNode}); a name the function never
 * mentions goes to the call's table of other variables ({@link IncludeScope}), where a file the call includes
 * finds it (WooCommerce's wc_get_template extracts a template's arguments, then includes it). At the top level
 * the variables are globals, or those of the call the file is included from. Each mode as php-src's
 * php_extract_* (a variable the function names but that is unset is in php-src's symbol table, undefined).
 * EXTR_REFS binds values, not references.
 */
public final class ExtractNode extends ExprNode {
    private static final Pattern NAME = Pattern.compile("[a-zA-Z_\\x80-\\xff][a-zA-Z0-9_\\x80-\\xff]*");
    private static final long OVERWRITE = 0;
    private static final long SKIP = 1;
    private static final long PREFIX_SAME = 2;
    private static final long PREFIX_ALL = 3;
    private static final long PREFIX_INVALID = 4;
    private static final long IF_EXISTS = 6;
    private static final long PREFIX_IF_EXISTS = 5;

    @Child private ExprNode array;
    @Child private ExprNode flags;
    @Child private ExprNode prefix;
    /** Name to frame slot, or null at the top level. */
    private final Map<String, Integer> slots;
    /** In a function: the slot of the call's IncludeScope, and the class the function is written in. */
    private final int scopeSlot;
    private final String lexicalClass;

    public ExtractNode(ExprNode array, ExprNode flags, ExprNode prefix, Map<String, Integer> slots, int scopeSlot,
                       String lexicalClass) {
        this.array = array;
        this.flags = flags;
        this.prefix = prefix;
        this.slots = slots;
        this.scopeSlot = scopeSlot;
        this.lexicalClass = lexicalClass;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object source = array.execute(frame);
        Object flagsValue = flags == null ? null : flags.execute(frame);
        Object prefixValue = prefix == null ? null : prefix.execute(frame);
        IncludeScope included = slots == null ? IncludeScope.of(frame)
                : IncludeScope.forCall(frame, scopeSlot, slots, lexicalClass);
        return extract(source, flagsValue, prefixValue, included, slots == null ? PhpContext.get(this) : null);
    }

    /**
     * The call, its arguments evaluated (flags and prefix null when not given), checked as php-src's PHP_FUNCTION
     * (extract) checks them: into included, a function call's variables or the scope a file was included into, else
     * into the globals (context). Also a function's extract() on bytecode.
     */
    @TruffleBoundary
    public static long extract(Object source, Object flags, Object prefix, IncludeScope included,
                               PhpContext context) {
        if (!(source instanceof PhpArray values)) {
            throw Args.typeError("extract", 1, "array", "array", source);
        }
        Object[] arguments = new Object[PhpFunction.ARG_FIRST + 3];
        arguments[PhpFunction.ARG_FIRST + 1] = flags;
        arguments[PhpFunction.ARG_FIRST + 2] = prefix;
        PhpContext running = PhpContext.get(null);
        long mode = flags == null ? OVERWRITE
                : Args.integer(running, arguments, 1, "extract", "flags", "int") & 0xff;
        String prefixText = prefix == null ? null
                : Str.java(Args.string(running, arguments, 2, "extract", "prefix", false));
        if (mode < OVERWRITE || mode > IF_EXISTS) {
            throw new PhpError("ValueError", "extract(): Argument #2 ($flags) must be a valid extract type");
        }
        if (mode > SKIP && mode <= PREFIX_IF_EXISTS && prefixText == null) {
            throw new PhpError("ValueError",
                    "extract(): Argument #3 ($prefix) is required when using this extract type");
        }
        if (prefixText != null && !prefixText.isEmpty() && !valid(prefixText)) {
            throw new PhpError("ValueError", "extract(): Argument #3 ($prefix) must be a valid identifier");
        }
        return extract(values, mode, prefixText, included, context);
    }

    /** php-src's php_extract_* by mode: each entry's variable (or none), assigned in order, counted. */
    private static long extract(PhpArray values, long mode, String prefix, IncludeScope included,
                                PhpContext context) {
        long count = 0;
        for (int p = values.firstPosition(); p >= 0; p = values.nextPosition(p)) {
            Object key = values.keyAt(p);
            // null for an int key
            String name = key instanceof TruffleString string ? Str.java(string) : null;
            String target = target(mode, name, key, prefix, included, context);
            if (target == null) {
                continue;
            }
            Object value = PhpRef.deref(values.valueAt(p));
            if (included != null) {
                included.set(target, value);
            } else {
                if (value instanceof PhpArray array) {
                    array.addHolder();
                }
                context.globalRef(target).set(value);
            }
            count++;
        }
        return count;
    }

    /** The variable an entry goes to, or null when the mode leaves it out. */
    private static String target(long mode, String name, Object key, String prefix, IncludeScope included,
                                 PhpContext context) {
        if (mode == OVERWRITE) {
            if (name == null || !valid(name)) {
                return null;
            }
            if (name.equals("this")) {
                throw reassignThis();
            }
            return name.equals("GLOBALS") && state(name, included, context) != ABSENT ? null : name;
        }
        if (mode == SKIP) {
            return name == null || !valid(name) || name.equals("this")
                    || state(name, included, context) == DEFINED ? null : name;
        }
        if (mode == IF_EXISTS) {
            if (name == null || state(name, included, context) != DEFINED || !valid(name)
                    || name.equals("GLOBALS")) {
                return null;
            }
            if (name.equals("this")) {
                throw reassignThis();
            }
            return name;
        }
        if (mode == PREFIX_ALL) {
            if (name != null && name.isEmpty()) {
                return null;
            }
            return prefixed(prefix, name != null ? name : key.toString());
        }
        if (mode == PREFIX_INVALID) {
            if (name != null && valid(name) && !name.equals("this")) {
                return name;
            }
            return prefixed(prefix, name != null ? name : key.toString());
        }
        if (name == null) {
            return null;
        }
        int state = state(name, included, context);
        if (mode == PREFIX_SAME) {
            if (name.isEmpty()) {
                return null;
            }
            if (state == UNDEFINED) {
                // a variable the function names but that is unset: php-src's CV, assigned unprefixed
                return name;
            }
            if (state == ABSENT) {
                if (!valid(name)) {
                    return null;
                }
                if (!name.equals("this")) {
                    return name;
                }
            }
            return prefixed(prefix, name);
        }
        // PREFIX_IF_EXISTS
        if (state == ABSENT) {
            return null;
        }
        return state == UNDEFINED ? name : prefixed(prefix, name);
    }

    /** php_prefix_varname with the underscore, if the result is a valid name (null if not; $this is an error). */
    private static String prefixed(String prefix, String name) {
        String target = prefix + "_" + name;
        if (!valid(target)) {
            return null;
        }
        if (target.equals("this")) {
            throw reassignThis();
        }
        return target;
    }

    private static final int ABSENT = 0;
    /** A variable the function names (php-src's CV) that is unset: in the symbol table, undefined. */
    private static final int UNDEFINED = 1;
    private static final int DEFINED = 2;

    /** Whether the name is in the symbol table, and defined. */
    private static int state(String name, IncludeScope included, PhpContext context) {
        if (included == null) {
            return context.findGlobal(name) != null ? DEFINED : ABSENT;
        }
        if (included.isDefined(name)) {
            return DEFINED;
        }
        return included.isFunctionVariable(name) ? UNDEFINED : ABSENT;
    }

    /** php_valid_var_name. */
    private static boolean valid(String name) {
        return NAME.matcher(name).matches();
    }

    private static PhpError reassignThis() {
        return new PhpError("Error", "Cannot re-assign $this");
    }
}
