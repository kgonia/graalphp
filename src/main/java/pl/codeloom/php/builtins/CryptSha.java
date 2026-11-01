package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-crypt (Ulrich Drepper's "$5$" and "$6$") as php-src's crypt_sha256.c and crypt_sha512.c compute it, with
 * php_crypt's output buffer of PHP_MAX_SALT_LEN (123) bytes: a result that doesn't fit in it fails. The key and the
 * setting are C strings there: each ends at its first NUL byte.
 */
final class CryptSha {
    private CryptSha() {
    }

    private static final byte[] ROUNDS_PREFIX = "rounds=".getBytes(StandardCharsets.US_ASCII);

    /** SALT_LEN_MAX. */
    private static final int SALT_LEN_MAX = 16;
    private static final long ROUNDS_DEFAULT = 5000;
    private static final long ROUNDS_MIN = 1000;
    private static final long ROUNDS_MAX = 999999999;

    /** The size of the buffer php_crypt gives php_sha256_crypt_r and php_sha512_crypt_r (PHP_MAX_SALT_LEN). */
    private static final int BUFLEN = 123;

    private static final byte[] B64T =
            "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);

    /** The b64_from_24bit calls of php_sha256_crypt_r: B2, B1, B0 (-1 for a 0 byte) and N, four per call. */
    private static final int[] SHA256_ORDER = {
        0, 10, 20, 4, 21, 1, 11, 4, 12, 22, 2, 4, 3, 13, 23, 4, 24, 4, 14, 4, 15, 25, 5, 4, 6, 16, 26, 4,
        27, 7, 17, 4, 18, 28, 8, 4, 9, 19, 29, 4, -1, 31, 30, 3,
    };

    /** The b64_from_24bit calls of php_sha512_crypt_r, as SHA256_ORDER. */
    private static final int[] SHA512_ORDER = {
        0, 21, 42, 4, 22, 43, 1, 4, 44, 2, 23, 4, 3, 24, 45, 4, 25, 46, 4, 4, 47, 5, 26, 4, 6, 27, 48, 4,
        28, 49, 7, 4, 50, 8, 29, 4, 9, 30, 51, 4, 31, 52, 10, 4, 53, 11, 32, 4, 12, 33, 54, 4, 34, 55, 13, 4,
        56, 14, 35, 4, 15, 36, 57, 4, 37, 58, 16, 4, 59, 17, 38, 4, 18, 39, 60, 4, 40, 61, 19, 4, 62, 20, 41, 4,
        -1, -1, 63, 2,
    };

    /** php_sha256_crypt_r: "$5$", an optional "rounds=N$", the salt, '$' and 43 characters; null where it fails. */
    @TruffleBoundary
    static byte[] sha256(byte[] key, byte[] salt) {
        return crypt(key, salt, "SHA-256", (byte) '5', SHA256_ORDER);
    }

    /** php_sha512_crypt_r: "$6$", an optional "rounds=N$", the salt, '$' and 86 characters; null where it fails. */
    @TruffleBoundary
    static byte[] sha512(byte[] key, byte[] salt) {
        return crypt(key, salt, "SHA-512", (byte) '6', SHA512_ORDER);
    }

    private static byte[] crypt(byte[] key, byte[] setting, String algorithm, byte id, int[] order) {
        byte[] prefix = {'$', id, '$'};
        // the beginning of the salt string: the prefix should normally always be there, but just in case it isn't
        int salt = startsWith(setting, 0, prefix) ? prefix.length : 0;
        long rounds = ROUNDS_DEFAULT;
        boolean roundsCustom = false;
        if (startsWith(setting, salt, ROUNDS_PREFIX)) {
            long[] parsed = strtoul(setting, salt + ROUNDS_PREFIX.length);
            int endp = (int) parsed[1];
            if (at(setting, endp) == '$') {
                salt = endp + 1;
                long srounds = parsed[0];
                if (Long.compareUnsigned(srounds, ROUNDS_MIN) < 0 || Long.compareUnsigned(srounds, ROUNDS_MAX) > 0) {
                    return null;
                }
                rounds = srounds;
                roundsCustom = true;
            }
        }
        // the salt ends at the first '$' and is at most 16 characters
        int saltLen = 0;
        while (saltLen < SALT_LEN_MAX && at(setting, salt + saltLen) != 0 && at(setting, salt + saltLen) != '$') {
            saltLen++;
        }
        int keyLen = key.length;

        MessageDigest ctx = digest(algorithm);
        int size = ctx.getDigestLength();
        // the key, then the salt
        ctx.update(key, 0, keyLen);
        ctx.update(setting, salt, saltLen);
        // the alternate sum of key, salt and key, added to the first context
        MessageDigest altCtx = digest(algorithm);
        altCtx.update(key, 0, keyLen);
        altCtx.update(setting, salt, saltLen);
        altCtx.update(key, 0, keyLen);
        byte[] altResult = altCtx.digest();
        // for every character in the key one byte of the alternate sum
        int cnt;
        for (cnt = keyLen; cnt > size; cnt -= size) {
            ctx.update(altResult, 0, size);
        }
        ctx.update(altResult, 0, cnt);
        // for every 1 in the binary representation of the key's length the alternate sum, for every 0 the key
        for (cnt = keyLen; cnt > 0; cnt >>>= 1) {
            if ((cnt & 1) != 0) {
                ctx.update(altResult, 0, size);
            } else {
                ctx.update(key, 0, keyLen);
            }
        }
        altResult = ctx.digest();

        // the P byte sequence: for every character in the password the entire password
        altCtx.reset();
        for (cnt = 0; cnt < keyLen; cnt++) {
            altCtx.update(key, 0, keyLen);
        }
        byte[] pBytes = repeat(altCtx.digest(), keyLen);
        // the S byte sequence: the salt 16 + altResult[0] times
        altCtx.reset();
        for (cnt = 0; cnt < 16 + (altResult[0] & 0xff); cnt++) {
            altCtx.update(setting, salt, saltLen);
        }
        byte[] sBytes = repeat(altCtx.digest(), saltLen);

        // repeatedly run the collected hash value through the digest to burn CPU cycles
        for (long round = 0; round < rounds; round++) {
            ctx.reset();
            if ((round & 1) != 0) {
                ctx.update(pBytes, 0, keyLen);
            } else {
                ctx.update(altResult, 0, size);
            }
            if (round % 3 != 0) {
                ctx.update(sBytes, 0, saltLen);
            }
            if (round % 7 != 0) {
                ctx.update(pBytes, 0, keyLen);
            }
            if ((round & 1) != 0) {
                ctx.update(altResult, 0, size);
            } else {
                ctx.update(pBytes, 0, keyLen);
            }
            altResult = ctx.digest();
        }

        // the result string, into a buffer of BUFLEN bytes
        ByteArrayOutputStream out = new ByteArrayOutputStream(BUFLEN);
        int buflen = BUFLEN;
        out.write(prefix, 0, prefix.length);
        buflen -= prefix.length;
        if (roundsCustom) {
            byte[] spec = ("rounds=" + rounds + "$").getBytes(StandardCharsets.US_ASCII);
            out.write(spec, 0, spec.length);
            buflen -= spec.length;
        }
        int copied = Math.min(Math.max(0, buflen), saltLen);
        out.write(setting, salt, copied);
        buflen -= copied;
        if (buflen > 0) {
            out.write('$');
            buflen--;
        }
        for (int i = 0; i < order.length; i += 4) {
            int w = (byteAt(altResult, order[i]) << 16) | (byteAt(altResult, order[i + 1]) << 8)
                    | byteAt(altResult, order[i + 2]);
            for (int n = order[i + 3]; n > 0 && buflen > 0; n--) {
                out.write(B64T[w & 0x3f]);
                buflen--;
                w >>>= 6;
            }
        }
        if (buflen <= 0) {
            return null; // ERANGE: no room for the terminating NUL
        }
        return out.toByteArray();
    }

    /** The first length bytes of digest repeated (the P and S sequences). */
    private static byte[] repeat(byte[] digest, int length) {
        byte[] bytes = new byte[length];
        for (int at = 0; at < length; at += digest.length) {
            System.arraycopy(digest, 0, bytes, at, Math.min(digest.length, length - at));
        }
        return bytes;
    }

    private static int byteAt(byte[] digest, int i) {
        return i < 0 ? 0 : digest[i] & 0xff;
    }

    /**
     * strtoul(s + start, &endp, 10) as glibc computes it (64-bit unsigned long): leading white space, an optional
     * sign (a '-' negates the value modulo 2^64), decimal digits, ULONG_MAX on overflow. Returns the value and endp's
     * index, which is start itself when there are no digits.
     */
    private static long[] strtoul(byte[] s, int start) {
        int i = start;
        while (isSpace(at(s, i))) {
            i++;
        }
        boolean negative = false;
        if (at(s, i) == '+' || at(s, i) == '-') {
            negative = at(s, i) == '-';
            i++;
        }
        if (at(s, i) < '0' || at(s, i) > '9') {
            return new long[] {0, start};
        }
        long value = 0;
        boolean overflow = false;
        while (at(s, i) >= '0' && at(s, i) <= '9') {
            int digit = at(s, i) - '0';
            if (!overflow) {
                // value * 10 + digit beyond 2^64 - 1?
                if (Long.compareUnsigned(value, Long.divideUnsigned(-1L, 10)) > 0
                        || value == Long.divideUnsigned(-1L, 10) && digit > Long.remainderUnsigned(-1L, 10)) {
                    overflow = true;
                } else {
                    value = value * 10 + digit;
                }
            }
            i++;
        }
        if (overflow) {
            return new long[] {-1L, i};
        }
        return new long[] {negative ? -value : value, i};
    }

    /** isspace in the C locale. */
    private static boolean isSpace(int c) {
        return c == ' ' || c >= '\t' && c <= '\r';
    }

    private static boolean startsWith(byte[] s, int at, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (at(s, at + i) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** A byte of a C string: 0 at and past its end. */
    private static int at(byte[] s, int i) {
        return i < s.length ? s[i] & 0xff : 0;
    }

    private static MessageDigest digest(String algorithm) {
        try {
            return MessageDigest.getInstance(algorithm);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
