package pl.codeloom.php.nodes;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.ReportPolymorphism;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.Str;

/**
 * A value as a PHP string ({@link Convert#toStr}), specialized on the value's type: strings pass through and ints
 * are formatted by a cached node; floats, booleans, null and objects (__toString) take the runtime conversion.
 */
@ReportPolymorphism
public abstract class ToStrNode extends Node {
    public abstract TruffleString execute(Object value);

    public static ToStrNode create() {
        return ToStrNodeGen.create();
    }

    @Specialization
    protected static TruffleString string(TruffleString value) {
        return value;
    }

    @Specialization
    protected static TruffleString number(long value, @Cached TruffleString.FromLongNode fromLong) {
        return fromLong.execute(value, Str.ENC, true);
    }

    @Fallback
    protected static TruffleString other(Object value) {
        return Convert.toStr(value);
    }
}
