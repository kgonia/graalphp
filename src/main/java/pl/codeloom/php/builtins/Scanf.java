package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.Str;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.Arrays;

/**
 * sscanf() and fscanf(): a port of php-src's ext/standard/scanf.c (from Tcl's), ValidateFormat and
 * php_sscanf_internal. The string and the format end at their first NUL, as the C code reads them.
 */
final class Scanf {
    private static final int SCAN_NOSKIP = 0x1;
    private static final int SCAN_SUPPRESS = 0x2;
    private static final int SCAN_UNSIGNED = 0x4;
    private static final int SCAN_WIDTH = 0x8;
    private static final int SCAN_SIGNOK = 0x10;
    private static final int SCAN_NODIGITS = 0x20;
    private static final int SCAN_NOZERO = 0x40;
    private static final int SCAN_XOK = 0x80;
    private static final int SCAN_PTOK = 0x100;
    private static final int SCAN_EXPOK = 0x200;
    private static final int SCAN_MAX_ARGS = 0xFF;
    private static final BigInteger ULONG_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);

    private Scanf() {
    }

    /** The byte at i as C reads it: 0 past the end. */
    private static int at(byte[] s, int i) {
        return i < s.length ? s[i] & 0xFF : 0;
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    /** isspace() in the C locale. */
    private static boolean isSpace(int c) {
        return c == ' ' || c >= '\t' && c <= '\r';
    }

    /** ZEND_STRTOUL(s + from, &end, 10) on digits: the value (saturated) and, in end[0], where the digits end. */
    private static long strtoul(byte[] s, int from, int[] end) {
        long value = 0;
        int i = from;
        boolean overflow = false;
        for (; isDigit(at(s, i)); i++) {
            int digit = at(s, i) - '0';
            if (Long.compareUnsigned(value, Long.divideUnsigned(-1L - digit, 10)) > 0) {
                overflow = true;
            } else {
                value = value * 10 + digit;
            }
        }
        end[0] = i;
        return overflow ? -1L : value;
    }

    /**
     * ValidateFormat: the format must be well formed and assign each variable exactly once. The number of values
     * the scan fills (numVars, or the format's count when no variables are given).
     */
    private static int validate(byte[] format, int numVars) {
        int[] nassign = new int[Math.max(16, numVars)];
        int objIndex = 0;
        int xpgSize = 0;
        boolean gotXpg = false;
        boolean gotSequential = false;
        int[] end = new int[1];
        int p = 0;
        while (at(format, p) != 0) {
            int ch = at(format, p++);
            int flags = 0;
            if (ch != '%') {
                continue;
            }
            ch = at(format, p++);
            if (ch == '%') {
                continue;
            }
            boolean xpg = false;
            if (ch == '*') {
                flags |= SCAN_SUPPRESS;
                ch = at(format, p++);
                xpg = true;
            } else if (isDigit(ch)) {
                int value = (int) strtoul(format, p - 1, end);
                if (at(format, end[0]) == '$') {
                    p = end[0] + 1;
                    ch = at(format, p++);
                    gotXpg = true;
                    if (gotSequential) {
                        throw new PhpError("ValueError", "cannot mix \"%\" and \"%n$\" conversion specifiers");
                    }
                    if (value < 1 || numVars != 0 && value > numVars) {
                        throw badIndex(true);
                    } else if (numVars == 0) {
                        if (value > SCAN_MAX_ARGS) {
                            throw badIndex(true);
                        }
                        xpgSize = Math.max(xpgSize, value);
                    }
                    objIndex = value - 1;
                    xpg = true;
                }
            }
            if (!xpg) {
                gotSequential = true;
                if (gotXpg) {
                    throw new PhpError("ValueError", "cannot mix \"%\" and \"%n$\" conversion specifiers");
                }
            }
            if (isDigit(ch)) {
                strtoul(format, p - 1, end);
                p = end[0];
                flags |= SCAN_WIDTH;
                ch = at(format, p++);
            }
            if (ch == 'l' || ch == 'L' || ch == 'h') {
                ch = at(format, p++);
            }
            if ((flags & SCAN_SUPPRESS) == 0 && numVars != 0 && objIndex >= numVars) {
                throw badIndex(gotXpg);
            }
            switch (ch) {
                case 'n', 'd', 'D', 'i', 'o', 'x', 'X', 'u', 'f', 'e', 'E', 'g', 's', 'c' -> {
                }
                case '[' -> {
                    if (at(format, p) == 0) {
                        throw unmatchedSet();
                    }
                    ch = at(format, p++);
                    if (ch == '^') {
                        if (at(format, p) == 0) {
                            throw unmatchedSet();
                        }
                        ch = at(format, p++);
                    }
                    if (ch == ']') {
                        if (at(format, p) == 0) {
                            throw unmatchedSet();
                        }
                        ch = at(format, p++);
                    }
                    while (ch != ']') {
                        if (at(format, p) == 0) {
                            throw unmatchedSet();
                        }
                        ch = at(format, p++);
                    }
                }
                // a format ending in "%": the message ends at the NUL its %c writes
                default -> throw new PhpError("ValueError", "Bad scan conversion character \""
                        + (ch == 0 ? "" : (char) ch + "\""));
            }
            if ((flags & SCAN_SUPPRESS) == 0) {
                if (objIndex >= nassign.length) {
                    nassign = Arrays.copyOf(nassign, Math.max(objIndex + 1, xpgSize != 0 ? xpgSize
                            : nassign.length + 16));
                }
                nassign[objIndex]++;
                objIndex++;
            }
        }
        if (numVars == 0) {
            numVars = xpgSize != 0 ? xpgSize : objIndex;
        }
        for (int i = 0; i < numVars; i++) {
            if (nassign[i] > 1) {
                throw new PhpError("ValueError", "Variable is assigned by multiple \"%n$\" conversion specifiers");
            } else if (xpgSize == 0 && nassign[i] == 0) {
                throw new PhpError("ValueError", "Variable is not assigned by any conversion specifiers");
            }
        }
        return numVars;
    }

    private static PhpError badIndex(boolean gotXpg) {
        return new PhpError("ValueError", gotXpg ? "\"%n$\" argument index out of range"
                : "Different numbers of variable names and field specifiers");
    }

    private static PhpError unmatchedSet() {
        return new PhpError("ValueError", "Unmatched [ in format string");
    }

    /** BuildCharSet's set: single characters and ranges (C's signed chars), maybe excluded. */
    private static final class CharSet {
        boolean exclude;
        final ByteArrayOutputStream chars = new ByteArrayOutputStream();
        final ByteArrayOutputStream ranges = new ByteArrayOutputStream();
        /** Where the format continues, after the closing ']'. */
        int next;

        /** BuildCharSet on the format after the '['. */
        CharSet(byte[] f, int format) {
            if (at(f, format) == '^') {
                exclude = true;
                format++;
            }
            int ch = format++;
            byte start = (byte) at(f, ch);
            if (at(f, ch) == ']' || at(f, ch) == '-') {
                chars.write(at(f, ch));
                ch = format++;
            }
            while (at(f, ch) != ']') {
                if (at(f, format) == '-') {
                    // maybe the first character of a range
                    start = (byte) at(f, ch);
                } else if (at(f, ch) == '-') {
                    if (at(f, format) == ']') {
                        // the dash is the set's last character, not a range
                        chars.write(start);
                        chars.write('-');
                    } else {
                        ch = format++;
                        byte last = (byte) at(f, ch);
                        ranges.write(start < last ? start : last);
                        ranges.write(start < last ? last : start);
                    }
                } else {
                    chars.write(at(f, ch));
                }
                ch = format++;
            }
            next = format;
        }

        boolean contains(int c) {
            byte ch = (byte) c;
            boolean match = false;
            for (byte b : chars.toByteArray()) {
                match |= b == ch;
            }
            byte[] r = ranges.toByteArray();
            for (int i = 0; i < r.length && !match; i += 2) {
                match = r[i] <= ch && ch <= r[i + 1];
            }
            return exclude != match;
        }
    }

    /**
     * php_sscanf_internal: scans the string; the values go to the by-reference arguments from varStart (the
     * count of conversions is returned, -1 if the string ended before the first), or, without them, into the
     * returned array (null if the string ended before the first).
     */
    @TruffleBoundary
    static Object scan(byte[] string, byte[] format, Object[] a, int varStart) {
        int argCount = Args.count(a);
        int numVars = Math.max(0, argCount - varStart);
        int totalVars = validate(format, numVars);
        int objIndex = numVars != 0 ? varStart : 0;
        PhpArray result = null;
        if (numVars == 0) {
            result = new PhpArray();
            for (int i = 0; i < totalVars; i++) {
                result.append(PhpNull.NULL);
            }
            varStart = 0;
        }
        int nconversions = 0;
        boolean underflow = false;
        int base = 0;
        char op = 0;
        int[] numberEnd = new int[1];
        int s = 0;
        int p = 0;
        scan:
        while (at(format, p) != 0) {
            int ch = at(format, p++);
            int flags = 0;
            if (isSpace(ch)) {
                while (isSpace(at(string, s))) {
                    s++;
                }
                continue;
            }
            boolean literal = ch != '%';
            if (!literal) {
                ch = at(format, p++);
                literal = ch == '%';
            }
            if (literal) {
                if (at(string, s) == 0) {
                    underflow = true;
                    break;
                }
                if (ch != at(string, s++)) {
                    break;
                }
                continue;
            }
            if (ch == '*') {
                flags |= SCAN_SUPPRESS;
                ch = at(format, p++);
            } else if (isDigit(ch)) {
                long value = strtoul(format, p - 1, numberEnd);
                if (at(format, numberEnd[0]) == '$') {
                    p = numberEnd[0] + 1;
                    ch = at(format, p++);
                    objIndex = varStart + (int) value - 1;
                }
            }
            long width = 0;
            if (isDigit(ch)) {
                width = strtoul(format, p - 1, numberEnd);
                p = numberEnd[0];
                ch = at(format, p++);
            }
            if (ch == 'l' || ch == 'L' || ch == 'h') {
                ch = at(format, p++);
            }
            switch (ch) {
                case 'n' -> {
                    if ((flags & SCAN_SUPPRESS) == 0) {
                        objIndex = assign(a, result, numVars, objIndex, (long) s);
                    }
                    nconversions++;
                    continue;
                }
                case 'd', 'D' -> {
                    op = 'i';
                    base = 10;
                }
                case 'i' -> {
                    op = 'i';
                    base = 0;
                }
                case 'o' -> {
                    op = 'i';
                    base = 8;
                }
                case 'x', 'X' -> {
                    op = 'i';
                    base = 16;
                }
                case 'u' -> {
                    op = 'i';
                    base = 10;
                    flags |= SCAN_UNSIGNED;
                }
                case 'f', 'e', 'E', 'g' -> op = 'f';
                case 's' -> op = 's';
                case 'c' -> {
                    op = 's';
                    flags |= SCAN_NOSKIP;
                    if (width == 0) {
                        width = 1;
                    }
                }
                case '[' -> {
                    op = '[';
                    flags |= SCAN_NOSKIP;
                }
                default -> {
                }
            }
            if (at(string, s) == 0) {
                underflow = true;
                break;
            }
            if ((flags & SCAN_NOSKIP) == 0) {
                while (isSpace(at(string, s))) {
                    s++;
                }
                if (at(string, s) == 0) {
                    underflow = true;
                    break;
                }
            }
            switch (op) {
                case 's' -> {
                    if (width == 0) {
                        width = Long.MAX_VALUE;
                    }
                    int end = s;
                    while (at(string, end) != 0 && !isSpace(at(string, end))) {
                        end++;
                        if (--width == 0) {
                            break;
                        }
                    }
                    if ((flags & SCAN_SUPPRESS) == 0) {
                        objIndex = assign(a, result, numVars, objIndex, Str.of(Arrays.copyOfRange(string, s, end)));
                    }
                    s = end;
                }
                case '[' -> {
                    if (width == 0) {
                        width = Long.MAX_VALUE;
                    }
                    int end = s;
                    CharSet set = new CharSet(format, p);
                    p = set.next;
                    while (at(string, end) != 0 && set.contains(at(string, end))) {
                        end++;
                        if (--width == 0) {
                            break;
                        }
                    }
                    if (s == end) {
                        // nothing matched the set: the scan stops
                        break scan;
                    }
                    if ((flags & SCAN_SUPPRESS) == 0) {
                        objIndex = assign(a, result, numVars, objIndex, Str.of(Arrays.copyOfRange(string, s, end)));
                    }
                    s = end;
                }
                case 'i' -> {
                    if (width == 0 || width > 63) {
                        width = 63;
                    }
                    flags |= SCAN_SIGNOK | SCAN_NODIGITS | SCAN_NOZERO;
                    StringBuilder buf = new StringBuilder();
                    for (; width > 0; width--) {
                        int c = at(string, s);
                        boolean add = false;
                        if (c == '0') {
                            if (base == 16) {
                                flags |= SCAN_XOK;
                            }
                            if (base == 0) {
                                base = 8;
                                flags |= SCAN_XOK;
                            }
                            flags &= (flags & SCAN_NOZERO) != 0 ? ~(SCAN_SIGNOK | SCAN_NODIGITS | SCAN_NOZERO)
                                    : ~(SCAN_SIGNOK | SCAN_XOK | SCAN_NODIGITS);
                            add = true;
                        } else if (c >= '1' && c <= '9') {
                            if (base == 0) {
                                base = 10;
                            }
                            add = c <= '7' || base > 8;
                        } else if (c >= 'A' && c <= 'F' || c >= 'a' && c <= 'f') {
                            add = base > 10;
                        } else if (c == '+' || c == '-') {
                            if ((flags & SCAN_SIGNOK) != 0) {
                                flags &= ~SCAN_SIGNOK;
                                add = true;
                            }
                        } else if (c == 'x' || c == 'X') {
                            if ((flags & SCAN_XOK) != 0 && buf.length() == 1) {
                                base = 16;
                                flags &= ~SCAN_XOK;
                                add = true;
                            }
                        }
                        if (!add) {
                            break;
                        }
                        if (c != '0' && c != '+' && c != '-' && c != 'x' && c != 'X') {
                            flags &= ~(SCAN_SIGNOK | SCAN_XOK | SCAN_NODIGITS);
                        }
                        buf.append((char) c);
                        s++;
                        if (at(string, s) == 0) {
                            break;
                        }
                    }
                    if ((flags & SCAN_NODIGITS) != 0) {
                        if (at(string, s) == 0) {
                            underflow = true;
                        }
                        break scan;
                    }
                    char last = buf.charAt(buf.length() - 1);
                    if (last == 'x' || last == 'X') {
                        buf.setLength(buf.length() - 1);
                        s--;
                    }
                    if ((flags & SCAN_SUPPRESS) == 0) {
                        long value = strtol(buf.toString(), base, (flags & SCAN_UNSIGNED) != 0);
                        objIndex = assign(a, result, numVars, objIndex, (flags & SCAN_UNSIGNED) != 0 && value < 0
                                ? Str.of(Long.toUnsignedString(value)) : (Object) value);
                    }
                }
                case 'f' -> {
                    if (width == 0 || width > 63) {
                        width = 63;
                    }
                    flags |= SCAN_SIGNOK | SCAN_NODIGITS | SCAN_PTOK | SCAN_EXPOK;
                    StringBuilder buf = new StringBuilder();
                    for (; width > 0; width--) {
                        int c = at(string, s);
                        if (isDigit(c)) {
                            flags &= ~(SCAN_SIGNOK | SCAN_NODIGITS);
                        } else if ((c == '+' || c == '-') && (flags & SCAN_SIGNOK) != 0) {
                            flags &= ~SCAN_SIGNOK;
                        } else if (c == '.' && (flags & SCAN_PTOK) != 0) {
                            flags &= ~(SCAN_SIGNOK | SCAN_PTOK);
                        } else if ((c == 'e' || c == 'E') && (flags & (SCAN_NODIGITS | SCAN_EXPOK)) == SCAN_EXPOK) {
                            // an exponent, after at least one digit
                            flags = (flags & ~(SCAN_EXPOK | SCAN_PTOK)) | SCAN_SIGNOK | SCAN_NODIGITS;
                        } else {
                            break;
                        }
                        buf.append((char) c);
                        s++;
                        if (at(string, s) == 0) {
                            break;
                        }
                    }
                    if ((flags & SCAN_NODIGITS) != 0) {
                        if ((flags & SCAN_EXPOK) != 0) {
                            // no digits at all
                            if (at(string, s) == 0) {
                                underflow = true;
                            }
                            break scan;
                        }
                        // a bad exponent: back up over the 'e' and its sign
                        char last = buf.charAt(buf.length() - 1);
                        buf.setLength(buf.length() - 1);
                        s--;
                        if (last != 'e' && last != 'E') {
                            buf.setLength(buf.length() - 1);
                            s--;
                        }
                    }
                    if ((flags & SCAN_SUPPRESS) == 0) {
                        objIndex = assign(a, result, numVars, objIndex, Double.parseDouble(buf.toString()));
                    }
                }
                default -> {
                }
            }
            nconversions++;
        }
        if (underflow && nconversions == 0) {
            return numVars != 0 ? (Object) (long) -1 : PhpNull.NULL;
        }
        return numVars != 0 ? (Object) (long) nconversions : result;
    }

    /** A converted value to the next variable, or into the result array at objIndex; the index after it. */
    private static int assign(Object[] a, PhpArray result, int numVars, int objIndex, Object value) {
        if (numVars != 0) {
            Args.ref(a, objIndex).set(value);
        } else {
            result.put((long) objIndex, value);
        }
        return objIndex + 1;
    }

    /** strtol / strtoul on the scanned digits: saturated at the type's bounds, strtoul's negation wrapping. */
    private static long strtol(String digits, int base, boolean unsigned) {
        boolean negative = digits.startsWith("-");
        String number = digits.startsWith("-") || digits.startsWith("+") ? digits.substring(1) : digits;
        if (base == 16 && (number.startsWith("0x") || number.startsWith("0X"))) {
            number = number.substring(2);
        }
        BigInteger value = new BigInteger(number, base);
        if (unsigned) {
            if (value.compareTo(ULONG_MAX) > 0) {
                return -1L;
            }
            return negative ? -value.longValue() : value.longValue();
        }
        value = negative ? value.negate() : value;
        return value.max(BigInteger.valueOf(Long.MIN_VALUE)).min(BigInteger.valueOf(Long.MAX_VALUE)).longValue();
    }
}
