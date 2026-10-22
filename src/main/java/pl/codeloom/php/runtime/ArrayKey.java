package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;

/** Normalizes array keys as php-src does: canonical decimal strings become ints, bools/floats/null convert. */
public final class ArrayKey {
    private ArrayKey() {
    }

    /** The normalized key: a Long or a TruffleString. */
    public static Object of(Object key) {
        if (key instanceof Long) {
            return key;
        }
        if (key instanceof TruffleString s) {
            return ofString(s);
        }
        if (key instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        if (key instanceof Double d) {
            return Convert.doubleToLong(d);
        }
        if (key == PhpNull.NULL) {
            return Str.EMPTY;
        }
        throw new PhpError("Cannot access offset of type " + Convert.typeName(key) + " on array");
    }

    /** "123" and "-5" become ints; "0123", "1.5", " 1", "-0" and out-of-range digits stay strings. */
    @TruffleBoundary
    public static Object ofString(TruffleString s) {
        int length = Str.length(s);
        if (length == 0 || length > 20) {
            return s;
        }
        int first = Str.byteAt(s, 0);
        if (!(first >= '0' && first <= '9' || first == '-')) {
            return s;
        }
        byte[] bytes = Str.readBytes(s);
        int i = bytes[0] == '-' ? 1 : 0;
        if (i == length || bytes[i] == '0' && (length - i > 1 || i == 1)) {
            return s;
        }
        for (int j = i; j < length; j++) {
            if (bytes[j] < '0' || bytes[j] > '9') {
                return s;
            }
        }
        try {
            return Long.parseLong(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1));
        } catch (NumberFormatException overflow) {
            return s;
        }
    }
}
