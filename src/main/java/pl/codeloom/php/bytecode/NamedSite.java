package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.PhpFunction;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A call with named arguments, `f($a, c: 1, b: 2)`: the constant operand of its named sends and of its call. php-src
 * binds each name when its SEND runs (zend_handle_named_arg); here the whole list is bound at once against each
 * callee the site meets ({@link BindingPlan}), and the sends and the call follow the plan.
 *
 * <p>The positional arguments before the first name keep their positional sends: their slots are their positions.
 */
public final class NamedSite {
    /**
     * What a named place argument's send asks about its parameter when the plan says by reference (NamedTarget): a
     * function whose every parameter is by reference. {@link MethodSite#BY_VALUE} stands for by value.
     */
    static final PhpFunction BY_REFERENCE = new PhpFunction("{by reference}", new PhpFunction.Param[] {
            new PhpFunction.Param("", true, true, null, null, true, null)}, false, false, true, false, () -> null);

    /** Each argument's name in source order, interned (php-src's); null for the positional ones. */
    @CompilationFinal(dimensions = 1) private final TruffleString[] names;
    /** How many positional arguments come before the first name. */
    private final int positional;
    /** A `new`: the callee {@link MethodSite#BY_VALUE} is a class without a constructor (zend_pass_function). */
    private final boolean construct;
    /**
     * A constant expression's `new` (zend_ast_evaluate): the arguments are all evaluated first, a name given twice
     * failing at its evaluation, then bound by zend_call_function.
     */
    private final boolean constant;
    /** In a constant expression, the first argument whose name an earlier one has, else -1. */
    private final int duplicate;
    /** The plans by callee, for the operations' generic specializations; functions are shared by contexts. */
    private final Map<PhpFunction, BindingPlan> plans = Collections.synchronizedMap(new WeakHashMap<>());

    public NamedSite(TruffleString[] names, boolean construct, boolean constant) {
        this.names = names;
        int first = 0;
        while (first < names.length && names[first] == null) {
            first++;
        }
        this.positional = first;
        this.construct = construct;
        this.constant = constant;
        int repeated = -1;
        for (int k = first + 1; k < names.length && repeated < 0; k++) {
            for (int j = first; j < k; j++) {
                if (names[j].equals(names[k])) {
                    repeated = k;
                    break;
                }
            }
        }
        this.duplicate = repeated;
    }

    /** The plan for calling callee from this site, computed once per callee. */
    @TruffleBoundary
    public BindingPlan plan(PhpFunction callee) {
        return plans.computeIfAbsent(callee, function -> BindingPlan.of(this, function));
    }

    TruffleString[] names() {
        return names;
    }

    public int positional() {
        return positional;
    }

    boolean isConstruct() {
        return construct;
    }

    boolean isConstant() {
        return constant;
    }

    int duplicate() {
        return duplicate;
    }

    @Override
    public String toString() {
        StringBuilder text = new StringBuilder("named(");
        for (int k = 0; k < names.length; k++) {
            text.append(k == 0 ? "" : ", ").append(names[k] == null ? "_" : names[k] + ":");
        }
        return text.append(')').toString();
    }
}
