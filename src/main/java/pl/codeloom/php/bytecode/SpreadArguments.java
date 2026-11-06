package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.Args;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.nodes.stmt.ForeachNode;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.util.ArrayList;
import java.util.List;

/**
 * The arguments of a call that unpacks some (`f($a, ...$b)`), or of call_user_func_array() and the calls like it,
 * gathered as they are evaluated and bound against the callee resolved before them: their number is known only once
 * the unpacked values are. An argument goes by position, or by name (php-src's zend_handle_named_arg) for an array's
 * string key, a Traversable's string key that isn't numeric, and a named argument after the unpacking:
 * <ul>
 * <li>a name found at slot i fills it, the slots skipped on the way being holes (UNDEF below num_args) that the
 * callee's defaults target fills before the call; "Named parameter $x overwrites previous argument" if it is
 * set;</li>
 * <li>a name the callee doesn't have is an extra its variadic parameter collects (NamedExtras), else "Unknown named
 * parameter $x";</li>
 * <li>a position after a name in the same unpacking is an Error; each `...` starts afresh.</li>
 * </ul>
 * Whether an argument goes by reference is its slot's, an extra's the variadic parameter's. The call takes them as
 * frame arguments, then {@link #enter} before calling.
 */
public final class SpreadArguments {
    /** The arguments by position; NOT_PASSED where a name skipped a parameter. */
    private final List<Object> values = new ArrayList<>();
    /** The extras (extra_named_params) in their order; null while there are none. */
    private List<TruffleString> extraNames;
    private List<Object> extraValues;
    /** Whether a name skipped a parameter (ZEND_CALL_MAY_HAVE_UNDEF). */
    private boolean holes;
    /** A `new`'s: its callee BY_VALUE is a class without a constructor (zend_pass_function), which takes no name. */
    private final boolean construct;

    SpreadArguments(boolean construct) {
        this.construct = construct;
    }

    /** The arguments of a call_user_func_array() (no `new`). */
    public SpreadArguments() {
        this(false);
    }

    @TruffleBoundary
    void add(Object value) {
        values.add(value);
    }

    /** The position the next argument lands on. */
    @TruffleBoundary
    int size() {
        return values.size();
    }

    /** The frame arguments of the call: the first slots for the callee's $this, called class and closure. */
    @TruffleBoundary
    public Object[] frameArguments() {
        Object[] arguments = new Object[PhpFunction.ARG_FIRST + values.size()];
        for (int i = 0; i < values.size(); i++) {
            arguments[PhpFunction.ARG_FIRST + i] = values.get(i);
        }
        return arguments;
    }

    // ---- binding by name ----

    /**
     * zend_handle_named_arg: the slot of the callee's parameter called name (its index), or {@link BindingPlan#EXTRA}
     * for its variadic parameter; the Error for an unknown name or a slot already set. The slot is the argument's
     * from then on: its value comes with {@link #set}.
     */
    @TruffleBoundary
    int bind(TruffleString name, PhpFunction callee) {
        int index = BindingPlan.parameterIndex(callee, Str.java(name));
        if (index >= 0) {
            if (index < values.size()) {
                if (values.get(index) != PhpFunction.NOT_PASSED) {
                    throw new PhpError(BindingPlan.overwrites(Str.java(name)));
                }
                return index;
            }
            if (index > values.size()) {
                holes = true;
            }
            while (values.size() <= index) {
                values.add(PhpFunction.NOT_PASSED);
            }
            return index;
        }
        if (callee == MethodSite.BY_VALUE ? construct : !BindingPlan.isVariadic(callee)) {
            throw new PhpError("Unknown named parameter $" + Str.java(name));
        }
        if (extraNames == null) {
            extraNames = new ArrayList<>();
            extraValues = new ArrayList<>();
        } else if (extraNames.contains(name)) {
            throw new PhpError(BindingPlan.overwrites(Str.java(name)));
        }
        extraNames.add(name);
        extraValues.add(PhpFunction.NOT_PASSED);
        return BindingPlan.EXTRA;
    }

    /** The value of the argument bound to slot: a position, or EXTRA for the last extra bound. */
    @TruffleBoundary
    void set(int slot, Object value) {
        if (slot == BindingPlan.EXTRA) {
            extraValues.set(extraValues.size() - 1, value);
        } else if (slot == values.size()) {
            values.add(value);
        } else {
            values.set(slot, value);
        }
    }

    /**
     * CHECK_FUNC_ARG with a name: whether a place named so goes by reference, as the parameter of that name takes
     * it, else the variadic one; an unknown name by value (its send throws).
     */
    @TruffleBoundary
    static boolean namedByRef(PhpFunction callee, TruffleString name) {
        int index = BindingPlan.parameterIndex(callee, Str.java(name));
        if (index >= 0) {
            return callee.isByRef(index);
        }
        return callee != MethodSite.BY_VALUE && BindingPlan.isVariadic(callee)
                && callee.isByRef(BindingPlan.variadicIndex(callee));
    }

    /** ARG_SHOULD_BE_SENT_BY_REF of a slot: its parameter's, an extra's the variadic one's. */
    static boolean takesRef(PhpFunction callee, int slot) {
        return callee.isByRef(slot == BindingPlan.EXTRA ? Math.max(BindingPlan.variadicIndex(callee), 0) : slot);
    }

    /** ARG_MUST_BE_SENT_BY_REF: by reference, and not a built-in's prefer-ref parameter. */
    private static boolean mustTakeRef(PhpFunction callee, int slot) {
        return takesRef(callee, slot) && !callee.isPreferRef();
    }

    /** The parameter a slot's messages number: its own, an extra's the variadic one (arg_offset is num_args). */
    static int parameterOf(PhpFunction callee, int slot) {
        return slot == BindingPlan.EXTRA ? BindingPlan.variadicIndex(callee) : slot;
    }

    // ---- ZEND_SEND_UNPACK ----

    /**
     * `...value`: an array's elements, or a Traversable's; php-src's TypeError for anything else. A temporary
     * array's element for a by-reference parameter goes as it is: a reference stays one, a value is bound as a new
     * reference.
     */
    @TruffleBoundary
    void addSpread(Object value, PhpFunction callee, Node site) {
        if (value instanceof PhpArray array) {
            addArray(array, callee, false);
            return;
        }
        if (!(value instanceof PhpObject object) || !object.getPhpClass().isSubclassOf("traversable")) {
            throw new PhpError("TypeError", "Only arrays and Traversables can be unpacked, " + Args.valueName(value)
                    + " given");
        }
        addTraversable(object, callee, site);
    }

    /**
     * An unpacked array's elements in order: an int key is the next position whatever its value, a string key a
     * name. variable: the array is a variable's, separated for this (see {@link #landsOnRef}), whose elements passed
     * by reference become references in it.
     */
    @TruffleBoundary
    void addArray(PhpArray array, PhpFunction callee, boolean variable) {
        boolean named = false;
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            int slot;
            if (array.keyAt(p) instanceof TruffleString name) {
                named = true;
                slot = bind(name, callee);
            } else if (named) {
                throw new PhpError("Cannot use positional argument after named argument during unpacking");
            } else {
                slot = values.size();
            }
            if (!takesRef(callee, slot)) {
                set(slot, array.valueAt(p));
            } else if (variable) {
                set(slot, array.refAtPosition(p));
            } else {
                set(slot, array.rawAt(p));
            }
        }
    }

    /**
     * Whether an element of array unpacked at position lands on a by-reference parameter, by its name or its
     * position, as php-src checks before it separates a variable's shared array: from a name's parameter on, an
     * unknown name's offset (-1) looking at the variadic parameter.
     */
    @TruffleBoundary
    static boolean landsOnRef(PhpFunction callee, int position, PhpArray array) {
        // 1-based, as zend_check_arg_send_type numbers them
        int number = position + 1;
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            if (array.keyAt(p) instanceof TruffleString name) {
                number = BindingPlan.parameterIndex(callee, Str.java(name)) + 1;
            }
            if (number == 0 ? BindingPlan.isVariadic(callee) && callee.isByRef(BindingPlan.variadicIndex(callee))
                    : callee.isByRef(number - 1)) {
                return true;
            }
            number++;
        }
        return false;
    }

    /**
     * A Traversable's elements, as its iterator gives them: an int key is a position, a string one a name unless it
     * is numeric (ZEND_HANDLE_NUMERIC), any other key the Error. Each goes by value: for a by-reference parameter as
     * a new reference, after php-src's warning.
     */
    private void addTraversable(PhpObject object, PhpFunction callee, Node site) {
        boolean[] named = new boolean[1];
        ForeachNode.traverse(site, object, (key, element) -> {
            int slot;
            if (key instanceof TruffleString text && ArrayKey.of(text) instanceof TruffleString name) {
                named[0] = true;
                slot = bind(name, callee);
            } else if (!(key instanceof Long) && !(key instanceof TruffleString)) {
                throw new PhpError("Keys must be of type int|string during argument unpacking");
            } else if (named[0]) {
                throw new PhpError("Cannot use positional argument after named argument during unpacking");
            } else {
                slot = values.size();
            }
            if (mustTakeRef(callee, slot)) {
                ErrorFunctions.raise(PhpContext.get(site), ErrorFunctions.E_WARNING, Str.of("Cannot pass "
                        + "by-reference argument " + (parameterOf(callee, slot) + 1) + " of " + callee.displayName()
                        + "() by unpacking a Traversable, passing by-value instead"), location(site));
                set(slot, new PhpRef(element));
            } else {
                set(slot, element);
            }
            return true;
        });
    }

    // ---- ZEND_SEND_ARRAY, zend_call_function's named_params ----

    /**
     * call_user_func_array()'s arguments (and the calls like it): the array's elements bound as {@link #addArray}
     * binds them, but a position after a name is "Cannot use positional argument after named argument", and a value
     * for a by-reference parameter is php-src's warning, the call going on with a new reference; a reference element
     * stays one, by value only its value goes (but to __call, whose arguments array keeps it).
     */
    @TruffleBoundary
    public void addSendArray(PhpArray array, PhpFunction callee, Node site) {
        boolean named = false;
        for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
            int slot;
            if (array.keyAt(p) instanceof TruffleString name) {
                named = true;
                slot = bind(name, callee);
            } else if (named) {
                throw new PhpError("Cannot use positional argument after named argument");
            } else {
                slot = values.size();
            }
            Object element = array.rawAt(p);
            if (!takesRef(callee, slot)) {
                // "don't separate references for __call"
                set(slot, callee == MethodSite.BY_VALUE ? element : PhpRef.deref(element));
            } else if (element instanceof PhpRef || callee.isPreferRef()) {
                set(slot, element);
            } else {
                BindingPlan.mustBeRef(callee, parameterOf(callee, slot), site);
                set(slot, new PhpRef(element));
            }
        }
    }

    /**
     * The frame arguments of target's call with call_user_func_array()'s array (ZEND_SEND_ARRAY, zend_call_function's
     * named_params): bound by {@link #addSendArray}, the holes filled and the extras put in. site: the call's
     * instruction, or null for a built-in's.
     */
    @TruffleBoundary
    public static Object[] sendArray(Callables.Target target, PhpArray array, Node site) {
        SpreadArguments list = new SpreadArguments();
        if (target.magicName() != null) {
            // __call's stand-in: names into its array, or none for Closure::fromCallable()'s (no variadic)
            list.addSendArray(array, target.closure() instanceof PhpClosure closure && !closure.isMagicVariadic()
                    ? MethodSite.NO_PARAMETERS : MethodSite.BY_VALUE, site);
            return target.boundArguments(list.magicValues(target.magicName()));
        }
        list.addSendArray(array, target.function(), site);
        Object[] frame = target.boundArguments(list.values.toArray());
        if (list.isNamed()) {
            list.enter(frame, target.function(), site);
        }
        return frame;
    }

    /** Where a warning is: the call's instruction, or for a built-in's call the PHP code calling it. */
    static SourceLocation location(Node site) {
        return site == null ? SourceLocation.current() : SourceLocation.of(site);
    }

    // ---- the call ----

    /** Whether a name left holes or extras, which the call must {@link #enter} (else the frame is the call's). */
    public boolean isNamed() {
        return holes || extraNames != null;
    }

    /**
     * Once the frame's first slots are set, before the call: the holes filled by the callee's defaults target
     * (ZEND_CHECK_UNDEF_ARGS), and the extras passed in the closure slot (NamedExtras), as BindingPlan.enter. site:
     * the call's instruction (null for a built-in's call).
     */
    @TruffleBoundary
    public void enter(Object[] frame, PhpFunction callee, Node site) {
        NamedExtras extras = null;
        if (extraNames != null) {
            NamedExtras.expect(callee);
            extras = new NamedExtras(frame[PhpFunction.ARG_CLOSURE], extraNames.toArray(new TruffleString[0]),
                    extraValues.toArray());
            frame[PhpFunction.ARG_CLOSURE] = extras;
        }
        if (holes) {
            // in the call's place, as php-src's fake frame is the call's
            Backtrace.callFrom(site, () -> BindingPlan.defaultsTarget(callee).call(frame));
            if (extras != null) {
                frame[PhpFunction.ARG_CLOSURE] = extras;
            }
        }
    }

    /**
     * __call's or __callStatic's two arguments (ZEND_CALL_TRAMPOLINE): the name as called, and the values in an
     * array, the named ones under their names after the positional ones; a reference call_user_func_array() passed
     * stays one.
     */
    @TruffleBoundary
    public Object[] magicValues(TruffleString name) {
        PhpArray arguments = new PhpArray();
        for (Object value : values) {
            arguments.appendCopied(value);
        }
        if (extraNames != null) {
            for (int i = 0; i < extraNames.size(); i++) {
                arguments.putCopied(extraNames.get(i), extraValues.get(i));
            }
        }
        return new Object[] {name, arguments};
    }
}
