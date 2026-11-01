package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * MD5-based crypt ("$1$", Poul-Henning Kamp's md5crypt) as php-src's php_crypt_r.c computes it. The key and the
 * setting are C strings there: each ends at its first NUL byte.
 */
final class CryptMd5 {
    private CryptMd5() {
    }

    private static final byte[] MAGIC = "$1$".getBytes(StandardCharsets.US_ASCII);

    private static final byte[] ITOA64 =
            "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);

    /** php_md5_crypt_r: "$1$", the salt (up to 8 characters before a '$'), '$' and 22 characters of hash. */
    @TruffleBoundary
    static byte[] crypt(byte[] pw, byte[] salt) {
        int pwl = pw.length;
        // the salt: past the magic string if it starts with it, up to the first '$', at most 8 characters
        int sp = startsWith(salt, MAGIC) ? MAGIC.length : 0;
        int ep = sp;
        while (at(salt, ep) != 0 && at(salt, ep) != '$' && ep < sp + 8) {
            ep++;
        }
        int sl = ep - sp;

        MessageDigest ctx = md5();
        // the password first, since that is what is most unknown; then the magic string; then the raw salt
        ctx.update(pw, 0, pwl);
        ctx.update(MAGIC);
        ctx.update(salt, sp, sl);
        // then just as many characters of MD5(pw, salt, pw)
        MessageDigest ctx1 = md5();
        ctx1.update(pw, 0, pwl);
        ctx1.update(salt, sp, sl);
        ctx1.update(pw, 0, pwl);
        byte[] fin = ctx1.digest();
        for (int pl = pwl; pl > 0; pl -= 16) {
            ctx.update(fin, 0, Math.min(pl, 16));
        }
        // "then something really weird": final was zeroed by now, so a set bit adds a NUL byte
        Arrays.fill(fin, (byte) 0);
        for (int i = pwl; i != 0; i >>>= 1) {
            if ((i & 1) != 0) {
                ctx.update(fin, 0, 1);
            } else {
                ctx.update(pw, 0, 1);
            }
        }
        fin = ctx.digest();

        // 1000 rounds, so that things don't run too fast
        for (int i = 0; i < 1000; i++) {
            ctx1.reset();
            if ((i & 1) != 0) {
                ctx1.update(pw, 0, pwl);
            } else {
                ctx1.update(fin, 0, 16);
            }
            if (i % 3 != 0) {
                ctx1.update(salt, sp, sl);
            }
            if (i % 7 != 0) {
                ctx1.update(pw, 0, pwl);
            }
            if ((i & 1) != 0) {
                ctx1.update(fin, 0, 16);
            } else {
                ctx1.update(pw, 0, pwl);
            }
            fin = ctx1.digest();
        }

        byte[] passwd = new byte[MAGIC.length + sl + 1 + 22];
        System.arraycopy(MAGIC, 0, passwd, 0, MAGIC.length);
        System.arraycopy(salt, sp, passwd, MAGIC.length, sl);
        passwd[MAGIC.length + sl] = '$';
        int p = MAGIC.length + sl + 1;
        p = to64(passwd, p, (u(fin, 0) << 16) | (u(fin, 6) << 8) | u(fin, 12), 4);
        p = to64(passwd, p, (u(fin, 1) << 16) | (u(fin, 7) << 8) | u(fin, 13), 4);
        p = to64(passwd, p, (u(fin, 2) << 16) | (u(fin, 8) << 8) | u(fin, 14), 4);
        p = to64(passwd, p, (u(fin, 3) << 16) | (u(fin, 9) << 8) | u(fin, 15), 4);
        p = to64(passwd, p, (u(fin, 4) << 16) | (u(fin, 10) << 8) | u(fin, 5), 4);
        to64(passwd, p, u(fin, 11), 2);
        return passwd;
    }

    /** to64: n characters of v, six bits each from the lowest; returns the position after them. */
    private static int to64(byte[] s, int p, int v, int n) {
        while (--n >= 0) {
            s[p++] = ITOA64[v & 0x3f];
            v >>= 6;
        }
        return p;
    }

    private static int u(byte[] b, int i) {
        return b[i] & 0xff;
    }

    private static boolean startsWith(byte[] s, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (at(s, i) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** A byte of a C string: 0 at and past its end. */
    private static int at(byte[] s, int i) {
        return i < s.length ? s[i] & 0xff : 0;
    }

    private static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
