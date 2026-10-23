package pl.codeloom.php.nodes;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.Str;

/**
 * PHP truthiness ({@link pl.codeloom.php.runtime.Convert#toBool}), specialized on the value's type: a site that
 * sees one type (nearly all of them) compiles to that one test.
 */
public abstract class ToBooleanNode extends Node {
    public abstract boolean execute(Object value);

    public static ToBooleanNode create() {
        return ToBooleanNodeGen.create();
    }

    @Specialization
    protected static boolean bool(boolean value) {
        return value;
    }

    @Specialization
    protected static boolean number(long value) {
        return value != 0;
    }

    @Specialization
    protected static boolean number(double value) {
        return value != 0.0;
    }

    /** "" and "0" are false. */
    @Specialization
    protected static boolean string(TruffleString value, @Cached TruffleString.ReadByteNode readByte) {
        int length = value.byteLength(Str.ENC);
        return !(length == 0 || length == 1 && readByte.execute(value, 0, Str.ENC) == '0');
    }

    @Specialization
    protected static boolean nil(PhpNull value) {
        return false;
    }

    @Specialization
    protected static boolean array(PhpArray value) {
        return value.size() > 0;
    }

    /** Objects and closures. */
    @Fallback
    protected static boolean other(Object value) {
        return true;
    }
}
