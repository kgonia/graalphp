package pl.codeloom.php.runtime;

import com.oracle.truffle.api.strings.InternalByteArray;
import com.oracle.truffle.api.strings.TruffleString;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A growable byte buffer for building PHP strings: ByteArrayOutputStream without its per-call synchronization,
 * which showed up in profiles of the regex and escaping built-ins.
 */
public final class ByteBuilder {
    private byte[] buf;
    private int count;

    public ByteBuilder() {
        this(32);
    }

    public ByteBuilder(int capacity) {
        this.buf = new byte[Math.max(capacity, 8)];
    }

    private void ensure(int extra) {
        int needed = count + extra;
        if (needed > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(needed, buf.length * 2));
        }
    }

    public void write(int b) {
        ensure(1);
        buf[count++] = (byte) b;
    }

    public void write(byte[] bytes, int offset, int length) {
        ensure(length);
        System.arraycopy(bytes, offset, buf, count, length);
        count += length;
    }

    public void write(byte[] bytes) {
        write(bytes, 0, bytes.length);
    }

    public void writeBytes(byte[] bytes) {
        write(bytes, 0, bytes.length);
    }

    /** Appends a string's bytes, read in place (no intermediate copy, also for substrings). */
    public void write(TruffleString s) {
        InternalByteArray bytes = s.getInternalByteArrayUncached(Str.ENC);
        write(bytes.getArray(), bytes.getOffset(), bytes.getLength());
    }

    /** Appends an int in decimal, as PHP converts it to a string. */
    public void writeLong(long value) {
        if (value == Long.MIN_VALUE) {
            writeBytes(Long.toString(value).getBytes(StandardCharsets.ISO_8859_1));
            return;
        }
        if (value < 0) {
            write('-');
            value = -value;
        }
        int digits = 1;
        for (long v = value; v >= 10; v /= 10) {
            digits++;
        }
        ensure(digits);
        for (int i = count + digits - 1; i >= count; i--) {
            buf[i] = (byte) ('0' + value % 10);
            value /= 10;
        }
        count += digits;
    }

    /** Replaces the last byte written. */
    public void setLast(int b) {
        buf[count - 1] = (byte) b;
    }

    public int size() {
        return count;
    }

    public void reset() {
        count = 0;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buf, count);
    }
}
