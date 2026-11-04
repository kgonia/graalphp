package pl.codeloom.php.nodes.call;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.PhpLanguage;
import pl.codeloom.php.builtins.BuiltinSignature;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

/**
 * A built-in's defaults target (PhpFunction.getDefaultsTarget): the holes a call by name left in its frame
 * arguments filled before the built-in runs, as zend_handle_undef_args does for an internal function, in a fake frame
 * of the built-in (traces list it with the holes as NULL): each hole of a required parameter is "Argument #n ($x)
 * not passed", of one whose default php-src doesn't know "must be passed explicitly, because the default value is
 * not known"; any other takes its arginfo default, in a new reference for a by-reference parameter.
 */
public final class BuiltinDefaultsRootNode extends RootNode implements Backtrace.Callee {
    private final PhpFunction function;
    private final BuiltinSignature signature;

    public BuiltinDefaultsRootNode(PhpLanguage language, PhpFunction function, BuiltinSignature signature) {
        super(language);
        this.function = function;
        this.signature = signature;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        fill(frame.getArguments());
        return null;
    }

    @TruffleBoundary
    private void fill(Object[] arguments) {
        for (int i = PhpFunction.ARG_FIRST; i < arguments.length; i++) {
            if (arguments[i] != PhpFunction.NOT_PASSED) {
                continue;
            }
            int index = i - PhpFunction.ARG_FIRST;
            Object value = signature.defaultValue(index);
            if (value == BuiltinSignature.REQUIRED || value == BuiltinSignature.UNKNOWN) {
                throw new PhpError("ArgumentCountError", builtin().messageName(arguments) + "(): Argument #"
                        + (index + 1) + " ($" + signature.name(index) + ") "
                        + (value == BuiltinSignature.REQUIRED ? "not passed"
                                : "must be passed explicitly, because the default value is not known"));
            }
            if (value instanceof BuiltinSignature.EnumCase enumCase) {
                value = PhpContext.get(this).findClass(Str.lower(enumCase.className()))
                        .getConstant(enumCase.caseName());
            }
            arguments[i] = signature.isByRef(index) ? new PhpRef(value) : value;
        }
    }

    private BuiltinRootNode builtin() {
        return (BuiltinRootNode) function.getCallTarget().getRootNode();
    }

    /** The built-in's frame, as php-src's fake one: its name and the arguments so far, a hole NULL. */
    @Override
    public boolean describe(Frame frame, PhpArray entry, int options, Backtrace.CallSite site) {
        return builtin().describe(frame, entry, options, site);
    }

    @Override
    public SourceSection getSourceSection() {
        return builtin().getSourceSection();
    }

    @Override
    public String getName() {
        return builtin().getName();
    }
}
