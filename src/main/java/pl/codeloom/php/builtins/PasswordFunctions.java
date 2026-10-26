package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * password_hash() and its family (php-src's ext/standard/password.c) and crypt() (crypt.c). The algorithms are
 * those of a PHP built without argon2: bcrypt ("2y") only.
 */
final class PasswordFunctions {
    private PasswordFunctions() {
    }

    /** PHP_PASSWORD_BCRYPT_COST. */
    static final long BCRYPT_DEFAULT_COST = 12;

    static void register(Registry r) {
        r.fn("password_hash", PasswordFunctions::hash);
        r.fn("password_verify", a -> verify(Str.bytes(Args.str(a, 0)), Str.bytes(Args.str(a, 1))));
        r.fn("password_needs_rehash", a -> needsRehash(Str.bytes(Args.str(a, 0)), algorithm(a, "password_needs_rehash"),
                options(a, "password_needs_rehash")));
        r.fn("password_get_info", a -> info(Str.bytes(Args.str(a, 0))));
        r.fn("password_algos", a -> PhpArray.ofStrings("2y"));
        r.fn("crypt", a -> crypt(Str.bytes(Args.str(a, 0)), Str.bytes(Args.str(a, 1))));
    }

    /** php_password_algo_find_zval: true for bcrypt, false for an algorithm this build doesn't have. */
    private static boolean algorithm(Object[] a, String function) {
        Object algo = Args.get(a, 1, PhpNull.NULL);
        if (algo == PhpNull.NULL) {
            return true;
        }
        if (algo instanceof TruffleString name) {
            return Str.java(name).equals("2y");
        }
        if (algo instanceof PhpArray || algo instanceof PhpObject) {
            throw new PhpError("TypeError", function + "(): Argument #2 ($algo) must be of type string|int|null, "
                    + Convert.typeName(algo) + " given");
        }
        long id = Convert.toLong(algo);
        return id == 0 || id == 1;
    }

    private static PhpArray options(Object[] a, String function) {
        return Args.has(a, 2) ? Args.array(a, 2, function) : null;
    }

    @TruffleBoundary
    private static Object hash(PhpContext context, Object[] a) {
        byte[] password = Str.bytes(Args.str(a, 0));
        if (!algorithm(a, "password_hash")) {
            throw new PhpError("ValueError",
                    "password_hash(): Argument #2 ($algo) must be a valid password hashing algorithm");
        }
        PhpArray options = options(a, "password_hash");
        for (byte b : password) {
            if (b == 0) {
                throw new PhpError("ValueError", "Bcrypt password must not contain null character");
            }
        }
        long cost = cost(options);
        if (cost < 4 || cost > 31) {
            throw new PhpError("ValueError", "Invalid bcrypt cost parameter specified: " + cost);
        }
        if (options != null && options.get(ArrayKey.of(Str.of("salt"))) != null) {
            ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of("password_hash(): The \"salt\" option has "
                    + "been ignored, since providing a custom salt is no longer supported"), SourceLocation.current());
        }
        byte[] setting = (String.format("$2y$%02d$", cost) + salt(22)).getBytes(StandardCharsets.US_ASCII);
        byte[] hash = Bcrypt.crypt(password, setting);
        if (hash == null) {
            throw new PhpError("Error", "Password hashing failed for unknown reason");
        }
        return Str.of(hash);
    }

    private static long cost(PhpArray options) {
        Object cost = options == null ? null : options.get(ArrayKey.of(Str.of("cost")));
        return cost == null ? BCRYPT_DEFAULT_COST : Convert.toLong(cost);
    }

    /** php_password_make_salt: length characters of base64 ('+' as '.') of random bytes. */
    private static String salt(int length) {
        byte[] random = new byte[length * 3 / 4 + 1];
        EncodingFunctions.RANDOM.nextBytes(random);
        return Base64.getEncoder().encodeToString(random).substring(0, length).replace('+', '.');
    }

    /** php_password_bcrypt_get_cost: a "$2y$NN$" hash of 60 bytes with a cost in 4..31, or -1. */
    private static int bcryptCost(byte[] hash) {
        if (hash.length != 60 || hash[0] != '$' || hash[1] != '2' || hash[2] != 'y' || hash[3] != '$'
                || !isDigit(hash[4]) || !isDigit(hash[5]) || hash[6] != '$') {
            return -1;
        }
        int cost = (hash[4] - '0') * 10 + (hash[5] - '0');
        return cost >= 4 && cost <= 31 ? cost : -1;
    }

    private static boolean isDigit(byte b) {
        return b >= '0' && b <= '9';
    }

    /** php_password_algo_extract_ident: the text between the first byte and the next '$', or null. */
    private static String ident(byte[] hash) {
        if (hash.length < 3) {
            return null;
        }
        for (int i = 1; i < hash.length; i++) {
            if (hash[i] == '$') {
                return new String(hash, 1, i - 1, StandardCharsets.ISO_8859_1);
            }
            if (hash[i] == 0) {
                return null;
            }
        }
        return null;
    }

    /** Whether the hash is a valid bcrypt one by its ident ("2y" and the format), which password_get_info names. */
    private static boolean isBcrypt(byte[] hash) {
        return "2y".equals(ident(hash)) && bcryptCost(hash) >= 0;
    }

    /** password_verify: any hash php_crypt knows is checked as bcrypt's verify does, in constant time. */
    @TruffleBoundary
    private static boolean verify(byte[] password, byte[] hash) {
        byte[] result = phpCrypt(password, hash);
        return result != null && hash.length >= 13 && MessageDigest.isEqual(result, hash);
    }

    @TruffleBoundary
    private static boolean needsRehash(byte[] hash, boolean bcrypt, PhpArray options) {
        if (!bcrypt) {
            return false; // an unknown new algorithm never asks for a rehash
        }
        return !isBcrypt(hash) || bcryptCost(hash) != cost(options);
    }

    @TruffleBoundary
    private static PhpArray info(byte[] hash) {
        PhpArray info = new PhpArray();
        PhpArray options = new PhpArray();
        if (isBcrypt(hash)) {
            info.put(Str.of("algo"), Str.of("2y"));
            info.put(Str.of("algoName"), Str.of("bcrypt"));
            options.put(Str.of("cost"), (long) bcryptCost(hash));
        } else {
            info.put(Str.of("algo"), PhpNull.NULL);
            info.put(Str.of("algoName"), Str.of("unknown"));
        }
        info.put(Str.of("options"), options);
        return info;
    }

    /** crypt(): the salt is at most PHP_MAX_SALT_LEN (123) bytes; a failure is "*0", or "*1" for a "*0" salt. */
    @TruffleBoundary
    private static TruffleString crypt(byte[] string, byte[] salt) {
        byte[] result = phpCrypt(string, Arrays.copyOf(salt, Math.min(salt.length, 123)));
        if (result == null) {
            return Str.of(salt.length >= 2 && salt[0] == '*' && salt[1] == '0' ? "*1" : "*0");
        }
        return Str.of(result);
    }

    /** php_crypt: the password and the salt are C strings (up to their first NUL); null where it fails. */
    private static byte[] phpCrypt(byte[] password, byte[] salt) {
        byte[] key = cString(password);
        byte[] setting = cString(salt);
        int c0 = setting.length > 0 ? setting[0] : 0;
        int c1 = setting.length > 1 ? setting[1] : 0;
        if (c0 == '*' && (c1 == '0' || c1 == '1')) {
            return null;
        }
        if (c0 == '$' && c1 == '2' && setting.length > 3 && setting[3] == '$') {
            return Bcrypt.crypt(key, setting);
        }
        if (c0 == '$' && (c1 == '1' || c1 == '5' || c1 == '6') && setting.length > 2 && setting[2] == '$'
                || c0 == '_' || isSaltCharacter(c0) && isSaltCharacter(c1)) {
            throw new PhpError("crypt() with an MD5, SHA-256, SHA-512 or DES salt is not supported");
        }
        return null;
    }

    /** IS_VALID_SALT_CHARACTER: the DES salt alphabet. */
    private static boolean isSaltCharacter(int c) {
        return c >= '.' && c <= '9' || c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z';
    }

    private static byte[] cString(byte[] bytes) {
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                return Arrays.copyOf(bytes, i);
            }
        }
        return bytes;
    }
}
