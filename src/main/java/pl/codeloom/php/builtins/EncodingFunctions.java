package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.zip.CRC32;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** serialize / unserialize, base64, hex, base conversion, hashes and HMACs, and cryptographic randomness. */
final class EncodingFunctions {
    private EncodingFunctions() {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    static void register(Registry r) {
        r.fn("serialize", a -> serialize(Args.get(a, 0)));
        r.fn("unserialize", (context, a) -> unserialize(context, Str.bytes(Args.str(a, 0)), Args.get(a, 1)));
        r.fn("base64_encode", a -> Str.of(Base64.getEncoder().encode(Str.bytes(Args.str(a, 0)))));
        r.fn("base64_decode", a -> base64Decode(Str.bytes(Args.str(a, 0)), Args.bool(a, 1, false)));
        r.fn("hex2bin", a -> hex2bin(Str.readBytes(Args.str(a, 0))));
        r.fn("base_convert", a -> baseConvert(Str.java(Convert.toStr(Args.get(a, 0))), Args.lng(a, 1, 10),
                Args.lng(a, 2, 10)));
        r.fn("random_bytes", a -> {
            byte[] bytes = new byte[(int) Args.lng(a, 0, 0)];
            RANDOM.nextBytes(bytes);
            return Str.of(bytes);
        });
        r.fn("random_int", a -> randomInt(Args.lng(a, 0, 0), Args.lng(a, 1, 0)));
        r.fn("uniqid", a -> uniqid(Str.java(Args.str(a, 0, Str.EMPTY)), Args.bool(a, 1, false)));
        r.fn("hash", a -> hash(Str.java(Args.str(a, 0)), Str.bytes(Args.str(a, 1)), Args.bool(a, 2, false)));
        r.fn("hash_hmac", a -> hmac(Str.java(Args.str(a, 0)), Str.bytes(Args.str(a, 1)), Str.bytes(Args.str(a, 2)),
                Args.bool(a, 3, false)));
        r.fn("hash_equals", a -> MessageDigest.isEqual(Str.bytes(Args.str(a, 0)), Str.bytes(Args.str(a, 1))));
        r.fn("hash_algos", a -> PhpArray.ofStrings("md5", "sha1", "sha224", "sha256", "sha384", "sha512", "crc32b"));
        r.fn("hash_hmac_algos", a -> PhpArray.ofStrings("md5", "sha1", "sha224", "sha256", "sha384", "sha512"));
    }


    // ---- serialize ----

    /** Serialized output: ASCII syntax and the raw bytes of strings. */
    private static final class Out {
        final ByteBuilder bytes = new ByteBuilder();

        Out append(String ascii) {
            bytes.write(ascii.getBytes(StandardCharsets.ISO_8859_1));
            return this;
        }

        Out append(TruffleString s) {
            bytes.write(s);
            return this;
        }
    }

    @TruffleBoundary
    static TruffleString serialize(Object value) {
        Out out = new Out();
        serialize(out, PhpRef.deref(value));
        return Str.of(out.bytes.toByteArray());
    }

    private static void serialize(Out out, Object value) {
        if (value == PhpNull.NULL || value == null) {
            out.append("N;");
        } else if (value instanceof Boolean b) {
            out.append(b ? "b:1;" : "b:0;");
        } else if (value instanceof Long l) {
            out.append("i:").append(Long.toString(l)).append(";");
        } else if (value instanceof Double d) {
            out.append("d:").append(Convert.doubleToRepr(d)).append(";");
        } else if (value instanceof TruffleString s) {
            string(out, s);
        } else if (value instanceof PhpArray array) {
            out.append("a:").append(Integer.toString(array.size())).append(":{");
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                key(out, array.keyAt(p));
                serialize(out, array.valueAt(p));
            }
            out.append("}");
        } else if (value instanceof PhpObject object) {
            Object[] names = object.propertyNames();
            TruffleString className = Str.of(object.getPhpClass().getName());
            out.append("O:").append(Integer.toString(className.byteLength(Str.ENC)))
                    .append(":\"").append(className).append("\":")
                    .append(Integer.toString(names.length)).append(":{");
            for (Object name : names) {
                string(out, Str.ofKey((String) name));
                serialize(out, PhpRef.deref(object.getProperty((String) name)));
            }
            out.append("}");
        } else {
            throw new PhpError("Serialization of '" + Convert.typeName(value) + "' is not allowed");
        }
    }

    private static void key(Out out, Object key) {
        if (key instanceof Long l) {
            out.append("i:").append(Long.toString(l)).append(";");
        } else {
            string(out, (TruffleString) key);
        }
    }

    private static void string(Out out, TruffleString s) {
        out.append("s:").append(Integer.toString(s.byteLength(Str.ENC))).append(":\"").append(s).append("\";");
    }

    // ---- unserialize ----

    /** unserialize_max_depth's default: deeper nesting fails, as php-src's. */
    private static final long MAX_DEPTH = 4096;

    /**
     * unserialize($data, $options): false for malformed input. A class allowed_classes excludes, or one that
     * doesn't exist, becomes a __PHP_Incomplete_Class without being autoloaded or woken; what __wakeup throws
     * propagates.
     */
    @TruffleBoundary
    static Object unserialize(PhpContext context, byte[] input, Object options) {
        Reader reader = new Reader(context, input, options instanceof PhpArray array ? array : null);
        try {
            return reader.value();
        } catch (IllegalArgumentException | IndexOutOfBoundsException malformed) {
            return Boolean.FALSE;
        }
    }

    private static final class Reader {
        private final PhpContext context;
        private final byte[] s;
        private int pos;
        /** allowed_classes: null for all, else the allowed lower-case names (none for false). */
        private final java.util.Set<String> allowedClasses;
        private final long maxDepth;
        private long depth;

        Reader(PhpContext context, byte[] s, PhpArray options) {
            this.context = context;
            this.s = s;
            Object allowed = options == null ? null : options.get(ArrayKey.of(Str.of("allowed_classes")));
            if (allowed == null || allowed == Boolean.TRUE) {
                allowedClasses = null;
            } else {
                allowedClasses = new java.util.HashSet<>();
                if (allowed instanceof PhpArray names) {
                    for (int p = names.firstPosition(); p >= 0; p = names.nextPosition(p)) {
                        allowedClasses.add(Str.lower(Str.java(Convert.toStr(names.valueAt(p)))));
                    }
                }
            }
            Object depthOption = options == null ? null : options.get(ArrayKey.of(Str.of("max_depth")));
            long requested = depthOption == null ? 0 : Convert.toLong(depthOption);
            maxDepth = requested > 0 ? requested : MAX_DEPTH;
        }

        /** Entering a nested array or object; php-src counts only the ones with elements. */
        private void enter(long elements) {
            if (elements > 0 && depth++ >= maxDepth) {
                throw new IllegalArgumentException("unserialize: maximum depth of " + maxDepth + " exceeded");
            }
        }

        private void leave(long elements) {
            if (elements > 0) {
                depth--;
            }
        }

        /**
         * An array or object being read: its elements come as key, value, key, value... Nesting is kept on an
         * explicit stack, not the Java one, so the depth php-src allows (4096) fits any thread.
         */
        private static final class Container {
            final long count;
            final PhpArray array;
            final PhpObject object;
            final PhpClass phpClass;
            long read;
            Object key;

            Container(long count, PhpArray array, PhpObject object, PhpClass phpClass) {
                this.count = count;
                this.array = array;
                this.object = object;
                this.phpClass = phpClass;
            }
        }

        /** The value at the current position, with everything nested in it. */
        Object value() {
            java.util.ArrayDeque<Container> open = new java.util.ArrayDeque<>();
            while (true) {
                Object value;
                Container started = start();
                if (started == null) {
                    value = scalar();
                } else if (started.count > 0) {
                    open.push(started);
                    continue;
                } else {
                    value = finish(started);
                }
                // hand the value to the containers it completes, innermost first
                while (true) {
                    Container top = open.peek();
                    if (top == null) {
                        return value;
                    }
                    if (top.key == null) {
                        if (!(value instanceof Long) && !(value instanceof TruffleString)) {
                            throw new IllegalArgumentException("unserialize: a key must be an int or a string");
                        }
                        top.key = value;
                        break;
                    }
                    store(top, top.key, value);
                    top.key = null;
                    if (++top.read < top.count) {
                        break;
                    }
                    open.pop();
                    value = finish(top);
                }
            }
        }

        /** An array or object header (`a:n:{`, `O:len:"Class":n:{`), or null for a scalar. */
        private Container start() {
            char type = (char) s[pos];
            if (type == 'a') {
                expect("a:");
                long count = number(':');
                expect("{");
                enter(count);
                return new Container(count, new PhpArray(), null, null);
            }
            if (type != 'O') {
                return null;
            }
            expect("O:");
            String className = Str.java(bytes(number(':')));
            expect(":");
            long count = number(':');
            expect("{");
            enter(count);
            boolean allowed = allowedClasses == null || allowedClasses.contains(Str.lower(className));
            PhpClass phpClass = allowed ? context.findClassAutoloading(className) : null;
            PhpObject object;
            if (phpClass != null) {
                object = phpClass.instantiate();
            } else {
                object = context.getClass("__PHP_Incomplete_Class").instantiate();
                object.setProperty("__PHP_Incomplete_Class_Name", Str.of(className));
            }
            return new Container(count, null, object, phpClass);
        }

        private static void store(Container container, Object key, Object value) {
            if (container.array != null) {
                container.array.put(ArrayKey.of(key), value);
                return;
            }
            String name = Str.key(Convert.toStr(key));
            // private and protected properties are serialized as "\0Class\0name" and "\0*\0name"
            if (name.startsWith("\0")) {
                name = name.substring(name.indexOf('\0', 1) + 1);
            }
            container.object.setProperty(name, value);
        }

        /** The closing brace: the array, or the object (woken if its class has __wakeup). */
        private Object finish(Container container) {
            expect("}");
            leave(container.count);
            if (container.array != null) {
                return container.array;
            }
            if (container.phpClass != null && container.phpClass.findMethod("__wakeup") != null) {
                container.object.callMethod("__wakeup");
            }
            return container.object;
        }

        private Object scalar() {
            char type = (char) s[pos];
            switch (type) {
                case 'N':
                    expect("N;");
                    return PhpNull.NULL;
                case 'b': {
                    expect("b:");
                    long v = number(';');
                    return v != 0;
                }
                case 'i': {
                    expect("i:");
                    return number(';');
                }
                case 'd': {
                    expect("d:");
                    String text = until(';');
                    return switch (text) {
                        case "NAN" -> Double.NaN;
                        case "INF" -> Double.POSITIVE_INFINITY;
                        case "-INF" -> Double.NEGATIVE_INFINITY;
                        default -> Double.parseDouble(text);
                    };
                }
                case 's': {
                    expect("s:");
                    TruffleString value = bytes(number(':'));
                    expect(";");
                    return value;
                }
                default:
                    throw new IllegalArgumentException("unserialize: unexpected " + type);
            }
        }

        private void expect(String text) {
            for (int i = 0; i < text.length(); i++) {
                if (s[pos++] != text.charAt(i)) {
                    throw new IllegalArgumentException("unserialize: expected " + text);
                }
            }
        }

        private String until(char end) {
            int start = pos;
            while (s[pos] != end) {
                pos++;
            }
            String text = new String(s, start, pos - start, StandardCharsets.ISO_8859_1);
            pos++;
            return text;
        }

        private long number(char end) {
            return Long.parseLong(until(end));
        }

        /** A quoted byte string of a known length: "..." (checked against the input before copying). */
        private TruffleString bytes(long length) {
            expect("\"");
            if (length < 0 || length > s.length - pos) {
                throw new IllegalArgumentException("unserialize: string length " + length + " past the end");
            }
            byte[] value = java.util.Arrays.copyOfRange(s, pos, pos + (int) length);
            pos += (int) length;
            expect("\"");
            return Str.of(value);
        }
    }

    // ---- the rest ----

    /** base64.c's base64_reverse_table: a byte's 6-bit value, -1 for whitespace, -2 for anything else. */
    private static final byte[] BASE64_REVERSE = new byte[256];

    static {
        Arrays.fill(BASE64_REVERSE, (byte) -2);
        BASE64_REVERSE['\t'] = -1;
        BASE64_REVERSE['\n'] = -1;
        BASE64_REVERSE['\r'] = -1;
        BASE64_REVERSE[' '] = -1;
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        for (int i = 0; i < alphabet.length(); i++) {
            BASE64_REVERSE[alphabet.charAt(i)] = (byte) i;
        }
    }

    /**
     * php_base64_decode_impl: '=' is counted wherever it is; other bytes outside the alphabet are skipped, or in
     * strict mode whitespace is skipped and anything else, or data after padding, fails, as do a lone last
     * character and wrong padding.
     */
    @TruffleBoundary
    private static Object base64Decode(byte[] in, boolean strict) {
        byte[] out = new byte[in.length / 4 * 3 + 3];
        int i = 0;
        int padding = 0;
        int j = 0;
        for (byte b : in) {
            if (b == '=') {
                padding++;
                continue;
            }
            int ch = BASE64_REVERSE[b & 0xFF];
            if (!strict) {
                if (ch < 0) {
                    continue;
                }
            } else {
                if (ch == -1) {
                    continue;
                }
                if (ch == -2 || padding != 0) {
                    return Boolean.FALSE;
                }
            }
            switch (i % 4) {
                case 0 -> out[j] = (byte) (ch << 2);
                case 1 -> {
                    out[j++] |= (byte) (ch >> 4);
                    out[j] = (byte) ((ch & 0x0F) << 4);
                }
                case 2 -> {
                    out[j++] |= (byte) (ch >> 2);
                    out[j] = (byte) ((ch & 0x03) << 6);
                }
                default -> out[j++] |= (byte) ch;
            }
            i++;
        }
        if (strict && i % 4 == 1) {
            // truncated: one character in the last group
            return Boolean.FALSE;
        }
        if (strict && padding != 0 && (padding > 2 || (i + padding) % 4 != 0)) {
            return Boolean.FALSE;
        }
        return Str.of(out, 0, j);
    }

    /** False for an odd length or a byte outside [0-9A-Fa-f] (PHP also warns); HexFormat rejects exactly those. */
    @TruffleBoundary
    private static Object hex2bin(byte[] hex) {
        try {
            return Str.of(HexFormat.of().parseHex(new String(hex, StandardCharsets.ISO_8859_1)));
        } catch (IllegalArgumentException e) {
            return Boolean.FALSE;
        }
    }

    @TruffleBoundary
    private static TruffleString baseConvert(String number, long from, long to) {
        if (from < 2 || from > 36 || to < 2 || to > 36) {
            throw new PhpError("ValueError", "base_convert(): Argument #2 ($from_base) must be between 2 and 36 "
                    + "(inclusive)");
        }
        StringBuilder digits = new StringBuilder();
        for (char c : Str.lower(number).toCharArray()) {
            if (Character.digit(c, (int) from) >= 0) {
                digits.append(c);
            }
        }
        if (digits.isEmpty()) {
            return Str.of("0");
        }
        return Str.of(new java.math.BigInteger(digits.toString(), (int) from).toString((int) to));
    }

    @TruffleBoundary
    private static long randomInt(long min, long max) {
        if (min > max) {
            throw new PhpError("ValueError", "random_int(): Argument #1 ($min) must be less than or equal to "
                    + "argument #2 ($max)");
        }
        // bounded draws are uniform (RandomGenerator.nextLong(origin, bound)); max + 1 may overflow
        if (max < Long.MAX_VALUE) {
            return RANDOM.nextLong(min, max + 1);
        }
        return min == Long.MIN_VALUE ? RANDOM.nextLong() : RANDOM.nextLong(min - 1, max) + 1;
    }

    @TruffleBoundary
    private static TruffleString uniqid(String prefix, boolean moreEntropy) {
        long micros = System.currentTimeMillis() * 1000 + (System.nanoTime() / 1000) % 1000;
        String id = prefix + String.format("%08x%05x", micros / 1_000_000, micros % 1_000_000);
        if (moreEntropy) {
            id += String.format(Locale.ROOT, ".%08d", RANDOM.nextInt(100_000_000));
        }
        return Str.of(id);
    }

    private static String javaAlgorithm(String algo) {
        return switch (Str.lower(algo)) {
            case "md5" -> "MD5";
            case "sha1" -> "SHA-1";
            case "sha224" -> "SHA-224";
            case "sha256" -> "SHA-256";
            case "sha384" -> "SHA-384";
            case "sha512" -> "SHA-512";
            default -> null;
        };
    }

    @TruffleBoundary
    private static TruffleString hash(String algo, byte[] data, boolean binary) {
        if (algo.equalsIgnoreCase("crc32b")) {
            CRC32 crc = new CRC32();
            crc.update(data);
            // the checksum's four bytes, most significant first: the hex form is "%08x" of the value
            int value = (int) crc.getValue();
            return digestResult(new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8),
                    (byte) value}, binary);
        }
        String name = javaAlgorithm(algo);
        if (name == null) {
            throw new PhpError("ValueError", "hash(): Argument #1 ($algo) must be a valid hashing algorithm");
        }
        return digest(name, data, binary);
    }

    /** md5(), sha1() and hash(): the digest under its Java name, raw or as lowercase hex. */
    @TruffleBoundary
    static TruffleString digest(String javaAlgorithm, byte[] data, boolean binary) {
        try {
            return digestResult(MessageDigest.getInstance(javaAlgorithm).digest(data), binary);
        } catch (NoSuchAlgorithmException e) {
            throw new PhpError("hash(): " + e.getMessage());
        }
    }

    @TruffleBoundary
    private static Object hmac(String algo, byte[] data, byte[] key, boolean binary) {
        String name = javaAlgorithm(algo);
        if (name == null) {
            throw new PhpError("ValueError", "hash_hmac(): Argument #1 ($algo) must be a valid cryptographic hashing "
                    + "algorithm");
        }
        try {
            String macName = "Hmac" + name.replace("-", "");
            Mac mac = Mac.getInstance(macName);
            // HMAC pads the key with zero bytes, so an empty key (which SecretKeySpec can't hold) is one zero byte
            mac.init(new SecretKeySpec(key.length == 0 ? new byte[1] : key, macName));
            return digestResult(mac.doFinal(data), binary);
        } catch (java.security.GeneralSecurityException e) {
            throw new PhpError("hash_hmac(): " + e.getMessage());
        }
    }

    private static TruffleString digestResult(byte[] digest, boolean binary) {
        return binary ? Str.of(digest) : Str.of(HexFormat.of().formatHex(digest));
    }
}
