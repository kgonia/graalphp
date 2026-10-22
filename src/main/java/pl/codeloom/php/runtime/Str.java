package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.InternalByteArray;
import com.oracle.truffle.api.strings.TruffleString;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PHP strings are byte strings, held as {@link TruffleString}s in the BYTES encoding. These helpers are for code
 * outside compiled hot paths (built-ins, conversions); hot nodes use cached TruffleString nodes instead.
 */
public final class Str {
    public static final TruffleString.Encoding ENC = TruffleString.Encoding.BYTES;
    public static final TruffleString EMPTY = TruffleString.Encoding.BYTES.getEmpty();

    /** One-byte strings for string offsets and chr(), shared so `$s[$i]` allocates nothing. */
    private static final TruffleString[] SINGLE_BYTES = new TruffleString[256];

    static {
        for (int i = 0; i < 256; i++) {
            SINGLE_BYTES[i] = TruffleString.fromByteArrayUncached(new byte[]{(byte) i}, ENC, false);
        }
    }

    private Str() {
    }

    public static TruffleString singleByte(int b) {
        return SINGLE_BYTES[b & 0xFF];
    }

    /** Interned literals, so equal literals across files are one object (array keys then match by identity). */
    private static final ConcurrentHashMap<TruffleString, TruffleString> LITERALS = new ConcurrentHashMap<>();

    /** The canonical instance of a literal string. */
    @TruffleBoundary
    public static TruffleString intern(TruffleString s) {
        TruffleString existing = LITERALS.putIfAbsent(s, s);
        return existing != null ? existing : s;
    }

    @TruffleBoundary
    public static TruffleString of(String javaString) {
        return TruffleString.fromByteArrayUncached(javaString.getBytes(StandardCharsets.UTF_8), ENC, false);
    }

    /** Wraps the array without copying; callers must not modify it afterwards. */
    @TruffleBoundary
    public static TruffleString of(byte[] bytes) {
        return TruffleString.fromByteArrayUncached(bytes, ENC, false);
    }

    /**
     * A new string holding a copy of the range. Copies with Arrays.copyOfRange and hands TruffleString an exact
     * array: TruffleString's own copying path, called from host-compiled built-ins, profiled ~10x slower.
     */
    @TruffleBoundary
    public static TruffleString of(byte[] bytes, int offset, int length) {
        if (length == 1) {
            return SINGLE_BYTES[bytes[offset] & 0xFF];
        }
        return TruffleString.fromByteArrayUncached(Arrays.copyOfRange(bytes, offset, offset + length), ENC, false);
    }

    /** A fresh copy of the string's bytes, which the caller may modify. */
    @TruffleBoundary
    public static byte[] bytes(TruffleString s) {
        return s.copyToByteArrayUncached(ENC);
    }

    /**
     * The string's bytes for reading only: the string's own backing array when that holds exactly the string
     * (no copy; never write to it, and don't wrap it in a new string), else a copy. Built-ins that scan big
     * strings at an offset (strspn on a document) would otherwise copy the whole document per call.
     */
    @TruffleBoundary
    public static byte[] readBytes(TruffleString s) {
        InternalByteArray internal = s.getInternalByteArrayUncached(ENC);
        byte[] array = internal.getArray();
        if (internal.getOffset() == 0 && internal.getLength() == array.length) {
            return array;
        }
        return s.copyToByteArrayUncached(ENC);
    }

    @TruffleBoundary
    public static int length(TruffleString s) {
        return s.byteLength(ENC);
    }

    /** Decodes as UTF-8 for messages, names and Java-side lookups; invalid bytes become U+FFFD. */
    @TruffleBoundary
    public static String java(TruffleString s) {
        return new String(bytes(s), StandardCharsets.UTF_8);
    }

    @TruffleBoundary
    public static TruffleString concat(TruffleString a, TruffleString b) {
        return a.concatUncached(b, ENC, true);
    }

    @TruffleBoundary
    public static boolean equal(TruffleString a, TruffleString b) {
        return a.equalsUncached(b, ENC);
    }

    /** Byte-wise comparison, as PHP's strcmp: negative, zero or positive. */
    @TruffleBoundary
    public static int compare(TruffleString a, TruffleString b) {
        return a.compareBytesUncached(b, ENC);
    }

    @TruffleBoundary
    public static int byteAt(TruffleString s, int index) {
        return s.readByteUncached(index, ENC);
    }
}
