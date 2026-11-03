package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

/** pack() and unpack() as ext/standard/pack.c on a little-endian machine with a 4-byte int. */
final class Pack {
    private Pack() {
    }

    private static void warn(PhpContext context, String function, String message) {
        ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of(function + "(): " + message),
                SourceLocation.current());
    }

    /** atoi on the digits at i (a C int, wrapping as the library's does on x86-64). */
    private static int atoi(byte[] format, int i, int length) {
        long value = 0;
        while (i < length && format[i] >= '0' && format[i] <= '9') {
            value = value * 10 + format[i++] - '0';
            if (value > Integer.MAX_VALUE) {
                return (int) value;
            }
        }
        return (int) value;
    }

    @TruffleBoundary
    static TruffleString pack(PhpContext context, Object[] a) {
        Args.arity(a, "pack", 1, Integer.MAX_VALUE);
        TruffleString formatString = Args.string(context, a, 0, "pack", "format", false);
        byte[] format = Str.readBytes(formatString);
        int formatLength = Str.length(formatString);
        int numArgs = Args.count(a) - 1;
        char[] codes = new char[formatLength];
        int[] counts = new int[formatLength];
        int formatCount = 0;
        int currentArg = 0;
        for (int i = 0; i < formatLength; formatCount++) {
            char code = (char) (format[i++] & 0xFF);
            int arg = 1;
            if (i < formatLength) {
                if (format[i] == '*') {
                    arg = -1;
                    i++;
                } else if (format[i] >= '0' && format[i] <= '9') {
                    arg = atoi(format, i, formatLength);
                    while (i < formatLength && format[i] >= '0' && format[i] <= '9') {
                        i++;
                    }
                }
            }
            switch (code) {
                case 'x', 'X', '@' -> {
                    if (arg < 0) {
                        warn(context, "pack", "Type " + code + ": '*' ignored");
                        arg = 1;
                    }
                }
                case 'a', 'A', 'Z', 'h', 'H' -> {
                    if (currentArg >= numArgs) {
                        throw new PhpError("ValueError", "Type " + code + ": not enough arguments");
                    }
                    if (arg < 0) {
                        arg = Str.length(Convert.toStr(Args.get(a, 1 + currentArg))) + (code == 'Z' ? 1 : 0);
                    }
                    currentArg++;
                }
                case 'q', 'Q', 'J', 'P', 'c', 'C', 's', 'S', 'i', 'I', 'l', 'L', 'n', 'N', 'v', 'V', 'f', 'g', 'G',
                     'd', 'e', 'E' -> {
                    if (arg < 0) {
                        arg = numArgs - currentArg;
                    }
                    if (currentArg > Integer.MAX_VALUE - arg || (currentArg += arg) > numArgs) {
                        throw new PhpError("ValueError", "Type " + code + ": too few arguments");
                    }
                }
                default -> throw new PhpError("ValueError", "Type " + code + ": unknown format code");
            }
            codes[formatCount] = code;
            counts[formatCount] = arg;
        }
        if (currentArg < numArgs) {
            warn(context, "pack", (numArgs - currentArg) + " arguments unused");
        }
        int outputPos = 0;
        int outputSize = 0;
        for (int i = 0; i < formatCount; i++) {
            char code = codes[i];
            int arg = counts[i];
            int unit = switch (code) {
                case 'h', 'H' -> {
                    arg = arg / 2 + arg % 2;
                    yield 1;
                }
                case 'a', 'A', 'Z', 'c', 'C', 'x' -> 1;
                case 's', 'S', 'n', 'v' -> 2;
                case 'i', 'I', 'l', 'L', 'N', 'V', 'f', 'g', 'G' -> 4;
                case 'q', 'Q', 'J', 'P', 'd', 'e', 'E' -> 8;
                default -> 0;
            };
            if (unit > 0) {
                if (arg < 0 || (Integer.MAX_VALUE - outputPos) / unit < arg) {
                    throw new PhpError("ValueError", "Type " + code + ": integer overflow in format string");
                }
                outputPos += arg * unit;
            } else if (code == 'X') {
                outputPos -= arg;
                if (outputPos < 0) {
                    warn(context, "pack", "Type X: outside of string");
                    outputPos = 0;
                }
            } else if (code == '@') {
                outputPos = arg;
            }
            outputSize = Math.max(outputSize, outputPos);
        }
        byte[] output = new byte[outputSize + 1];
        outputPos = 0;
        currentArg = 1;
        for (int i = 0; i < formatCount; i++) {
            char code = codes[i];
            int arg = counts[i];
            switch (code) {
                case 'a', 'A', 'Z' -> {
                    int copy = code != 'Z' ? arg : Math.max(0, arg - 1);
                    TruffleString str = Convert.toStr(Args.get(a, currentArg++));
                    java.util.Arrays.fill(output, outputPos, outputPos + arg, (byte) (code == 'A' ? ' ' : 0));
                    System.arraycopy(Str.readBytes(str), 0, output, outputPos, Math.min(Str.length(str), copy));
                    outputPos += arg;
                }
                case 'h', 'H' -> {
                    int nibbleShift = code == 'h' ? 0 : 4;
                    boolean first = true;
                    TruffleString str = Convert.toStr(Args.get(a, currentArg++));
                    byte[] v = Str.readBytes(str);
                    outputPos--;
                    if (arg > Str.length(str)) {
                        warn(context, "pack", "Type " + code + ": not enough characters in string");
                        arg = Str.length(str);
                    }
                    for (int k = 0; k < arg; k++) {
                        int n = v[k];
                        if (n >= '0' && n <= '9') {
                            n -= '0';
                        } else if (n >= 'A' && n <= 'F') {
                            n -= 'A' - 10;
                        } else if (n >= 'a' && n <= 'f') {
                            n -= 'a' - 10;
                        } else {
                            warn(context, "pack", "Type " + code + ": illegal hex digit " + (char) (n & 0xFF));
                            n = 0;
                        }
                        if (first) {
                            output[++outputPos] = 0;
                        }
                        first = !first;
                        output[outputPos] |= (byte) (n << nibbleShift);
                        nibbleShift = (nibbleShift + 4) & 7;
                    }
                    outputPos++;
                }
                case 'c', 'C', 's', 'S', 'n', 'v', 'i', 'I', 'l', 'L', 'N', 'V', 'q', 'Q', 'J', 'P' -> {
                    int size = switch (code) {
                        case 'c', 'C' -> 1;
                        case 's', 'S', 'n', 'v' -> 2;
                        case 'q', 'Q', 'J', 'P' -> 8;
                        default -> 4;
                    };
                    boolean big = code == 'n' || code == 'N' || code == 'J';
                    for (int k = 0; k < arg; k++) {
                        long value = Convert.toLong(Args.get(a, currentArg++));
                        for (int b = 0; b < size; b++) {
                            output[outputPos + (big ? size - 1 - b : b)] = (byte) (value >>> (8 * b));
                        }
                        outputPos += size;
                    }
                }
                case 'f', 'g', 'G', 'd', 'e', 'E' -> {
                    boolean single = code == 'f' || code == 'g' || code == 'G';
                    boolean big = code == 'G' || code == 'E';
                    int size = single ? 4 : 8;
                    for (int k = 0; k < arg; k++) {
                        double d = Convert.toDouble(Args.get(a, currentArg++));
                        long bits = single ? Float.floatToRawIntBits((float) d) & 0xFFFFFFFFL : Double.doubleToRawLongBits(d);
                        for (int b = 0; b < size; b++) {
                            output[outputPos + (big ? size - 1 - b : b)] = (byte) (bits >>> (8 * b));
                        }
                        outputPos += size;
                    }
                }
                case 'x' -> {
                    java.util.Arrays.fill(output, outputPos, outputPos + arg, (byte) 0);
                    outputPos += arg;
                }
                case 'X' -> outputPos = Math.max(0, outputPos - arg);
                case '@' -> {
                    if (arg > outputPos) {
                        java.util.Arrays.fill(output, outputPos, arg, (byte) 0);
                    }
                    outputPos = arg;
                }
                default -> {
                }
            }
        }
        return Str.of(output, 0, outputPos);
    }

    @TruffleBoundary
    static Object unpack(PhpContext context, Object[] a) {
        Args.arity(a, "unpack", 2, 3);
        TruffleString formatString = Args.string(context, a, 0, "unpack", "format", false);
        TruffleString data = Args.string(context, a, 1, "unpack", "string", false);
        long offset = Args.has(a, 2) ? Args.integer(context, a, 2, "unpack", "offset", "int") : 0;
        byte[] format = Str.readBytes(formatString);
        int formatLength = Str.length(formatString);
        byte[] bytes = Str.readBytes(data);
        long inputLength = Str.length(data);
        if (offset < 0 || offset > inputLength) {
            throw new PhpError("ValueError", "unpack(): Argument #3 ($offset) must be contained in argument #2 ($data)");
        }
        int base = (int) offset;
        inputLength -= offset;
        long inputPos = 0;
        PhpArray result = new PhpArray();
        int f = 0;
        while (f < formatLength) {
            char type = (char) (format[f++] & 0xFF);
            int repetitions = 1;
            if (f < formatLength) {
                if (format[f] >= '0' && format[f] <= '9') {
                    long tmp = 0;
                    int d = f;
                    while (d < formatLength && format[d] >= '0' && format[d] <= '9' && tmp <= Integer.MAX_VALUE) {
                        tmp = tmp * 10 + format[d++] - '0';
                    }
                    if (tmp > Integer.MAX_VALUE) {
                        warn(context, "unpack", "Type " + type + ": integer overflow");
                        return Boolean.FALSE;
                    }
                    repetitions = (int) tmp;
                    while (f < formatLength && format[f] >= '0' && format[f] <= '9') {
                        f++;
                    }
                } else if (format[f] == '*') {
                    repetitions = -1;
                    f++;
                }
            }
            int name = f;
            int argb = repetitions;
            while (f < formatLength && format[f] != '/') {
                f++;
            }
            int nameLength = Math.min(f - name, 200);
            int size;
            switch (type) {
                case 'X' -> {
                    size = -1;
                    if (repetitions < 0) {
                        warn(context, "unpack", "Type X: '*' ignored");
                        repetitions = 1;
                    }
                }
                case '@' -> size = 0;
                case 'a', 'A', 'Z' -> {
                    size = repetitions;
                    repetitions = 1;
                }
                case 'h', 'H' -> {
                    size = repetitions > 0 ? (int) (((long) repetitions + 1) / 2) : repetitions;
                    repetitions = 1;
                }
                case 'c', 'C', 'x' -> size = 1;
                case 's', 'S', 'n', 'v' -> size = 2;
                case 'i', 'I', 'l', 'L', 'N', 'V', 'f', 'g', 'G' -> size = 4;
                case 'q', 'Q', 'J', 'P', 'd', 'e', 'E' -> size = 8;
                default -> throw new PhpError("ValueError", "Invalid format type " + type);
            }
            for (int i = 0; i != repetitions; i++) {
                if (size != 0 && size != -1 && Integer.MAX_VALUE - size + 1 < inputPos) {
                    warn(context, "unpack", "Type " + type + ": integer overflow");
                    return Boolean.FALSE;
                }
                if (inputPos + size > inputLength) {
                    if (repetitions < 0) {
                        break;
                    }
                    long left = inputLength - inputPos;
                    warn(context, "unpack", "Type " + type + ": not enough input values, need " + size
                            + " values but only " + left + " " + (left == 1 ? "was" : "were") + " provided");
                    return Boolean.FALSE;
                }
                Object key;
                if (nameLength == 0) {
                    key = (long) (i + 1);
                } else {
                    byte[] nameBytes = java.util.Arrays.copyOfRange(format, name, name + nameLength);
                    TruffleString realName = Str.of(nameBytes);
                    key = ArrayKey.of(repetitions == 1 ? realName
                            : Str.concat(realName, Str.of(Integer.toUnsignedString(i + 1))));
                }
                int p = base + (int) inputPos;
                Object value = null;
                switch (type) {
                    case 'a', 'A', 'Z' -> {
                        long len = inputLength - inputPos;
                        if (size >= 0 && len > size) {
                            len = size;
                        }
                        size = (int) len;
                        int end = (int) len;
                        if (type == 'A') {
                            while (--end >= 0) {
                                byte b = bytes[p + end];
                                if (b != 0 && b != ' ' && b != '\t' && b != '\r' && b != '\n') {
                                    break;
                                }
                            }
                            end++;
                        } else if (type == 'Z') {
                            int s = 0;
                            while (s < len && bytes[p + s] != 0) {
                                s++;
                            }
                            end = s;
                        }
                        value = Str.of(bytes, p, end);
                    }
                    case 'h', 'H' -> {
                        long len = (inputLength - inputPos) * 2;
                        int nibbleShift = type == 'h' ? 0 : 4;
                        if (size > Integer.MAX_VALUE / 2) {
                            throw new PhpError("ValueError", "unpack(): Argument #1 ($format) repeater must be less "
                                    + "than or equal to " + Integer.MAX_VALUE / 2);
                        }
                        if (size >= 0 && len > size * 2L) {
                            len = size * 2L;
                        }
                        if (len > 0 && argb > 0) {
                            len -= argb % 2;
                        }
                        byte[] hex = new byte[(int) len];
                        int ipos = 0;
                        boolean first = true;
                        for (int opos = 0; opos < len; opos++) {
                            int cc = (bytes[p + ipos] >> nibbleShift) & 0xF;
                            hex[opos] = (byte) (cc < 10 ? cc + '0' : cc + 'a' - 10);
                            nibbleShift = (nibbleShift + 4) & 7;
                            if (!first) {
                                ipos++;
                            }
                            first = !first;
                        }
                        value = Str.of(hex);
                    }
                    case 'c', 'C', 's', 'S', 'n', 'v', 'i', 'I', 'l', 'L', 'N', 'V', 'q', 'Q', 'J', 'P' -> {
                        boolean big = type == 'n' || type == 'N' || type == 'J';
                        long v = 0;
                        for (int b = 0; b < size; b++) {
                            v |= (bytes[p + (big ? size - 1 - b : b)] & 0xFFL) << (8 * b);
                        }
                        if (type == 'c' || type == 's' || type == 'i' || type == 'l') {
                            v = v << (64 - 8 * size) >> (64 - 8 * size);
                        }
                        value = v;
                    }
                    case 'f', 'g', 'G', 'd', 'e', 'E' -> {
                        boolean big = type == 'G' || type == 'E';
                        long bits = 0;
                        for (int b = 0; b < size; b++) {
                            bits |= (bytes[p + (big ? size - 1 - b : b)] & 0xFFL) << (8 * b);
                        }
                        value = size == 4 ? (double) Float.intBitsToFloat((int) bits) : Double.longBitsToDouble(bits);
                    }
                    case 'X' -> {
                        if (inputPos < size) {
                            inputPos = -size;
                            i = repetitions - 1;
                            if (repetitions >= 0) {
                                warn(context, "unpack", "Type X: outside of string");
                            }
                        }
                    }
                    case '@' -> {
                        if (repetitions <= inputLength) {
                            inputPos = repetitions;
                        } else {
                            warn(context, "unpack", "Type @: outside of string");
                        }
                        i = repetitions - 1;
                    }
                    default -> {
                    }
                }
                if (value != null) {
                    result.put(key, value);
                }
                inputPos += size;
                if (inputPos < 0) {
                    if (size != -1) {
                        warn(context, "unpack", "Type " + type + ": outside of string");
                    }
                    inputPos = 0;
                }
            }
            if (f < formatLength) {
                f++;
            }
        }
        return result;
    }
}
