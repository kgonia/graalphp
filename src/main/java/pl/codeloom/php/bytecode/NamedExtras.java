package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.builtins.BuiltinSignature;
import pl.codeloom.php.nodes.call.BuiltinRootNode;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpRef;

/**
 * php-src's extra_named_params on their way into a variadic user function: the named arguments no parameter took,
 * which its variadic parameter collects under their names after the positional extras (ZEND_RECV_VARIADIC). The
 * call passes them in the frame's ARG_CLOSURE slot, in place of the slot's own value (a closure, an include's scope
 * or null), and the callee's first instruction, TakeNamedExtras, puts that value back and keeps this in a hidden
 * local for BindNamedExtras and the traces: only a variadic function receives one, so no other prologue looks.
 *
 * <p>Whether a function's TakeNamedExtras does anything is an Assumption on its root, invalidated when the first
 * call with extras for it is bound ({@link #expect}): until then its compiled code is as if there were none.
 *
 * <p>The values stay out of a PHP array until the variadic's array takes them, so nothing here holds a count (the
 * call frame's extra_named_params is freed with the call).
 */
public final class NamedExtras {
    /** What the slot holds for the call. */
    private final Object slot;
    /** The extras' names in the order they were passed, and their values: references for a by-reference variadic. */
    private final TruffleString[] names;
    private final Object[] values;

    NamedExtras(Object slot, TruffleString[] names, Object[] values) {
        this.slot = slot;
        this.names = names;
        this.values = values;
    }

    Object slot() {
        return slot;
    }

    int count() {
        return values.length;
    }

    TruffleString name(int i) {
        return names[i];
    }

    Object value(int i) {
        return values[i];
    }

    /**
     * A call is about to pass callee extras: its root's TakeNamedExtras must look for them from now on. A callee
     * that isn't on bytecode (the tree's) can't take them.
     */
    @TruffleBoundary
    static void expect(PhpFunction callee) {
        if (callee.getBuiltin() != null) {
            // a forwarding built-in takes them on, any other variadic one throws "f() does not accept unknown named
            // parameters" once its argument count is checked
            BuiltinSignature signature = callee.getBuiltinSignature();
            ((BuiltinRootNode) callee.getCallTarget().getRootNode()).expectNamedExtras(
                    signature != null && signature.isForwarding());
            return;
        }
        RootNode root = callee.getCallTarget().getRootNode();
        if (!(root instanceof PhpBytecodeRootNode function) || !function.expectNamedExtras()) {
            throw new PhpError("Unsupported named arguments collected by the variadic parameter of "
                    + callee.displayName() + "(), which isn't on bytecode");
        }
    }

    /** The extras as trace arguments under their names, after the positional ones (debug_backtrace_get_args). */
    @TruffleBoundary
    public void describe(PhpArray args) {
        for (int i = 0; i < values.length; i++) {
            args.put(names[i], PhpRef.deref(values[i]));
        }
    }

    /**
     * A forwarding built-in's arguments from position from on, for zend_call_function: the positional ones, then the
     * extras a call passed it (Z_PARAM_VARIADIC_WITH_NAMED's named_params) under their names; null if it passed
     * none (the built-in calls on positionally).
     */
    @TruffleBoundary
    public static PhpArray forwarded(Object[] frame, int from) {
        if (!(frame[PhpFunction.ARG_CLOSURE] instanceof NamedExtras extras)) {
            return null;
        }
        PhpArray arguments = new PhpArray();
        for (int i = PhpFunction.ARG_FIRST + from; i < frame.length; i++) {
            arguments.appendCopied(frame[i]);
        }
        for (int i = 0; i < extras.values.length; i++) {
            arguments.putCopied(extras.names[i], extras.values[i]);
        }
        return arguments;
    }

    /** __call's and __callStatic's arguments array: the positional values, then the extras under their names. */
    @TruffleBoundary
    static PhpArray magicArguments(Object[] positional, TruffleString[] names, Object[] values) {
        PhpArray arguments = new PhpArray();
        for (Object value : positional) {
            arguments.appendCopied(value);
        }
        for (int i = 0; i < values.length; i++) {
            arguments.putCopied(names[i], values[i]);
        }
        return arguments;
    }
}
