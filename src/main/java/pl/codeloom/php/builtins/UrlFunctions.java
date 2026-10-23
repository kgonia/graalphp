package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.Str;

import java.util.Arrays;

/** parse_url (a port of ext/standard/url.c) and parse_str (after main/php_variables.c, simplified). */
final class UrlFunctions {
    private UrlFunctions() {
    }

    private static final String[] COMPONENTS = {"scheme", "host", "port", "user", "pass", "path", "query",
            "fragment"};
    private static final int SCHEME = 0;
    private static final int HOST = 1;
    private static final int PORT = 2;
    private static final int USER = 3;
    private static final int PASS = 4;
    private static final int PATH = 5;
    private static final int QUERY = 6;
    private static final int FRAGMENT = 7;

    /** parse_url: a port of php-src's php_url_parse_ex2 and PHP_FUNCTION(parse_url). */
    @TruffleBoundary
    static Object parseUrl(TruffleString input, long component) {
        Object[] url = parse(Str.bytes(input));
        if (url == null) {
            return Boolean.FALSE;
        }
        if (component > -1) {
            if (component > FRAGMENT) {
                throw new PhpError("ValueError: parse_url(): Argument #2 ($component) must be a valid URL component "
                        + "identifier, " + component + " given");
            }
            Object value = url[(int) component];
            return value == null ? PhpNull.NULL : value;
        }
        PhpArray parts = new PhpArray();
        for (int i = 0; i < url.length; i++) {
            if (url[i] != null) {
                parts.put(Str.of(COMPONENTS[i]), url[i]);
            }
        }
        return parts;
    }

    private static boolean isAlpha(int c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    /** A component: the bytes [from, to) with control characters replaced by '_' (php_replace_controlchars). */
    private static TruffleString part(byte[] str, int from, int to) {
        byte[] bytes = Arrays.copyOfRange(str, from, to);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] >= 0 && bytes[i] < 0x20 || bytes[i] == 0x7F) {
                bytes[i] = '_';
            }
        }
        return Str.of(bytes);
    }

    private static int indexOf(byte[] str, int from, int to, int b) {
        for (int i = from; i < to; i++) {
            if (str[i] == b) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOf(byte[] str, int from, int to, int b) {
        for (int i = to - 1; i >= from; i--) {
            if (str[i] == b) {
                return i;
            }
        }
        return -1;
    }

    /** binary_strcspn: the first of chars in [s, e), or e. */
    private static int strcspn(byte[] str, int s, int e, String chars) {
        for (int i = 0; i < chars.length(); i++) {
            int p = indexOf(str, s, e, chars.charAt(i));
            if (p >= 0) {
                e = p;
            }
        }
        return e;
    }

    /** ZEND_STRTOL(text, 10) of a port: the value, or -1 when it has no digits or is out of 0..65535. */
    private static long portNumber(byte[] str, int from, int to) {
        int i = from;
        while (i < to && (str[i] == ' ' || str[i] >= '\t' && str[i] <= '\r')) {
            i++;
        }
        boolean negative = false;
        if (i < to && (str[i] == '+' || str[i] == '-')) {
            negative = str[i] == '-';
            i++;
        }
        int digits = i;
        long value = 0;
        while (i < to && isDigit(str[i])) {
            value = value * 10 + (str[i++] - '0');
        }
        if (i == digits) {
            return -1;
        }
        value = negative ? -value : value;
        return value >= 0 && value <= 65535 ? value : -1;
    }

    /**
     * php_url_parse_ex2: the components in COMPONENTS order (null where absent; the port a Long), or null when
     * the URL is seriously malformed.
     */
    private static Object[] parse(byte[] str) {
        Object[] ret = new Object[8];
        int ue = str.length;
        int s = 0;
        int e;
        int p;
        int port = 0;
        e = indexOf(str, 0, ue, ':');
        if (e > 0) {
            boolean schemeChars = true;
            for (p = s; p < e; p++) {
                int c = str[p];
                if (!isAlpha(c) && !isDigit(c) && c != '+' && c != '.' && c != '-') {
                    schemeChars = false;
                    break;
                }
            }
            if (!schemeChars) {
                if (e + 1 < ue && e < strcspn(str, s, ue, "?#")) {
                    return parsePort(str, ret, s, e, ue, port);
                } else if (s + 1 < ue && str[s] == '/' && str[s + 1] == '/') {
                    // relative-scheme URL
                    return parseHost(str, ret, s + 2, ue, port);
                }
                return justPath(str, ret, s, ue);
            }
            if (e + 1 == ue) {
                // only the scheme
                ret[SCHEME] = part(str, s, e);
                return ret;
            }
            if (str[e + 1] != '/') {
                // mailto: and the like have no '/'; a.com:80 is a host and a port
                p = e + 1;
                while (p < ue && isDigit(str[p])) {
                    p++;
                }
                if ((p == ue || str[p] == '/') && p - e < 7) {
                    return parsePort(str, ret, s, e, ue, port);
                }
                ret[SCHEME] = part(str, s, e);
                return justPath(str, ret, e + 1, ue);
            }
            ret[SCHEME] = part(str, s, e);
            if (e + 2 < ue && str[e + 2] == '/') {
                s = e + 3;
                if (Str.java((TruffleString) ret[SCHEME]).equalsIgnoreCase("file") && e + 3 < ue
                        && str[e + 3] == '/') {
                    // windows drive letters, as file:///c:/somedir/file.txt
                    if (e + 5 < ue && str[e + 5] == ':') {
                        s = e + 4;
                    }
                    return justPath(str, ret, s, ue);
                }
                return parseHost(str, ret, s, ue, port);
            }
            return justPath(str, ret, e + 1, ue);
        } else if (e == 0) {
            // no scheme; starts with a colon: look for a port
            return parsePort(str, ret, s, e, ue, port);
        } else if (s + 1 < ue && str[s] == '/' && str[s + 1] == '/') {
            return parseHost(str, ret, s + 2, ue, port);
        }
        return justPath(str, ret, s, ue);
    }

    /** The parse_port label: after a colon at e that may start a port. */
    private static Object[] parsePort(byte[] str, Object[] ret, int s, int e, int ue, int port) {
        int p = e + 1;
        int pp = p;
        while (pp < ue && pp - p < 6 && isDigit(str[pp])) {
            pp++;
        }
        if (pp - p > 0 && pp - p < 6 && (pp == ue || str[pp] == '/')) {
            long value = portNumber(str, p, pp);
            if (value < 0) {
                return null;
            }
            ret[PORT] = value;
            port = (int) value;
            if (s + 1 < ue && str[s] == '/' && str[s + 1] == '/') {
                s += 2;
            }
        } else if (p == pp && pp == ue) {
            return null;
        } else if (s + 1 < ue && str[s] == '/' && str[s + 1] == '/') {
            s += 2;
        } else {
            return justPath(str, ret, s, ue);
        }
        return parseHost(str, ret, s, ue, port);
    }

    /** The parse_host label: user info, host and port up to the first '/', '?' or '#'. */
    private static Object[] parseHost(byte[] str, Object[] ret, int s, int ue, int port) {
        int e = strcspn(str, s, ue, "/?#");
        int p = lastIndexOf(str, s, e, '@');
        if (p >= 0) {
            int pp = indexOf(str, s, p, ':');
            if (pp >= 0) {
                ret[USER] = part(str, s, pp);
                ret[PASS] = part(str, pp + 1, p);
            } else {
                ret[USER] = part(str, s, p);
            }
            s = p + 1;
        }
        if (s < ue && str[s] == '[' && str[e - 1] == ']') {
            // an IPv6 address: no port scan
            p = -1;
        } else {
            p = lastIndexOf(str, s, e, ':');
        }
        if (p >= 0) {
            if (port == 0) {
                p++;
                if (e - p > 5) {
                    return null;
                } else if (e - p > 0) {
                    long value = portNumber(str, p, e);
                    if (value < 0) {
                        return null;
                    }
                    ret[PORT] = value;
                }
                p--;
            }
        } else {
            p = e;
        }
        if (p - s < 1) {
            // no host: not a URL
            return null;
        }
        ret[HOST] = part(str, s, p);
        if (e == ue) {
            return ret;
        }
        return justPath(str, ret, e, ue);
    }

    /** The just_path label: path, query and fragment. */
    private static Object[] justPath(byte[] str, Object[] ret, int s, int ue) {
        int e = ue;
        int p = indexOf(str, s, e, '#');
        if (p >= 0) {
            p++;
            ret[FRAGMENT] = p < e ? part(str, p, e) : Str.EMPTY;
            e = p - 1;
        }
        p = indexOf(str, s, e, '?');
        if (p >= 0) {
            p++;
            ret[QUERY] = p < e ? part(str, p, e) : Str.EMPTY;
            e = p - 1;
        }
        if (s < e || s == ue) {
            ret[PATH] = part(str, s, e);
        }
        return ret;
    }

    /** parse_str for flat and bracketed keys (`a[]=1&a[b]=2`). */
    @TruffleBoundary
    static PhpArray parseQuery(TruffleString input) {
        PhpArray result = new PhpArray();
        for (String pair : Str.java(input).split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String rawKey = eq >= 0 ? pair.substring(0, eq) : pair;
            String rawValue = eq >= 0 ? pair.substring(eq + 1) : "";
            TruffleString key = StringFunctions.urldecode(Str.of(rawKey), true);
            TruffleString value = StringFunctions.urldecode(Str.of(rawValue), true);
            String k = Str.java(key);
            int bracket = k.indexOf('[');
            if (bracket <= 0 || !k.endsWith("]")) {
                result.put(ArrayKey.of(key), value);
                continue;
            }
            PhpArray target = result;
            String base = k.substring(0, bracket);
            String path = k.substring(bracket);
            Object currentKey = ArrayKey.of(Str.of(base));
            while (!path.isEmpty()) {
                int close = path.indexOf(']');
                String inner = path.substring(1, close);
                path = path.substring(close + 1);
                PhpArray nested = target.arrayForWrite(currentKey);
                target = nested;
                currentKey = inner.isEmpty() ? null : ArrayKey.of(Str.of(inner));
                if (!path.startsWith("[")) {
                    break;
                }
            }
            if (currentKey == null) {
                target.append(value);
            } else {
                target.put(currentKey, value);
            }
        }
        return result;
    }
}
