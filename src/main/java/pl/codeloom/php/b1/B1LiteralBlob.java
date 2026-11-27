package pl.codeloom.php.b1;

import pl.codeloom.php.b1.runtime.B1Array;
import pl.codeloom.php.b1.runtime.B1Bytes;
import pl.codeloom.php.b1.runtime.B1Object;
import pl.codeloom.php.ir.IrUnit.*;
import java.io.*;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;

/** Tagged, length-delimited literal data. A constant pool entry addresses one immutable root. */
public final class B1LiteralBlob {
    private B1LiteralBlob() {}
    static final int BYTE_THRESHOLD = 16384;

    static boolean useBlob(ArrayValue array) {
        int count = weight(array);
        // Every all-literal array is an immutable condy root (IS_ARRAY_IMMUTABLE): no per-evaluation allocation.
        return count >= 0;
    }

    private static long encodedSize(Expr e) {
        if (e instanceof StoreTemp t) return encodedSize(t.value());
        if (e instanceof ArrayValue a) {
            long bytes = 5;
            for (Item item : a.items()) bytes += 1 + (item.key() == null ? 0 : encodedSize(item.key())) + encodedSize(item.value());
            return bytes;
        }
        return switch (((Literal)e).value()) {
            case String s -> 5L + s.getBytes(StandardCharsets.UTF_8).length;
            case byte[] binary -> 5L + binary.length;
            case Number ignored -> 9;
            case null, default -> 1;
        };
    }

    // A failing literal must execute at the PHP instruction, where its Error can be caught.
    static boolean validAppendKeys(ArrayValue array) {
        long next = 0;
        boolean assigned = false, occupiedMax = false;
        for (Item item : array.items()) {
            Object raw;
            if (item.key() == null) {
                if (occupiedMax) return false;
                raw = next;
            } else raw = ((Literal)item.key()).value();
            Object key = B1Array.key(raw instanceof byte[] bytes ? B1Bytes.of(bytes) : raw);
            if (key instanceof Long n && (!assigned || n >= next)) {
                assigned = true;
                occupiedMax = n == Long.MAX_VALUE;
                next = occupiedMax ? n : n + 1;
            }
            Expr value = item.value() instanceof StoreTemp t ? t.value() : item.value();
            if (value instanceof ArrayValue nested && !validAppendKeys(nested)) return false;
        }
        return true;
    }

    static int weight(Expr e) {
        return switch (e) {
            case StoreTemp t -> weight(t.value());
            case Literal ignored -> 0;
            case ArrayValue a -> {
                int count = a.items().size();
                for (Item item : a.items()) {
                    int nested = weight(item.value());
                    if (nested < 0 || item.key() != null && (weight(item.key()) < 0 || !exactKey(item.key()))) yield -1;
                    count += nested;
                }
                yield count;
            }
            default -> -1;
        };
    }
    /** A float key converts silently only when it is integral and within zend_long; any other float key must reach
     * the runtime, which reports the precision loss. */
    private static boolean exactKey(Expr key) {
        Expr k = key instanceof StoreTemp t ? t.value() : key;
        if (!(k instanceof Literal(Object value) && value instanceof Double d)) return true;
        double x = d;
        return x >= -0x1p63 && x < 0x1p63 && (double)(long)x == x;
    }
    static byte[] encode(Expr e) {
        var bytes = new ByteArrayOutputStream();
        try { write(new DataOutputStream(bytes), e); } catch (IOException ex) { throw new UncheckedIOException(ex); }
        return bytes.toByteArray();
    }
    private static void write(DataOutputStream out, Expr e) throws IOException {
        if (e instanceof StoreTemp t) { write(out,t.value()); return; }
        if (e instanceof ArrayValue a) {
            out.writeByte(6);
            out.writeInt(a.items().size());
            for (Item item : a.items()) {
                out.writeBoolean(item.key() != null);
                if (item.key() != null) write(out,item.key());
                write(out,item.value());
            }
            return;
        }
        switch (((Literal)e).value()) {
            case null -> out.writeByte(0);
            case Boolean b -> out.writeByte(b ? 2 : 1);
            case Long n -> { out.writeByte(3); out.writeLong(n); }
            case Double d -> { out.writeByte(4); out.writeDouble(d); }
            case String s -> writeBytes(out,s.getBytes(StandardCharsets.UTF_8));
            case byte[] bytes -> writeBytes(out,bytes);
            default -> throw new IllegalArgumentException("Unsupported literal");
        }
    }
    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeByte(5); out.writeInt(bytes.length); out.write(bytes);
    }
    private static Object read(DataInputStream in) throws IOException {
        return switch (in.readUnsignedByte()) {
            case 0 -> null;
            case 1 -> false;
            case 2 -> true;
            case 3 -> in.readLong();
            case 4 -> in.readDouble();
            case 5 -> intern(B1Bytes.of(in.readNBytes(in.readInt())));
            case 6 -> {
                int count = in.readInt();
                var a = new B1Array(count);
                for (int i = 0; i < count; i++) {
                    boolean keyed = in.readBoolean();
                    Object key = keyed ? read(in) : null;
                    Object value = read(in);
                    if (keyed) a.put(null,key,value); else a.append(null,value);
                    B1Object.release(null,value);
                }
                yield a;
            }
            default -> throw new IOException("Invalid literal tag");
        };
    }
    static Object decodedLiteral(byte[] bytes) throws IOException {
        Object value = read(new DataInputStream(new ByteArrayInputStream(bytes)));
        return value instanceof B1Array a ? a.freeze() : value;
    }

    static Object propertyDefault(Expr initial) {
        try { return decodedLiteral(encode(initial)); }
        catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    public static Object indices(MethodHandles.Lookup lookup, String name, Class<?> type, int index)
            throws IllegalAccessException {
        byte[] data = classData(lookup, index);
        int[] slots = new int[data.length / Integer.BYTES];
        java.nio.ByteBuffer.wrap(data).asIntBuffer().get(slots);
        return slots;
    }
    public static Object text(MethodHandles.Lookup lookup, String name, Class<?> type, String text) {
        return intern(B1Bytes.ofUtf8(text));
    }
    /** Literal strings are immutable and shared by every request: one instance per byte content, so array keys
     * from literals compare by identity first (as PHP's interned strings do). Declared property names join them
     * (B1Compiler), so $obj->$name with a literal name finds its slot by identity too. Bounded by the loaded code. */
    // Weak both ways: a literal no longer referenced by any linked class (a retired reload or eval) is reclaimed.
    private static final java.util.WeakHashMap<B1Bytes,java.lang.ref.WeakReference<B1Bytes>> INTERNED = new java.util.WeakHashMap<>();
    static B1Bytes intern(B1Bytes literal) {
        synchronized (INTERNED) {
            var ref = INTERNED.get(literal);
            B1Bytes existing = ref == null ? null : ref.get();
            if (existing != null) return existing;
            INTERNED.put(literal, new java.lang.ref.WeakReference<>(literal));
            return literal;
        }
    }
    /** Interned literals still reachable, for reload tests. */
    static int internedCount() {
        synchronized (INTERNED) { return (int)INTERNED.values().stream().filter(r -> r.get() != null).count(); }
    }
    public static Object bootstrap(MethodHandles.Lookup lookup, String name, Class<?> type, int index)
            throws IOException, IllegalAccessException {
        return decodedLiteral(classData(lookup, index));
    }
    /** Blobs of classes defined by B1Compiler's profiling mode (ordinary classes have no class data). Weak keys:
     * an unloaded class (eval, reload) takes its blobs with it. */
    static final java.util.Map<Class<?>, java.util.List<byte[]>> PROFILE_DATA =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private static byte[] classData(MethodHandles.Lookup lookup, int index) throws IllegalAccessException {
        if (!lookup.lookupClass().isHidden()) return PROFILE_DATA.get(lookup.lookupClass()).get(index);
        return MethodHandles.classDataAt(lookup, "_", byte[].class, index);
    }
}
