package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.nio.charset.StandardCharsets;

/**
 * DES-based crypt as php-src's crypt_freesec.c (FreeSec's) computes it: the traditional form (a 2-character salt,
 * 25 iterations, the first 8 characters of the key) and BSDi's extended form ("_", 4 characters of iteration count,
 * 4 of salt, a key of any length). Keys and settings are C strings there: each ends at its first NUL byte.
 */
final class CryptDes {
    private CryptDes() {
    }

    private static final byte[] IP = {
        58, 50, 42, 34, 26, 18, 10, 2, 60, 52, 44, 36, 28, 20, 12, 4,
        62, 54, 46, 38, 30, 22, 14, 6, 64, 56, 48, 40, 32, 24, 16, 8,
        57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3,
        61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7,
    };

    private static final byte[] KEY_PERM = {
        57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18,
        10, 2, 59, 51, 43, 35, 27, 19, 11, 3, 60, 52, 44, 36,
        63, 55, 47, 39, 31, 23, 15, 7, 62, 54, 46, 38, 30, 22,
        14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 28, 20, 12, 4,
    };

    private static final byte[] KEY_SHIFTS = {1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1};

    private static final byte[] COMP_PERM = {
        14, 17, 11, 24, 1, 5, 3, 28, 15, 6, 21, 10,
        23, 19, 12, 4, 26, 8, 16, 7, 27, 20, 13, 2,
        41, 52, 31, 37, 47, 55, 30, 40, 51, 45, 33, 48,
        44, 49, 39, 56, 34, 53, 46, 42, 50, 36, 29, 32,
    };

    /** The eight S-boxes, one after another (sbox[box][i] is at box * 64 + i). */
    private static final byte[] SBOX = {
        14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
        0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
        4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
        15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13,

        15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
        3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5,
        0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
        13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9,

        10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
        13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
        13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
        1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12,

        7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
        13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
        10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
        3, 15, 0, 6, 10, 1, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14,

        2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
        14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
        4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
        11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3,

        12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
        10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
        9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
        4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13,

        4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
        13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
        1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
        6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12,

        13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
        1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
        7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
        2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11,
    };

    private static final byte[] PBOX = {
        16, 7, 20, 21, 29, 12, 28, 17, 1, 15, 23, 26, 5, 18, 31, 10,
        2, 8, 24, 14, 32, 27, 3, 9, 19, 13, 30, 6, 22, 11, 4, 25,
    };

    private static final byte[] ASCII64 =
            "./0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.US_ASCII);

    /** The tables _crypt_extended_init derives from the ones above, each row flattened (row * width + column). */
    private static final int[] M_SBOX = new int[4 * 4096];
    private static final int[] PSBOX = new int[4 * 256];
    private static final int[] IP_MASKL = new int[8 * 256];
    private static final int[] IP_MASKR = new int[8 * 256];
    private static final int[] FP_MASKL = new int[8 * 256];
    private static final int[] FP_MASKR = new int[8 * 256];
    private static final int[] KEY_PERM_MASKL = new int[8 * 128];
    private static final int[] KEY_PERM_MASKR = new int[8 * 128];
    private static final int[] COMP_MASKL = new int[8 * 128];
    private static final int[] COMP_MASKR = new int[8 * 128];

    static {
        init();
    }

    /** bits32[i]: the i-th bit from the top of a 32-bit word. */
    private static int bits32(int i) {
        return 0x80000000 >>> i;
    }

    /** bits8[i]: the i-th bit from the top of a byte. */
    private static int bits8(int i) {
        return 0x80 >>> i;
    }

    /** _crypt_extended_init. */
    private static void init() {
        // invert the S-boxes, reordering the input bits
        int[] uSbox = new int[8 * 64];
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 64; j++) {
                int b = (j & 0x20) | ((j & 1) << 4) | ((j >> 1) & 0xf);
                uSbox[i * 64 + j] = SBOX[i * 64 + b];
            }
        }
        // convert the inverted S-boxes into 4 arrays of 8 bits, each handling 12 bits of the S-box input
        for (int b = 0; b < 4; b++) {
            for (int i = 0; i < 64; i++) {
                for (int j = 0; j < 64; j++) {
                    M_SBOX[b * 4096 + ((i << 6) | j)] = (uSbox[(b << 1) * 64 + i] << 4) | uSbox[((b << 1) + 1) * 64 + j];
                }
            }
        }
        // the initial and final permutations in a useful form, and the inverted key permutation
        int[] initPerm = new int[64];
        int[] finalPerm = new int[64];
        int[] invKeyPerm = new int[64];
        int[] invCompPerm = new int[56];
        for (int i = 0; i < 64; i++) {
            finalPerm[i] = IP[i] - 1;
            initPerm[finalPerm[i]] = i;
            invKeyPerm[i] = 255;
        }
        // invert the key permutation and initialise the inverted key compression permutation
        for (int i = 0; i < 56; i++) {
            invKeyPerm[KEY_PERM[i] - 1] = i;
            invCompPerm[i] = 255;
        }
        // invert the key compression permutation
        for (int i = 0; i < 48; i++) {
            invCompPerm[COMP_PERM[i] - 1] = i;
        }
        // the OR-mask arrays for the initial and final permutations, and for the key initial and compression
        // permutations (bits28 and bits24 are bits32 from its 4th and 8th entry)
        for (int k = 0; k < 8; k++) {
            for (int i = 0; i < 256; i++) {
                int il = 0;
                int ir = 0;
                int fl = 0;
                int fr = 0;
                for (int j = 0; j < 8; j++) {
                    int inbit = 8 * k + j;
                    if ((i & bits8(j)) != 0) {
                        int obit = initPerm[inbit];
                        if (obit < 32) {
                            il |= bits32(obit);
                        } else {
                            ir |= bits32(obit - 32);
                        }
                        obit = finalPerm[inbit];
                        if (obit < 32) {
                            fl |= bits32(obit);
                        } else {
                            fr |= bits32(obit - 32);
                        }
                    }
                }
                IP_MASKL[k * 256 + i] = il;
                IP_MASKR[k * 256 + i] = ir;
                FP_MASKL[k * 256 + i] = fl;
                FP_MASKR[k * 256 + i] = fr;
            }
            for (int i = 0; i < 128; i++) {
                int il = 0;
                int ir = 0;
                for (int j = 0; j < 7; j++) {
                    int inbit = 8 * k + j;
                    if ((i & bits8(j + 1)) != 0) {
                        int obit = invKeyPerm[inbit];
                        if (obit == 255) {
                            continue;
                        }
                        if (obit < 28) {
                            il |= bits32(obit + 4);
                        } else {
                            ir |= bits32(obit - 28 + 4);
                        }
                    }
                }
                KEY_PERM_MASKL[k * 128 + i] = il;
                KEY_PERM_MASKR[k * 128 + i] = ir;
                il = 0;
                ir = 0;
                for (int j = 0; j < 7; j++) {
                    int inbit = 7 * k + j;
                    if ((i & bits8(j + 1)) != 0) {
                        int obit = invCompPerm[inbit];
                        if (obit == 255) {
                            continue;
                        }
                        if (obit < 24) {
                            il |= bits32(obit + 8);
                        } else {
                            ir |= bits32(obit - 24 + 8);
                        }
                    }
                }
                COMP_MASKL[k * 128 + i] = il;
                COMP_MASKR[k * 128 + i] = ir;
            }
        }
        // invert the P-box permutation and convert it into OR-masks for the output of the S-box arrays above
        int[] unPbox = new int[32];
        for (int i = 0; i < 32; i++) {
            unPbox[PBOX[i] - 1] = i;
        }
        for (int b = 0; b < 4; b++) {
            for (int i = 0; i < 256; i++) {
                int p = 0;
                for (int j = 0; j < 8; j++) {
                    if ((i & bits8(j)) != 0) {
                        p |= bits32(unPbox[8 * b + j]);
                    }
                }
                PSBOX[b * 256 + i] = p;
            }
        }
    }

    /** struct php_crypt_extended_data, as des_init_local leaves it (php_crypt starts each call from zeros). */
    private static final class Data {
        int saltbits;
        int oldSalt;
        final int[] enKeysl = new int[16];
        final int[] enKeysr = new int[16];
        final int[] deKeysl = new int[16];
        final int[] deKeysr = new int[16];
        int oldRawkey0;
        int oldRawkey1;

        /** setup_salt: the 24 salt bits, reversed. */
        void setupSalt(int salt) {
            if (salt == oldSalt) {
                return;
            }
            oldSalt = salt;
            int bits = 0;
            int saltbit = 1;
            int obit = 0x800000;
            for (int i = 0; i < 24; i++) {
                if ((salt & saltbit) != 0) {
                    bits |= obit;
                }
                saltbit <<= 1;
                obit >>>= 1;
            }
            saltbits = bits;
        }

        /** des_setkey: the 16 round keys of an 8-byte key, for encrypting and for decrypting. */
        void setKey(byte[] key) {
            int rawkey0 = bigEndian(key, 0);
            int rawkey1 = bigEndian(key, 4);
            if ((rawkey0 | rawkey1) != 0 && rawkey0 == oldRawkey0 && rawkey1 == oldRawkey1) {
                // already set up for this key (never so for a zero key, which keeps the starting conditions simple)
                return;
            }
            oldRawkey0 = rawkey0;
            oldRawkey1 = rawkey1;
            // the key permutation, split into two 28-bit subkeys
            int k0 = KEY_PERM_MASKL[rawkey0 >>> 25]
                    | KEY_PERM_MASKL[128 + ((rawkey0 >>> 17) & 0x7f)]
                    | KEY_PERM_MASKL[2 * 128 + ((rawkey0 >>> 9) & 0x7f)]
                    | KEY_PERM_MASKL[3 * 128 + ((rawkey0 >>> 1) & 0x7f)]
                    | KEY_PERM_MASKL[4 * 128 + (rawkey1 >>> 25)]
                    | KEY_PERM_MASKL[5 * 128 + ((rawkey1 >>> 17) & 0x7f)]
                    | KEY_PERM_MASKL[6 * 128 + ((rawkey1 >>> 9) & 0x7f)]
                    | KEY_PERM_MASKL[7 * 128 + ((rawkey1 >>> 1) & 0x7f)];
            int k1 = KEY_PERM_MASKR[rawkey0 >>> 25]
                    | KEY_PERM_MASKR[128 + ((rawkey0 >>> 17) & 0x7f)]
                    | KEY_PERM_MASKR[2 * 128 + ((rawkey0 >>> 9) & 0x7f)]
                    | KEY_PERM_MASKR[3 * 128 + ((rawkey0 >>> 1) & 0x7f)]
                    | KEY_PERM_MASKR[4 * 128 + (rawkey1 >>> 25)]
                    | KEY_PERM_MASKR[5 * 128 + ((rawkey1 >>> 17) & 0x7f)]
                    | KEY_PERM_MASKR[6 * 128 + ((rawkey1 >>> 9) & 0x7f)]
                    | KEY_PERM_MASKR[7 * 128 + ((rawkey1 >>> 1) & 0x7f)];
            // rotate the subkeys and do the compression permutation
            int shifts = 0;
            for (int round = 0; round < 16; round++) {
                shifts += KEY_SHIFTS[round];
                int t0 = (k0 << shifts) | (k0 >>> (28 - shifts));
                int t1 = (k1 << shifts) | (k1 >>> (28 - shifts));
                int l = COMP_MASKL[(t0 >>> 21) & 0x7f]
                        | COMP_MASKL[128 + ((t0 >>> 14) & 0x7f)]
                        | COMP_MASKL[2 * 128 + ((t0 >>> 7) & 0x7f)]
                        | COMP_MASKL[3 * 128 + (t0 & 0x7f)]
                        | COMP_MASKL[4 * 128 + ((t1 >>> 21) & 0x7f)]
                        | COMP_MASKL[5 * 128 + ((t1 >>> 14) & 0x7f)]
                        | COMP_MASKL[6 * 128 + ((t1 >>> 7) & 0x7f)]
                        | COMP_MASKL[7 * 128 + (t1 & 0x7f)];
                int r = COMP_MASKR[(t0 >>> 21) & 0x7f]
                        | COMP_MASKR[128 + ((t0 >>> 14) & 0x7f)]
                        | COMP_MASKR[2 * 128 + ((t0 >>> 7) & 0x7f)]
                        | COMP_MASKR[3 * 128 + (t0 & 0x7f)]
                        | COMP_MASKR[4 * 128 + ((t1 >>> 21) & 0x7f)]
                        | COMP_MASKR[5 * 128 + ((t1 >>> 14) & 0x7f)]
                        | COMP_MASKR[6 * 128 + ((t1 >>> 7) & 0x7f)]
                        | COMP_MASKR[7 * 128 + (t1 & 0x7f)];
                enKeysl[round] = l;
                deKeysl[15 - round] = l;
                enKeysr[round] = r;
                deKeysr[15 - round] = r;
            }
        }

        /**
         * do_des: count salted DES encryptions (decryptions for a negative count) of the block lIn:rIn, both
         * pseudo-big-endian; the result in out[0] and out[1]. False for a zero count, which do_des fails.
         */
        boolean doDes(int lIn, int rIn, int[] out, int count) {
            int[] kl1;
            int[] kr1;
            if (count == 0) {
                return false;
            } else if (count > 0) {
                kl1 = enKeysl;
                kr1 = enKeysr;
            } else {
                count = -count;
                kl1 = deKeysl;
                kr1 = deKeysr;
            }
            // the initial permutation (IP)
            int l = IP_MASKL[lIn >>> 24]
                    | IP_MASKL[256 + ((lIn >>> 16) & 0xff)]
                    | IP_MASKL[2 * 256 + ((lIn >>> 8) & 0xff)]
                    | IP_MASKL[3 * 256 + (lIn & 0xff)]
                    | IP_MASKL[4 * 256 + (rIn >>> 24)]
                    | IP_MASKL[5 * 256 + ((rIn >>> 16) & 0xff)]
                    | IP_MASKL[6 * 256 + ((rIn >>> 8) & 0xff)]
                    | IP_MASKL[7 * 256 + (rIn & 0xff)];
            int r = IP_MASKR[lIn >>> 24]
                    | IP_MASKR[256 + ((lIn >>> 16) & 0xff)]
                    | IP_MASKR[2 * 256 + ((lIn >>> 8) & 0xff)]
                    | IP_MASKR[3 * 256 + (lIn & 0xff)]
                    | IP_MASKR[4 * 256 + (rIn >>> 24)]
                    | IP_MASKR[5 * 256 + ((rIn >>> 16) & 0xff)]
                    | IP_MASKR[6 * 256 + ((rIn >>> 8) & 0xff)]
                    | IP_MASKR[7 * 256 + (rIn & 0xff)];
            int salt = saltbits;
            int f = 0;
            while (count-- != 0) {
                for (int round = 0; round < 16; round++) {
                    // expand R to 48 bits (the E-box)
                    int r48l = ((r & 0x00000001) << 23)
                            | ((r & 0xf8000000) >>> 9)
                            | ((r & 0x1f800000) >>> 11)
                            | ((r & 0x01f80000) >>> 13)
                            | ((r & 0x001f8000) >>> 15);
                    int r48r = ((r & 0x0001f800) << 7)
                            | ((r & 0x00001f80) << 5)
                            | ((r & 0x000001f8) << 3)
                            | ((r & 0x0000001f) << 1)
                            | ((r & 0x80000000) >>> 31);
                    // the salting crypt() does, and the XOR with the permuted key
                    f = (r48l ^ r48r) & salt;
                    r48l ^= f ^ kl1[round];
                    r48r ^= f ^ kr1[round];
                    // the S-box lookups (which shrink it back to 32 bits) with the P-box permutation
                    f = PSBOX[M_SBOX[r48l >>> 12]]
                            | PSBOX[256 + M_SBOX[4096 + (r48l & 0xfff)]]
                            | PSBOX[2 * 256 + M_SBOX[2 * 4096 + (r48r >>> 12)]]
                            | PSBOX[3 * 256 + M_SBOX[3 * 4096 + (r48r & 0xfff)]];
                    f ^= l;
                    l = r;
                    r = f;
                }
                r = l;
                l = f;
            }
            // the final permutation (the inverse of IP)
            out[0] = FP_MASKL[l >>> 24]
                    | FP_MASKL[256 + ((l >>> 16) & 0xff)]
                    | FP_MASKL[2 * 256 + ((l >>> 8) & 0xff)]
                    | FP_MASKL[3 * 256 + (l & 0xff)]
                    | FP_MASKL[4 * 256 + (r >>> 24)]
                    | FP_MASKL[5 * 256 + ((r >>> 16) & 0xff)]
                    | FP_MASKL[6 * 256 + ((r >>> 8) & 0xff)]
                    | FP_MASKL[7 * 256 + (r & 0xff)];
            out[1] = FP_MASKR[l >>> 24]
                    | FP_MASKR[256 + ((l >>> 16) & 0xff)]
                    | FP_MASKR[2 * 256 + ((l >>> 8) & 0xff)]
                    | FP_MASKR[3 * 256 + (l & 0xff)]
                    | FP_MASKR[4 * 256 + (r >>> 24)]
                    | FP_MASKR[5 * 256 + ((r >>> 16) & 0xff)]
                    | FP_MASKR[6 * 256 + ((r >>> 8) & 0xff)]
                    | FP_MASKR[7 * 256 + (r & 0xff)];
            return true;
        }

        /** des_cipher: the 8-byte block in, encrypted count times under salt, into out (which may be in). */
        boolean cipher(byte[] in, byte[] out, int salt, int count) {
            setupSalt(salt);
            int[] result = new int[2];
            boolean ok = doDes(bigEndian(in, 0), bigEndian(in, 4), result, count);
            for (int i = 0; i < 4; i++) {
                out[i] = (byte) (result[0] >>> (24 - 8 * i));
                out[4 + i] = (byte) (result[1] >>> (24 - 8 * i));
            }
            return ok;
        }
    }

    private static int bigEndian(byte[] b, int at) {
        return (b[at] & 0xff) << 24 | (b[at + 1] & 0xff) << 16 | (b[at + 2] & 0xff) << 8 | (b[at + 3] & 0xff);
    }

    /** ascii_to_bin: a character's value in the crypt base-64 alphabet, as the C char (signed) computes it. */
    private static int asciiToBin(int ch) {
        int sch = (byte) ch;
        int retval = sch - '.';
        if (sch >= 'A') {
            retval = sch - ('A' - 12);
            if (sch >= 'a') {
                retval = sch - ('a' - 38);
            }
        }
        return retval & 0x3f;
    }

    /** ascii_is_unsafe: NUL and the characters that would break the passwd file format. */
    private static boolean asciiIsUnsafe(int ch) {
        return ch == 0 || ch == '\n' || ch == ':';
    }

    /** _crypt_extended_r: the 13-byte (traditional) or 20-byte (extended) hash, or null where it fails. */
    @TruffleBoundary
    static byte[] crypt(byte[] key, byte[] setting) {
        Data data = new Data();
        // copy the key, shifting each character up by one bit and padding with zeros
        byte[] keybuf = new byte[8];
        int k = 0;
        for (int q = 0; q < 8; q++) {
            keybuf[q] = (byte) (at(key, k) << 1);
            if (at(key, k) != 0) {
                k++;
            }
        }
        data.setKey(keybuf);

        int count;
        int salt;
        byte[] output;
        int p;
        if (at(setting, 0) == '_') {
            // "new"-style: setting is an underscore, 4 characters of count, 4 of salt; the key has any length
            count = 0;
            for (int i = 1; i < 5; i++) {
                int value = asciiToBin(at(setting, i));
                if (ASCII64[value] != at(setting, i)) {
                    return null;
                }
                count |= value << (i - 1) * 6;
            }
            if (count == 0) {
                return null;
            }
            salt = 0;
            for (int i = 5; i < 9; i++) {
                int value = asciiToBin(at(setting, i));
                if (ASCII64[value] != at(setting, i)) {
                    return null;
                }
                salt |= value << (i - 5) * 6;
            }
            while (at(key, k) != 0) {
                // encrypt the key with itself, and XOR it with the next 8 characters of the key
                if (!data.cipher(keybuf, keybuf, 0, 1)) {
                    return null;
                }
                for (int q = 0; q < 8 && at(key, k) != 0; q++) {
                    keybuf[q] ^= (byte) (at(key, k++) << 1);
                }
                data.setKey(keybuf);
            }
            output = new byte[9 + 11];
            System.arraycopy(setting, 0, output, 0, 9);
            p = 9;
        } else {
            // "old"-style: setting is 2 characters of salt; the key counts up to 8 characters
            count = 25;
            if (asciiIsUnsafe(at(setting, 0)) || asciiIsUnsafe(at(setting, 1))) {
                return null;
            }
            salt = (asciiToBin(at(setting, 1)) << 6) | asciiToBin(at(setting, 0));
            output = new byte[2 + 11];
            output[0] = setting[0];
            output[1] = setting[1];
            p = 2;
        }
        data.setupSalt(salt);
        int[] r = new int[2];
        if (!data.doDes(0, 0, r, count)) {
            return null;
        }
        // encode the result
        int l = r[0] >>> 8;
        output[p++] = ASCII64[(l >>> 18) & 0x3f];
        output[p++] = ASCII64[(l >>> 12) & 0x3f];
        output[p++] = ASCII64[(l >>> 6) & 0x3f];
        output[p++] = ASCII64[l & 0x3f];
        l = (r[0] << 16) | ((r[1] >>> 16) & 0xffff);
        output[p++] = ASCII64[(l >>> 18) & 0x3f];
        output[p++] = ASCII64[(l >>> 12) & 0x3f];
        output[p++] = ASCII64[(l >>> 6) & 0x3f];
        output[p++] = ASCII64[l & 0x3f];
        l = r[1] << 2;
        output[p++] = ASCII64[(l >>> 12) & 0x3f];
        output[p++] = ASCII64[(l >>> 6) & 0x3f];
        output[p] = ASCII64[l & 0x3f];
        return output;
    }

    /** A byte of a C string: 0 at and past its end. */
    private static int at(byte[] s, int i) {
        return i < s.length ? s[i] & 0xff : 0;
    }
}
