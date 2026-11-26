package pl.codeloom.php.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Real B1 PHP byte-string: immutable raw byte[] with cached hash (no UTF-16 java.lang.String cheat).
 * Supports arbitrary binary bytes including NUL (\0) and UTF-8 sequences with O(1) byte length.
 * Single-digit cache ('0'..'9') mirrors php-src's ZSTR_CHAR 1-byte interned table; multi-digit ints
 * always allocate fresh bytes (no unfair cache advantage for "hot_hook_10".."hot_hook_39").
 */
public final class ByteStr {
    public static final ByteStr EMPTY = new ByteStr(new byte[0]);
    private static final ByteStr[] DIGITS = new ByteStr[10];
    static {
        for (int i = 0; i < 10; i++) {
            DIGITS[i] = new ByteStr(new byte[]{(byte) ('0' + i)});
        }
    }

    private final byte[] bytes;
    private volatile ByteStr lower;
    private int hash;
    private volatile boolean hashComputed;

    private ByteStr(byte[] bytes) {
        this.bytes = bytes;
    }

    public static ByteStr wrapOwned(byte[] bytes) {
        return bytes.length == 0 ? EMPTY : new ByteStr(bytes);
    }

    public static ByteStr copyOf(byte[] bytes, int from, int to) {
        int len = to - from;
        if (len <= 0) return EMPTY;
        if (from == 0 && to == bytes.length) return new ByteStr(bytes.clone());
        return new ByteStr(Arrays.copyOfRange(bytes, from, to));
    }

    public static ByteStr of(String latin1) {
        int len = latin1.length();
        if (len == 0) return EMPTY;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) latin1.charAt(i);
        }
        return new ByteStr(out);
    }

    public static ByteStr ofUtf8(String s) {
        if (s.isEmpty()) return EMPTY;
        return new ByteStr(s.getBytes(StandardCharsets.UTF_8));
    }

    /** Exact decimal formatting to raw ASCII bytes; only single digits 0..9 are interned (matching php-src ZSTR_CHAR). */
    public static ByteStr ofLong(long value) {
        if (value >= 0L && value <= 9L) {
            return DIGITS[(int) value];
        }
        boolean neg = value < 0;
        long v = neg ? value : -value; // Long.MIN_VALUE has no positive counterpart
        byte[] buf = new byte[20];
        int pos = 20;
        while (v < 0) {
            long q = v / 10;
            buf[--pos] = (byte) ('0' - (v - q * 10));
            v = q;
        }
        if (neg) buf[--pos] = '-';
        return new ByteStr(Arrays.copyOfRange(buf, pos, 20));
    }

    public int length() {
        return bytes.length;
    }

    public boolean isEmpty() {
        return bytes.length == 0;
    }

    public int byteAt(int index) {
        return bytes[index] & 0xFF;
    }

    /** Package-private read-only backing array access (callers must never mutate). */
    byte[] rawBytes() {
        return bytes;
    }

    @Override
    public int hashCode() {
        if (!hashComputed) {
            int h = 0;
            byte[] b = bytes;
            for (int i = 0; i < b.length; i++) {
                h = 31 * h + (b[i] & 0xFF);
            }
            hash = h;
            hashComputed = true;
        }
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof ByteStr other)) return false;
        if (bytes.length != other.bytes.length) return false;
        if (hashComputed && other.hashComputed && hash != other.hash) return false;
        return Arrays.equals(bytes, other.bytes);
    }

    public ByteStr concat(ByteStr other) {
        if (bytes.length == 0) return other;
        if (other.bytes.length == 0) return this;
        byte[] out = new byte[bytes.length + other.bytes.length];
        System.arraycopy(bytes, 0, out, 0, bytes.length);
        System.arraycopy(other.bytes, 0, out, bytes.length, other.bytes.length);
        return new ByteStr(out);
    }

    public static ByteStr concat3(ByteStr a, ByteStr b, ByteStr c) {
        int len = a.bytes.length + b.bytes.length + c.bytes.length;
        if (len == 0) return EMPTY;
        byte[] out = new byte[len];
        System.arraycopy(a.bytes, 0, out, 0, a.bytes.length);
        System.arraycopy(b.bytes, 0, out, a.bytes.length, b.bytes.length);
        System.arraycopy(c.bytes, 0, out, a.bytes.length + b.bytes.length, c.bytes.length);
        return new ByteStr(out);
    }

    /** PHP ASCII case-folding (zend_str_tolower). */
    public ByteStr toLowerAscii() {
        ByteStr cached = lower;
        return cached != null ? cached : cacheLowerAscii();
    }

    // Literal strings may be shared by requests. Only the first lookup needs the lock.
    private synchronized ByteStr cacheLowerAscii() {
        if (lower != null) return lower;
        byte[] b = bytes;
        int firstUpper = -1;
        for (int i = 0; i < b.length; i++) {
            int c = b[i] & 0xFF;
            if (c >= 'A' && c <= 'Z') {
                firstUpper = i;
                break;
            }
        }
        if (firstUpper < 0) return lower = this;
        byte[] out = b.clone();
        for (int i = firstUpper; i < out.length; i++) {
            int c = out[i] & 0xFF;
            if (c >= 'A' && c <= 'Z') out[i] = (byte) (c + 32);
        }
        ByteStr folded = new ByteStr(out);
        folded.lower = folded;
        return lower = folded;
    }

    public int indexOfDoubleColon() {
        byte[] b = bytes;
        int limit = b.length - 1;
        for (int i = 0; i < limit; i++) {
            if (b[i] == ':' && b[i + 1] == ':') return i;
        }
        return -1;
    }

    public ByteStr slice(int start, int end) {
        if (start == 0 && end == bytes.length) return this;
        if (end <= start) return EMPTY;
        return new ByteStr(Arrays.copyOfRange(bytes, start, end));
    }

    /** PHP ucfirst(): uppercase byte 0 if ASCII 'a'..'z'. */
    public ByteStr ucfirst() {
        if (bytes.length == 0) return this;
        int c = bytes[0] & 0xFF;
        if (c < 'a' || c > 'z') return this;
        byte[] out = bytes.clone();
        out[0] = (byte) (c - 32);
        return new ByteStr(out);
    }

    /** PHP str_replace() for single byte-string search/replacement. */
    public ByteStr replace(ByteStr search, ByteStr replacement) {
        byte[] src = bytes;
        byte[] pat = search.bytes;
        int srcLen = src.length;
        int patLen = pat.length;
        if (patLen == 0 || srcLen < patLen) return this;
        int first = indexOfBytes(src, 0, pat);
        if (first < 0) return this;
        byte[] rep = replacement.bytes;
        int repLen = rep.length;
        byte[] out = new byte[repLen <= patLen ? srcLen : Math.max(srcLen + (repLen - patLen) * 4, 16)];
        int outPos = 0;
        int pos = 0;
        int match = first;
        while (match >= 0) {
            int chunk = match - pos;
            int need = outPos + chunk + repLen;
            if (need > out.length) {
                out = Arrays.copyOf(out, Math.max(out.length * 2, need));
            }
            if (chunk > 0) {
                System.arraycopy(src, pos, out, outPos, chunk);
                outPos += chunk;
            }
            if (repLen > 0) {
                System.arraycopy(rep, 0, out, outPos, repLen);
                outPos += repLen;
            }
            pos = match + patLen;
            match = indexOfBytes(src, pos, pat);
        }
        int tail = srcLen - pos;
        if (tail > 0) {
            if (outPos + tail > out.length) {
                out = Arrays.copyOf(out, outPos + tail);
            }
            System.arraycopy(src, pos, out, outPos, tail);
            outPos += tail;
        }
        if (outPos == 0) return EMPTY;
        return new ByteStr(outPos == out.length ? out : Arrays.copyOf(out, outPos));
    }

    private static int indexOfBytes(byte[] src, int from, byte[] pat) {
        int patLen = pat.length;
        int max = src.length - patLen;
        byte first = pat[0];
        for (int i = from; i <= max; i++) {
            if (src[i] != first) continue;
            int j = 1;
            while (j < patLen && src[i + j] == pat[j]) j++;
            if (j == patLen) return i;
        }
        return -1;
    }

    /** PHP 1-arg trim() on raw bytes. */
    public ByteStr trim() {
        byte[] b = bytes;
        int start = 0;
        int end = b.length;
        while (start < end && isDefaultTrimByte(b[start] & 0xFF)) start++;
        while (end > start && isDefaultTrimByte(b[end - 1] & 0xFF)) end--;
        return slice(start, end);
    }

    private static boolean isDefaultTrimByte(int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0 || c == 0x0B;
    }

    /** PHP 2-arg trim() on raw bytes, including '..' range syntax and invalid-range errors. */
    public ByteStr trim(ByteStr chars) {
        byte[] maskBytes = chars.bytes;
        if (maskBytes.length == 0) return this;
        boolean hasRange = false;
        for (int i = 0; i + 1 < maskBytes.length; i++) {
            if (maskBytes[i] == '.' && maskBytes[i + 1] == '.') {
                hasRange = true;
                break;
            }
        }
        if (!hasRange) {
            byte[] b = bytes;
            int start = 0;
            int end = b.length;
            while (start < end && containsByte(maskBytes, b[start])) start++;
            while (end > start && containsByte(maskBytes, b[end - 1])) end--;
            return slice(start, end);
        }
        boolean[] mask = new boolean[256];
        int len = maskBytes.length;
        for (int i = 0; i < len; i++) {
            int first = maskBytes[i] & 0xFF;
            if (i + 3 < len && maskBytes[i + 1] == '.' && maskBytes[i + 2] == '.'
                    && first <= (maskBytes[i + 3] & 0xFF)) {
                Arrays.fill(mask, first, (maskBytes[i + 3] & 0xFF) + 1, true);
                i += 3;
            } else if (i + 1 < len && maskBytes[i] == '.' && maskBytes[i + 1] == '.') {
                String reason = "";
                if (i == 0) reason = ", no character to the left of '..'";
                else if (i + 2 == len) reason = ", no character to the right of '..'";
                else if ((maskBytes[i - 1] & 0xFF) > (maskBytes[i + 2] & 0xFF))
                    reason = ", '..'-range needs to be incrementing";
                throw new PhpError("trim(): Invalid '..'-range" + reason);
            } else {
                mask[first] = true;
            }
        }
        byte[] b = bytes;
        int start = 0;
        int end = b.length;
        while (start < end && mask[b[start] & 0xFF]) start++;
        while (end > start && mask[b[end - 1] & 0xFF]) end--;
        return slice(start, end);
    }

    private static boolean containsByte(byte[] arr, byte target) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == target) return true;
        }
        return false;
    }

    public static ByteStr toByteStr(Object value) {
        if (value instanceof ByteStr s) return s;
        if (value instanceof Long n) return ofLong(n);
        if (value instanceof Boolean b) return b ? DIGITS[1] : EMPTY;
        if (value == PhpNull.NULL || value == null) return EMPTY;
        if (value instanceof String s) return of(s);
        throw new PhpError("Expected string-convertible value, got " + value.getClass().getSimpleName());
    }

    @Override
    public String toString() {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }
}

final class Str {
    static final ByteStr EMPTY = ByteStr.EMPTY;
    static ByteStr of(String s) { return ByteStr.of(s); }
    static int length(ByteStr s) { return s.length(); }
    static int byteAt(ByteStr s, int index) { return s.byteAt(index); }
    static byte[] readBytes(ByteStr s) { return s.rawBytes(); }
}
