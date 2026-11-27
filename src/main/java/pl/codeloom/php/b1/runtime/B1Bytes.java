package pl.codeloom.php.b1.runtime;

import pl.codeloom.php.ir.AsciiCase;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** An immutable PHP byte string. Hashes and symbol-table keys survive repeated dispatches. */
public final class B1Bytes {
    private final byte[] bytes;
    private final int hash;
    private volatile String name, lower;
    private volatile Object arrayKey;

    private B1Bytes(byte[] owned) {
        bytes = owned;
        hash = Arrays.hashCode(bytes);
    }
    public static B1Bytes of(byte[] bytes) { return new B1Bytes(bytes.clone()); }
    public static B1Bytes ofUtf8(String text) {
        var result = new B1Bytes(text.getBytes(StandardCharsets.UTF_8));
        result.name = text;
        return result;
    }
    public String symbolKey() {
        String key = lower;
        if (key == null) lower = key = AsciiCase.lower(toString());
        return key;
    }
    /** PHP accepts only the canonical decimal spelling of a signed zend_long. */
    Object arrayKey() {
        Object key = arrayKey;
        if (key == null) arrayKey = key = parseKey();
        return key;
    }
    private Object parseKey() {
        if (bytes.length == 0) return this;
        boolean negative = bytes[0] == '-';
        int i = negative ? 1 : 0;
        if (i == bytes.length || bytes[i] == '0' && bytes.length != 1) return this;
        long limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        long value = 0;
        for (; i < bytes.length; i++) {
            int digit = bytes[i] - '0';
            if (digit < 0 || digit > 9 || value < limit / 10) return this;
            value *= 10;
            if (value < limit + digit) return this;
            value -= digit;
        }
        return negative ? value : -value;
    }
    public int length() { return bytes.length; }
    /** A copy of the bytes, for hosts. */
    public byte[] toByteArray() { return bytes.clone(); }
    boolean truth() { return bytes.length != 0 && !(bytes.length == 1 && bytes[0] == '0'); }
    @Override public int hashCode() { return hash; }
    @Override public boolean equals(Object other) { return other instanceof B1Bytes b && Arrays.equals(bytes, b.bytes); }
    @Override public String toString() {
        String text = name;
        if (text == null) name = text = new String(bytes, StandardCharsets.UTF_8);
        return text;
    }
    static byte[] bytes(Object string) {
        return string instanceof B1Bytes b ? b.bytes : ((String) string).getBytes(StandardCharsets.UTF_8);
    }
    public static B1Bytes concat(byte[] left, byte[] right) {
        byte[] joined = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right,0,joined,left.length,right.length);
        return new B1Bytes(joined);
    }
}
