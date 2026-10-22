package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.Str;

import java.nio.charset.StandardCharsets;

/** strip_tags(): a port of php-src's php_strip_tags_ex state machine (ext/standard/string.c). */
final class StripTags {
    private StripTags() {
    }

    @TruffleBoundary
    static TruffleString strip(TruffleString text, Object allowed) {
        byte[] buf = Str.readBytes(text);
        String allow = allowedTags(allowed);
        ByteBuilder out = new ByteBuilder(buf.length);
        ByteBuilder tag = allow == null ? null : new ByteBuilder();
        int depth = 0;
        int inQuote = 0;
        int state = 0;
        int br = 0;
        byte lc = 0;
        boolean isXml = false;
        int p = 0;
        int end = buf.length;
        while (p < end) {
            byte c = buf[p];
            switch (state) {
                case 0:
                    if (c == 0) {
                        break;
                    } else if (c == '<') {
                        if (inQuote != 0) {
                            break;
                        }
                        if (p + 1 < end && isSpace(buf[p + 1])) {
                            out.write(c);
                            break;
                        }
                        lc = '<';
                        state = 1;
                        if (tag != null) {
                            tag.write('<');
                        }
                    } else if (c == '>') {
                        if (depth != 0) {
                            depth--;
                            break;
                        }
                        if (inQuote != 0) {
                            break;
                        }
                        out.write(c);
                    } else {
                        out.write(c);
                    }
                    break;
                case 1:
                    if (c == 0) {
                        break;
                    } else if (c == '<') {
                        if (inQuote != 0) {
                            break;
                        }
                        if (p + 1 < end && isSpace(buf[p + 1])) {
                            if (tag != null) {
                                tag.write(c);
                            }
                            break;
                        }
                        depth++;
                    } else if (c == '>') {
                        if (depth != 0) {
                            depth--;
                            break;
                        }
                        if (inQuote != 0) {
                            break;
                        }
                        lc = '>';
                        if (isXml && p >= 1 && buf[p - 1] == '-') {
                            break;
                        }
                        inQuote = 0;
                        state = 0;
                        isXml = false;
                        if (tag != null) {
                            tag.write('>');
                            byte[] t = tag.toByteArray();
                            if (tagAllowed(t, allow)) {
                                out.write(t, 0, t.length);
                            }
                            tag.reset();
                        }
                    } else if (c == '"' || c == '\'') {
                        if (p != 0 && (inQuote == 0 || c == inQuote)) {
                            inQuote = inQuote != 0 ? 0 : c;
                        }
                        if (tag != null) {
                            tag.write(c);
                        }
                    } else if (c == '!' && p >= 1 && buf[p - 1] == '<') {
                        state = 3;
                        lc = c;
                    } else if (c == '?' && p >= 1 && buf[p - 1] == '<') {
                        br = 0;
                        state = 2;
                    } else if (tag != null) {
                        tag.write(c);
                    }
                    break;
                case 2:
                    if (c == '(') {
                        if (lc != '"' && lc != '\'') {
                            lc = '(';
                            br++;
                        }
                    } else if (c == ')') {
                        if (lc != '"' && lc != '\'') {
                            lc = ')';
                            br--;
                        }
                    } else if (c == '>') {
                        if (depth != 0) {
                            depth--;
                            break;
                        }
                        if (inQuote != 0) {
                            break;
                        }
                        if (br == 0 && p >= 1 && lc != '"' && buf[p - 1] == '?') {
                            inQuote = 0;
                            state = 0;
                            if (tag != null) {
                                tag.reset();
                            }
                        }
                    } else if (c == '"' || c == '\'') {
                        if (p >= 1 && buf[p - 1] != '\\') {
                            if (lc == c) {
                                lc = 0;
                            } else if (lc != '\\') {
                                lc = c;
                            }
                            if (p != 0 && (inQuote == 0 || c == inQuote)) {
                                inQuote = inQuote != 0 ? 0 : c;
                            }
                        }
                    } else if ((c == 'l' || c == 'L') && p > 4 && (buf[p - 1] == 'm' || buf[p - 1] == 'M')
                            && (buf[p - 2] == 'x' || buf[p - 2] == 'X') && buf[p - 3] == '?' && buf[p - 4] == '<') {
                        state = 1;
                        isXml = true;
                    }
                    break;
                case 3:
                    if (c == '>') {
                        if (depth != 0) {
                            depth--;
                            break;
                        }
                        if (inQuote != 0) {
                            break;
                        }
                        inQuote = 0;
                        state = 0;
                        if (tag != null) {
                            tag.reset();
                        }
                    } else if (c == '"' || c == '\'') {
                        if (p != 0 && buf[p - 1] != '\\' && (inQuote == 0 || c == inQuote)) {
                            inQuote = inQuote != 0 ? 0 : c;
                        }
                    } else if (c == '-' && p >= 2 && buf[p - 1] == '-' && buf[p - 2] == '!') {
                        state = 4;
                    } else if ((c == 'E' || c == 'e') && p > 6 && doctype(buf, p)) {
                        state = 1;
                    }
                    break;
                default:
                    if (c == '>' && inQuote == 0 && p >= 2 && buf[p - 1] == '-' && buf[p - 2] == '-') {
                        inQuote = 0;
                        state = 0;
                        if (tag != null) {
                            tag.reset();
                        }
                    }
                    break;
            }
            p++;
        }
        return Str.of(out.toByteArray());
    }

    private static boolean doctype(byte[] b, int p) {
        String before = new String(b, p - 6, 6, StandardCharsets.ISO_8859_1).toLowerCase();
        return before.equals("doctyp");
    }

    private static boolean isSpace(byte b) {
        return b == ' ' || b >= 9 && b <= 13;
    }

    private static String allowedTags(Object allowed) {
        if (allowed == null || allowed == PhpNull.NULL) {
            return null;
        }
        if (allowed instanceof PhpArray array) {
            StringBuilder tags = new StringBuilder();
            for (int p = array.firstPosition(); p >= 0; p = array.nextPosition(p)) {
                tags.append('<').append(Str.java(Convert.toStr(array.valueAt(p))).toLowerCase()).append('>');
            }
            return tags.toString();
        }
        return Str.java(Convert.toStr(allowed)).toLowerCase();
    }

    /** php_tag_find: the tag's name, normalized to "<name>", must appear in the allow list. */
    private static boolean tagAllowed(byte[] tag, String allow) {
        StringBuilder norm = new StringBuilder("<");
        int i = 1;
        while (i < tag.length && isSpace(tag[i])) {
            i++;
        }
        if (i < tag.length && tag[i] == '/') {
            i++;
        }
        while (i < tag.length) {
            char c = (char) (tag[i] & 0xFF);
            if (c == '<' || c == '>' || isSpace(tag[i]) || c == '/') {
                break;
            }
            norm.append(Character.toLowerCase(c));
            i++;
        }
        norm.append('>');
        return norm.length() > 2 && allow.contains(norm.toString());
    }
}
