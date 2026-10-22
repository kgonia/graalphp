package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.Str;


/** pack/unpack for the byte formats WordPress uses ('C', 'c', 'n', 'N', 'v', 'V', with counts and '*'). */
final class Pack {
    private Pack() {
    }

    @TruffleBoundary
    static TruffleString pack(Object[] a) {
        String format = Str.java(Args.str(a, 0));
        ByteBuilder out = new ByteBuilder();
        int arg = 1;
        int i = 0;
        while (i < format.length()) {
            char code = format.charAt(i++);
            int count = 1;
            if (i < format.length() && format.charAt(i) == '*') {
                count = Args.count(a) - arg;
                i++;
            } else {
                int start = i;
                while (i < format.length() && Character.isDigit(format.charAt(i))) {
                    i++;
                }
                if (i > start) {
                    count = Integer.parseInt(format.substring(start, i));
                }
            }
            for (int k = 0; k < count; k++) {
                long v = Convert.toLong(Args.get(a, arg++));
                switch (code) {
                    case 'C', 'c' -> out.write((int) v);
                    case 'n' -> {
                        out.write((int) (v >> 8));
                        out.write((int) v);
                    }
                    case 'v' -> {
                        out.write((int) v);
                        out.write((int) (v >> 8));
                    }
                    case 'N' -> {
                        for (int s = 24; s >= 0; s -= 8) {
                            out.write((int) (v >> s));
                        }
                    }
                    case 'V' -> {
                        for (int s = 0; s <= 24; s += 8) {
                            out.write((int) (v >> s));
                        }
                    }
                    default -> throw new PhpError("pack(): format code '" + code + "' is not supported");
                }
            }
        }
        return Str.of(out.toByteArray());
    }

    @TruffleBoundary
    static Object unpack(TruffleString formatString, TruffleString data, long offset) {
        String format = Str.java(formatString);
        byte[] bytes = Str.readBytes(data);
        PhpArray result = new PhpArray();
        int position = (int) offset;
        for (String element : format.split("/")) {
            if (element.isEmpty()) {
                continue;
            }
            char code = element.charAt(0);
            int i = 1;
            String countText = "";
            while (i < element.length() && (Character.isDigit(element.charAt(i)) || element.charAt(i) == '*')) {
                countText += element.charAt(i++);
            }
            String name = element.substring(i);
            int size = switch (code) {
                case 'C', 'c' -> 1;
                case 'n', 'v' -> 2;
                case 'N', 'V' -> 4;
                default -> throw new PhpError("unpack(): format code '" + code + "' is not supported");
            };
            int count = countText.equals("*") ? (bytes.length - position) / size
                    : countText.isEmpty() ? 1 : Integer.parseInt(countText);
            for (int k = 0; k < count; k++) {
                if (position + size > bytes.length) {
                    return Boolean.FALSE;
                }
                long v = 0;
                switch (code) {
                    case 'C' -> v = bytes[position] & 0xFF;
                    case 'c' -> v = bytes[position];
                    case 'n' -> v = (bytes[position] & 0xFF) << 8 | (bytes[position + 1] & 0xFF);
                    case 'v' -> v = (bytes[position + 1] & 0xFF) << 8 | (bytes[position] & 0xFF);
                    case 'N' -> v = ((long) (bytes[position] & 0xFF) << 24) | (bytes[position + 1] & 0xFF) << 16
                            | (bytes[position + 2] & 0xFF) << 8 | (bytes[position + 3] & 0xFF);
                    case 'V' -> v = ((long) (bytes[position + 3] & 0xFF) << 24) | (bytes[position + 2] & 0xFF) << 16
                            | (bytes[position + 1] & 0xFF) << 8 | (bytes[position] & 0xFF);
                    default -> {
                    }
                }
                position += size;
                String key = name + ((count > 1 || countText.equals("*")) || name.isEmpty() ? String.valueOf(k + 1) : "");
                result.put(ArrayKey.of(Str.of(key)), v);
            }
        }
        return result;
    }
}
