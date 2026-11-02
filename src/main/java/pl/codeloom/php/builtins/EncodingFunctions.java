package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
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

/**
 * serialize / unserialize (see {@link Serialization}), base64, hex, base conversion, hashes and HMACs, and
 * cryptographic randomness.
 */
final class EncodingFunctions {
    private EncodingFunctions() {
    }

    static final SecureRandom RANDOM = new SecureRandom();

    static void register(Registry r) {
        r.fn("serialize", (context, a) -> Serialization.serialize(context, Args.get(a, 0)));
        r.fn("unserialize", (context, a) -> {
            TruffleString data = Args.string(context, a, 0, "unserialize", "data", false);
            Object options = Args.get(a, 1);
            if (options != null && !(options instanceof PhpArray)) {
                throw Args.typeError("unserialize", 2, "options", "array", options);
            }
            return Serialization.unserialize(context, data, (PhpArray) options);
        });
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
        long micros = pl.codeloom.php.runtime.WallClock.uniqidMicros();
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
