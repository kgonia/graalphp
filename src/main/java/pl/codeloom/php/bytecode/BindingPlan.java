package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.IndirectCallNode;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.BuiltinSignature;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * How a call with named arguments binds them to one callee: php-src's zend_handle_named_arg for the whole argument
 * list at once, each failure recorded at its argument. Pure and immutable, computed once per (site, callee) outside
 * partial evaluation and cached in the site's operations after their callee identity check, so every array here is
 * a constant there and binding is a sequence of constant stores.
 *
 * <ul>
 * <li>A name is looked up case-sensitively among the callee's parameters before a variadic one
 * (zend_get_arg_offset_by_name). Found at slot i: the frame grows to i + 1, the slots skipped are holes, and a slot
 * already set is "Named parameter $x overwrites previous argument".</li>
 * <li>Not found, a variadic callee collects it under its name (extra_named_params: an extra; a second one of the
 * name overwrites too); else "Unknown named parameter $x".</li>
 * <li>Whether an argument goes by reference is its slot's (ARG_SHOULD_BE_SENT_BY_REF), an extra's the variadic's.</li>
 * <li>num_args, what func_num_args() counts, is the highest slot bound plus one; extras never count.</li>
 * </ul>
 */
public final class BindingPlan {
    /** An argument the variadic parameter collects under its name. */
    static final int EXTRA = -1;

    /** Per argument in source order: its parameter (its frame position less ARG_FIRST), or EXTRA. */
    @CompilationFinal(dimensions = 1) private final int[] slot;
    @CompilationFinal(dimensions = 1) private final boolean[] byRef;
    /** The frame arguments' length: ARG_FIRST + num_args. */
    private final int length;
    /** The parameters below num_args no argument is bound to, in order: the defaults target fills them. */
    @CompilationFinal(dimensions = 1) private final int[] holes;
    /** The extras' names, in the order of their arguments. */
    @CompilationFinal(dimensions = 1) private final TruffleString[] extraNames;
    /** Every argument lands on its own position and nothing is left out: the arguments are the frame's. */
    private final boolean identity;
    /** A constant expression's `new` (see NamedSite). */
    private final boolean constant;
    /** The argument whose send throws (-1 for none), and the message. */
    private final int sendErrorAt;
    private final String sendError;
    /** A constant expression's: the argument whose binding fails once all are evaluated (-1 for none). */
    private final int callErrorAt;
    private final String callError;
    /** The variadic parameter's index, for an extra's messages; -1 if the callee has none. */
    private final int variadic;
    /** Whether the callee's root has been told to look for extras (NamedExtras.expect), on the first call. */
    @CompilationFinal private boolean extrasExpected;

    private BindingPlan(int[] slot, boolean[] byRef, int length, int[] holes, TruffleString[] extraNames,
                        boolean constant, int sendErrorAt, String sendError, int callErrorAt, String callError,
                        int variadic) {
        this.slot = slot;
        this.byRef = byRef;
        this.length = length;
        this.holes = holes;
        this.extraNames = extraNames;
        this.constant = constant;
        this.sendErrorAt = sendErrorAt;
        this.sendError = sendError;
        this.callErrorAt = callErrorAt;
        this.callError = callError;
        this.variadic = variadic;
        boolean same = holes.length == 0 && extraNames.length == 0 && length == PhpFunction.ARG_FIRST + slot.length;
        for (int k = 0; k < slot.length && same; k++) {
            same = slot[k] == k;
        }
        this.identity = same;
    }

    /** The binding of site's arguments to callee (zend_get_arg_offset_by_name, zend_handle_named_arg). */
    static BindingPlan of(NamedSite site, PhpFunction callee) {
        TruffleString[] names = site.names();
        int count = names.length;
        int positional = site.positional();
        PhpFunction.Param[] params = callee.getParams();
        boolean hasVariadic = callee == MethodSite.BY_VALUE ? !site.isConstruct() : isVariadic(callee);
        int variadic = callee == MethodSite.BY_VALUE ? -1 : variadicIndex(callee);
        int[] slot = new int[count];
        boolean[] byRef = new boolean[count];
        BuiltinSignature signature = callee.getBuiltin() != null ? callee.getBuiltinSignature() : null;
        int declared = signature != null ? signature.names().length : params.length;
        boolean[] bound = new boolean[Math.max(count, declared) + 1];
        int numArgs = positional;
        for (int k = 0; k < positional; k++) {
            slot[k] = k;
            byRef[k] = callee.isByRef(k);
            if (k < bound.length) {
                bound[k] = true;
            }
        }
        List<TruffleString> extras = new ArrayList<>();
        int errorAt = -1;
        String error = null;
        for (int k = positional; k < count && errorAt < 0; k++) {
            String name = Str.java(names[k]);
            int index = parameterIndex(callee, name);
            if (index >= 0) {
                if (bound[index]) {
                    errorAt = k;
                    error = overwrites(name);
                }
                slot[k] = index;
                byRef[k] = callee.isByRef(index);
                bound[index] = true;
                numArgs = Math.max(numArgs, index + 1);
            } else if (hasVariadic) {
                if (extras.contains(names[k])) {
                    errorAt = k;
                    error = overwrites(name);
                }
                slot[k] = EXTRA;
                byRef[k] = variadic >= 0 && callee.isByRef(variadic);
                extras.add(names[k]);
            } else {
                errorAt = k;
                error = "Unknown named parameter $" + name;
            }
        }
        List<Integer> holes = new ArrayList<>();
        for (int i = 0; i < numArgs; i++) {
            if (!bound[i]) {
                holes.add(i);
            }
        }
        int[] holeArray = new int[holes.size()];
        for (int i = 0; i < holeArray.length; i++) {
            holeArray[i] = holes.get(i);
        }
        int sendErrorAt = errorAt;
        String sendError = error;
        int callErrorAt = -1;
        String callError = null;
        if (site.isConstant()) {
            // zend_ast_evaluate: a name given twice fails as its argument is evaluated; the rest once all are
            sendErrorAt = site.duplicate();
            sendError = sendErrorAt < 0 ? null : overwrites(Str.java(names[sendErrorAt]));
            callErrorAt = errorAt;
            callError = error;
        }
        return new BindingPlan(slot, byRef, PhpFunction.ARG_FIRST + numArgs, holeArray,
                extras.toArray(new TruffleString[0]), site.isConstant(), sendErrorAt, sendError, callErrorAt,
                callError, variadic);
    }

    static String overwrites(String name) {
        return "Named parameter $" + name + " overwrites previous argument";
    }

    /**
     * zend_get_arg_offset_by_name: the index of callee's parameter called name (case-sensitively), among the num_args
     * before a variadic one; -1 for none. None for a class without a constructor or __call standing in for a method.
     * A built-in's names are its arginfo's (BuiltinSignature); one php-src doesn't have binds none.
     */
    static int parameterIndex(PhpFunction callee, String name) {
        if (callee == MethodSite.BY_VALUE) {
            return -1;
        }
        if (callee.getBuiltin() != null) {
            BuiltinSignature signature = callee.getBuiltinSignature();
            return signature == null ? -1 : signature.indexOf(name);
        }
        PhpFunction.Param[] params = callee.getParams();
        int declared = isVariadic(callee) ? params.length - 1 : params.length;
        for (int i = 0; i < declared; i++) {
            if (params[i].name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /** Whether callee's last parameter is variadic (ZEND_ACC_VARIADIC); a built-in's as its arginfo says. */
    static boolean isVariadic(PhpFunction callee) {
        if (callee.getBuiltin() != null && callee.getBuiltinSignature() != null) {
            return callee.getBuiltinSignature().isVariadic();
        }
        PhpFunction.Param[] params = callee.getParams();
        return params.length > 0 && params[params.length - 1].variadic();
    }

    /** The index of callee's variadic parameter (num_args), -1 for none. */
    static int variadicIndex(PhpFunction callee) {
        if (!isVariadic(callee)) {
            return -1;
        }
        BuiltinSignature signature = callee.getBuiltin() != null ? callee.getBuiltinSignature() : null;
        return signature != null ? signature.declared() : callee.getParams().length - 1;
    }

    // ---- the sends ----

    /** The argument's send: the Error where its binding fails (constant per site and callee). */
    void checkSend(int argument) {
        if (argument == sendErrorAt) {
            throw error(sendError);
        }
    }

    /** Whether the argument goes by reference, as its slot's parameter (or the variadic one) takes it. */
    boolean takesRef(int argument) {
        return byRef[argument] && !constant;
    }

    /**
     * What a place argument's send (SendElement, SendProp... with position 0) asks about its parameter: a callee
     * taking it by reference or by value as the binding says. A constant expression's arguments are values.
     */
    PhpFunction sendTarget(int argument) {
        return takesRef(argument) ? NamedSite.BY_REFERENCE : MethodSite.BY_VALUE;
    }

    /** The parameter a by-reference error names: the argument's, or for an extra the variadic one. */
    int parameter(int argument) {
        return slot[argument] == EXTRA ? variadic : slot[argument];
    }

    // ---- the call ----

    /**
     * The frame arguments of the call, from the arguments as the call took them (in source order, after the first
     * slots): each at its parameter's position, the holes NOT_PASSED, the extras left for {@link #enter}. When
     * nothing moves, the same array.
     */
    @ExplodeLoop
    Object[] frame(Object[] arguments) {
        if (identity) {
            return arguments;
        }
        Object[] frame = new Object[length];
        for (int k = 0; k < slot.length; k++) {
            if (slot[k] != EXTRA) {
                frame[PhpFunction.ARG_FIRST + slot[k]] = arguments[PhpFunction.ARG_FIRST + k];
            }
        }
        for (int i = 0; i < holes.length; i++) {
            frame[PhpFunction.ARG_FIRST + holes[i]] = PhpFunction.NOT_PASSED;
        }
        return frame;
    }

    /**
     * Once the frame's first slots are set, before the call: the holes filled (ZEND_CHECK_UNDEF_ARGS, after every
     * argument is sent and before the callee runs), and the extras passed in the closure slot (NamedExtras). The
     * defaults target sees them, as php-src's fake frame is the call's, and puts the slot back: they go in again.
     */
    void enter(Object[] frame, Object[] arguments, PhpFunction callee, DirectCallNode defaults) {
        if (extraNames.length == 0) {
            if (holes.length != 0) {
                defaults.call(frame);
            }
            return;
        }
        NamedExtras extras = extras(frame[PhpFunction.ARG_CLOSURE], arguments, callee);
        frame[PhpFunction.ARG_CLOSURE] = extras;
        if (holes.length != 0) {
            defaults.call(frame);
            frame[PhpFunction.ARG_CLOSURE] = extras;
        }
    }

    void enter(Object[] frame, Object[] arguments, PhpFunction callee, IndirectCallNode callNode) {
        if (extraNames.length == 0) {
            if (holes.length != 0) {
                callNode.call(defaultsTarget(callee), frame);
            }
            return;
        }
        NamedExtras extras = extras(frame[PhpFunction.ARG_CLOSURE], arguments, callee);
        frame[PhpFunction.ARG_CLOSURE] = extras;
        if (holes.length != 0) {
            callNode.call(defaultsTarget(callee), frame);
            frame[PhpFunction.ARG_CLOSURE] = extras;
        }
    }

    /** The extras' values in their order (as sent: references for a by-reference variadic). */
    @ExplodeLoop
    private Object[] extraValues(Object[] arguments) {
        Object[] values = new Object[extraNames.length];
        int e = 0;
        for (int k = 0; k < slot.length; k++) {
            if (slot[k] == EXTRA) {
                values[e++] = arguments[PhpFunction.ARG_FIRST + k];
            }
        }
        return values;
    }

    /** The call's extras for the closure slot holding slotValue; the callee told to look for them first. */
    private NamedExtras extras(Object slotValue, Object[] arguments, PhpFunction callee) {
        if (!extrasExpected) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            NamedExtras.expect(callee);
            extrasExpected = true;
        }
        return new NamedExtras(slotValue, extraNames, extraValues(arguments));
    }

    /**
     * __call's or __callStatic's arguments (ZEND_CALL_TRAMPOLINE): the method's name as called, and an array of the
     * values, the positional ones (in frame) then every named one under its name.
     */
    Object[] magicValues(TruffleString name, Object[] frame, Object[] arguments) {
        Object[] positional = new Object[frame.length - PhpFunction.ARG_FIRST];
        System.arraycopy(frame, PhpFunction.ARG_FIRST, positional, 0, positional.length);
        return new Object[] {name, NamedExtras.magicArguments(positional, extraNames, extraValues(arguments))};
    }

    /** The call of callee's defaults target the plan needs, made with the operation's cache entry; or null. */
    DirectCallNode defaultsCall(PhpFunction callee) {
        return holes.length == 0 ? null : DirectCallNode.create(defaultsTarget(callee));
    }

    /**
     * The target filling the holes: a user function's (zend_handle_undef_args' RECV_INIT defaults and "not
     * passed"), or a built-in's (its arginfo defaults, BuiltinDefaultsRootNode). A built-in php-src doesn't have binds
     * no name, so leaves no hole.
     */
    @TruffleBoundary
    static RootCallTarget defaultsTarget(PhpFunction callee) {
        RootCallTarget target = callee.getDefaultsTarget();
        if (target == null) {
            throw new PhpError("Unsupported named arguments leaving out parameters of the built-in "
                    + callee.displayName() + "()");
        }
        return target;
    }

    /**
     * A constant expression's `new` once its arguments are evaluated (zend_call_function's named_params): each
     * argument bound in order, a value for a by-reference parameter passed in a new reference after php-src's
     * warning, and the first failing binding's Error.
     */
    @TruffleBoundary
    void bindConstant(Object[] arguments, PhpFunction callee, Node node) {
        for (int k = 0; k < slot.length; k++) {
            if (k == callErrorAt) {
                throw error(callError);
            }
            Object value = arguments[PhpFunction.ARG_FIRST + k];
            if (byRef[k] && !(value instanceof PhpRef) && !callee.isPreferRef()) {
                mustBeRef(callee, parameter(k), node);
                arguments[PhpFunction.ARG_FIRST + k] = new PhpRef(value);
            }
        }
    }

    /** zend_param_must_be_ref's warning: a variadic parameter's argument unnamed (get_function_arg_name). */
    @TruffleBoundary
    public static void mustBeRef(PhpFunction callee, int index, Node node) {
        String name = index >= 0 ? callee.argumentName(index) : null;
        ErrorFunctions.raise(PhpContext.get(node), ErrorFunctions.E_WARNING, Str.of(callee.displayName()
                + "(): Argument #" + (index + 1) + (name == null ? "" : " ($" + name + ")")
                + " must be passed by reference, value given"), SpreadArguments.location(node));
    }

    @TruffleBoundary
    private static PhpError error(String message) {
        return new PhpError(message);
    }

    /** "f(): Argument #2 ($b) not passed": a required parameter a call by name left out (in its fake frame). */
    @TruffleBoundary
    static PhpError notPassed(String function, PhpFunction.Param[] params, int index) {
        return new PhpError("ArgumentCountError", function + "(): Argument #" + (index + 1) + " ($"
                + params[index].name() + ") not passed");
    }

    @Override
    public String toString() {
        return "plan" + Arrays.toString(slot) + " holes " + Arrays.toString(holes) + " length " + length;
    }
}
