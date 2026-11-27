package pl.codeloom.php.b1.runtime;

import pl.codeloom.php.ir.AsciiCase;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** An immutable PHP byte string. Hashes and symbol-table keys survive repeated dispatches.
 * Instances can be shared between requests (literals, caches): every lazily cached field is a benign race. The
 * cached values are immutable objects published through their final fields (String, Long, this, SymbolId), so
 * the fields need no volatile: a racing reader sees null (and computes the same value) or a complete one. */
public final class B1Bytes {
    private final byte[] bytes;
    private final int hash;
    private String name, lower;
    private Object arrayKey;

    static final B1Bytes EMPTY = new B1Bytes(new byte[0]);
    private static final B1Bytes[] SMALL_INTS = new B1Bytes[256];
    private static final B1Bytes[] SINGLE = new B1Bytes[256];
    static {
        for (int i = 0; i < 256; i++) {
            SMALL_INTS[i] = new B1Bytes(Integer.toString(i).getBytes(StandardCharsets.US_ASCII));
            SINGLE[i] = i < 10 ? SMALL_INTS[i] : new B1Bytes(new byte[]{(byte)i});
        }
    }

    private B1Bytes(byte[] owned) {
        bytes = owned;
        hash = Arrays.hashCode(bytes);
    }
    public static B1Bytes of(byte[] bytes) { return bytes.length == 0 ? EMPTY : new B1Bytes(bytes.clone()); }
    /** Takes ownership of a freshly built array. */
    static B1Bytes wrap(byte[] owned) {
        if (owned.length == 0) return EMPTY;
        if (owned.length == 1) return SINGLE[owned[0] & 0xff];
        return new B1Bytes(owned);
    }
    static B1Bytes single(byte b) { return SINGLE[b & 0xff]; }
    public static B1Bytes ofUtf8(String text) {
        var result = new B1Bytes(text.getBytes(StandardCharsets.UTF_8));
        result.name = text;
        return result;
    }
    /** The decimal spelling of an integer, cached for 0..255. */
    static B1Bytes ofLong(long value) {
        if (value >= 0 && value < 256) return SMALL_INTS[(int)value];
        return new B1Bytes(Long.toString(value).getBytes(StandardCharsets.US_ASCII));
    }
    /** The folded name's id in the last symbol registry that asked (an immutable pair, published whole). A
     * registry holds names only; another registry's request recomputes and replaces the pair. */
    private SymbolId symbol;
    private record SymbolId(pl.codeloom.php.b1.B1Symbols registry, int id) {}
    int symbol(B1RequestContext ctx) {
        var registry = ctx.symbols();
        SymbolId cached = symbol;
        if (cached != null && cached.registry() == registry) return cached.id();
        int id = registry.of(symbolKey());
        symbol = new SymbolId(registry, id);
        return id;
    }
    public String symbolKey() {
        String key = lower;
        if (key == null) lower = key = AsciiCase.lower(toString()).intern();
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
    /** The owned bytes; callers never write them. */
    byte[] raw() { return bytes; }
    boolean truth() { return bytes.length != 0 && !(bytes.length == 1 && bytes[0] == '0'); }
    @Override public int hashCode() { return hash; }
    @Override public boolean equals(Object other) {
        return this == other || other instanceof B1Bytes b && hash == b.hash && Arrays.equals(bytes, b.bytes);
    }
    @Override public String toString() {
        String text = name;
        if (text == null) name = text = new String(bytes, StandardCharsets.UTF_8);
        return text;
    }
    static byte[] bytes(Object string) {
        return string instanceof B1Bytes b ? b.bytes : ((String) string).getBytes(StandardCharsets.UTF_8);
    }
    public static B1Bytes concat(byte[] left, byte[] right) {
        if (left.length == 0) return wrap(right.length < 2 ? right : right.clone());
        byte[] joined = Arrays.copyOf(left, left.length + right.length);
        System.arraycopy(right,0,joined,left.length,right.length);
        return new B1Bytes(joined);
    }
    static B1Bytes concat(B1Bytes left, B1Bytes right) {
        if (left.bytes.length == 0) return right;
        if (right.bytes.length == 0) return left;
        return concat(left.bytes, right.bytes);
    }

    private static boolean trimByte(byte b) { return b == 0 || b == ' ' || b >= 9 && b <= 11 || b == 13; }
    /** trim() with the default characters; the same string when nothing is trimmed. */
    B1Bytes trim() {
        int start = 0, end = bytes.length;
        while (start < end && trimByte(bytes[start])) start++;
        while (start < end && trimByte(bytes[end-1])) end--;
        if (start == 0 && end == bytes.length) return this;
        return wrap(Arrays.copyOfRange(bytes,start,end));
    }
    /** ucfirst(): ASCII only, as PHP 8. */
    B1Bytes ucfirst() {
        if (bytes.length == 0 || bytes[0] < 'a' || bytes[0] > 'z') return this;
        byte[] copy = bytes.clone();
        copy[0] -= 32;
        return wrap(copy);
    }
    /** str_replace() of one non-empty needle in one subject; the same string when the needle is absent. */
    B1Bytes replace(byte[] search, byte[] replacement) {
        int first = indexOf(bytes, search, 0);
        if (first < 0) return this;
        var out = new java.io.ByteArrayOutputStream(bytes.length + Math.max(0, replacement.length - search.length) * 4);
        int from = 0;
        for (int at = first; at >= 0; at = indexOf(bytes, search, from)) {
            out.write(bytes, from, at - from);
            out.writeBytes(replacement);
            from = at + search.length;
        }
        out.write(bytes, from, bytes.length - from);
        return wrap(out.toByteArray());
    }
    static int indexOf(byte[] haystack, byte[] needle, int from) {
        int last = haystack.length - needle.length;
        byte first = needle[0];
        for (int i = from; i <= last; i++) {
            if (haystack[i] != first) continue;
            if (Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) return i;
        }
        return -1;
    }
}
