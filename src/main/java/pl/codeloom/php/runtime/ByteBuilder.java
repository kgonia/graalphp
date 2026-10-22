package pl.codeloom.php.runtime;

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
