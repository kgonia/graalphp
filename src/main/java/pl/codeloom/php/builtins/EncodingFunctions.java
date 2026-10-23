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
import java.util.Base64;
import java.util.Locale;
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
        r.fn("hex2bin", a -> hex2bin(Str.java(Args.str(a, 0))));
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
        r.fn("hash_algos", a -> list("md5", "sha1", "sha224", "sha256", "sha384", "sha512", "crc32b"));
        r.fn("hash_hmac_algos", a -> list("md5", "sha1", "sha224", "sha256", "sha384", "sha512"));
    }

    private static PhpArray list(String... names) {
        PhpArray array = new PhpArray();
        for (String name : names) {
            array.append(Str.of(name));
        }
        return array;
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
            out.append("d:").append(serializedDouble(d)).append(";");
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
                string(out, Str.of((String) name));
                serialize(out, PhpRef.deref(object.getProperty((String) name)));
            }
            out.append("}");
        } else {
            throw new PhpError("Serialization of '" + Convert.typeName(value) + "' is not allowed");
        }
    }

    private static String serializedDouble(double d) {
        if (Double.isNaN(d)) {
            return "NAN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "INF" : "-INF";
        }
        return Convert.doubleToRepr(d);
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
        } catch (StackOverflowError tooDeep) {
            // ponytail: recursive reader, ~2000 nested levels on a default stack where php-src takes 4096;
            // an explicit stack if real data ever nests that deep
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
                        allowedClasses.add(Str.java(Convert.toStr(names.valueAt(p))).toLowerCase(Locale.ROOT));
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

        Object value() {
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
                case 'a': {
                    expect("a:");
                    long count = number(':');
                    expect("{");
                    enter(count);
                    PhpArray array = new PhpArray();
                    for (long i = 0; i < count; i++) {
                        Object key = ArrayKey.of(value());
                        array.put(key, value());
                    }
                    expect("}");
                    leave(count);
                    return array;
                }
                case 'O': {
                    expect("O:");
                    String className = Str.java(bytes(number(':')));
                    expect(":");
                    long count = number(':');
                    expect("{");
                    enter(count);
                    boolean allowed = allowedClasses == null
                            || allowedClasses.contains(className.toLowerCase(Locale.ROOT));
                    PhpClass phpClass = allowed ? context.findClassAutoloading(className) : null;
                    PhpObject object;
                    if (phpClass != null) {
                        object = phpClass.instantiate();
                    } else {
                        object = context.getClass("__PHP_Incomplete_Class").instantiate();
                        object.setProperty("__PHP_Incomplete_Class_Name", Str.of(className));
                    }
                    for (long i = 0; i < count; i++) {
                        String name = Str.java(Convert.toStr(value()));
                        // private and protected properties are serialized as "\0Class\0name" and "\0*\0name"
                        if (name.startsWith("\0")) {
                            name = name.substring(name.indexOf('\0', 1) + 1);
                        }
                        object.setProperty(name.intern(), value());
                    }
                    expect("}");
                    leave(count);
                    if (phpClass != null && phpClass.findMethod("__wakeup") != null) {
                        object.callMethod("__wakeup");
                    }
                    return object;
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

    @TruffleBoundary
    private static Object base64Decode(byte[] input, boolean strict) {
        StringBuilder clean = new StringBuilder(input.length);
        for (byte b : input) {
            char c = (char) (b & 0xFF);
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '+' || c == '/') {
                clean.append(c);
            } else if (c == '=') {
                break;
            } else if (strict && !Character.isWhitespace(c)) {
                return Boolean.FALSE;
            }
        }
        while (clean.length() % 4 == 1) {
            clean.setLength(clean.length() - 1);
        }
        try {
            return Str.of(Base64.getDecoder().decode(clean.toString().replaceAll("=+$", "")
                    + "==".substring(0, (4 - clean.length() % 4) % 4)));
        } catch (IllegalArgumentException e) {
            return Boolean.FALSE;
        }
    }

    @TruffleBoundary
    private static Object hex2bin(String hex) {
        if (hex.length() % 2 != 0) {
            return Boolean.FALSE;
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int high = Character.digit(hex.charAt(2 * i), 16);
            int low = Character.digit(hex.charAt(2 * i + 1), 16);
            if (high < 0 || low < 0) {
                return Boolean.FALSE;
            }
            out[i] = (byte) (high << 4 | low);
        }
        return Str.of(out);
    }

    @TruffleBoundary
    private static TruffleString baseConvert(String number, long from, long to) {
        if (from < 2 || from > 36 || to < 2 || to > 36) {
            throw new PhpError("ValueError: base_convert(): Argument #2 ($from_base) must be between 2 and 36 "
                    + "(inclusive)");
        }
        StringBuilder digits = new StringBuilder();
        for (char c : number.toLowerCase(Locale.ROOT).toCharArray()) {
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
            throw new PhpError("ValueError: random_int(): Argument #1 ($min) must be less than or equal to "
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
        return switch (algo.toLowerCase(Locale.ROOT)) {
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
    static Object hash(String algo, byte[] data, boolean binary) {
        if (algo.equalsIgnoreCase("crc32b")) {
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(data);
            String hex = String.format("%08x", crc.getValue());
            return binary ? hex2bin(hex) : Str.of(hex);
        }
        String name = javaAlgorithm(algo);
        if (name == null) {
            throw new PhpError("ValueError: hash(): Argument #1 ($algo) must be a valid hashing algorithm");
        }
        try {
            return digestResult(MessageDigest.getInstance(name).digest(data), binary);
        } catch (NoSuchAlgorithmException e) {
            throw new PhpError("hash(): " + e.getMessage());
        }
    }

    @TruffleBoundary
    private static Object hmac(String algo, byte[] data, byte[] key, boolean binary) {
        String name = javaAlgorithm(algo);
        if (name == null) {
            throw new PhpError("ValueError: hash_hmac(): Argument #1 ($algo) must be a valid cryptographic hashing "
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
        if (binary) {
            return Str.of(digest);
        }
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return Str.of(hex.toString());
    }
}
