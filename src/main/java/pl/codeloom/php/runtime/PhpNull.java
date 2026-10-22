package pl.codeloom.php.runtime;

import com.oracle.truffle.api.interop.TruffleObject;

/** PHP's null. A singleton, so `value == PhpNull.NULL` is the null test everywhere. */
public final class PhpNull implements TruffleObject {
    public static final PhpNull NULL = new PhpNull();

    private PhpNull() {
    }

    @Override
    public String toString() {
        return "NULL";
    }
}
