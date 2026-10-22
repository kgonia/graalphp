package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.Str;

/** parse_url and parse_str, after php-src's ext/standard/url.c and main/php_variables.c (simplified). */
final class UrlFunctions {
    private UrlFunctions() {
    }

    private static final String[] COMPONENTS = {"scheme", "host", "port", "user", "pass", "path", "query",
            "fragment"};

    @TruffleBoundary
    static Object parseUrl(TruffleString input, long component) {
        String url = Str.java(input);
        PhpArray parts = new PhpArray();
        String rest = url;
        int hash = rest.indexOf('#');
        String fragment = null;
        if (hash >= 0) {
            fragment = rest.substring(hash + 1);
            rest = rest.substring(0, hash);
        }
        int question = rest.indexOf('?');
        String query = null;
        if (question >= 0) {
            query = rest.substring(question + 1);
            rest = rest.substring(0, question);
        }
        String scheme = null;
        int colon = rest.indexOf(':');
        if (colon > 0 && rest.substring(0, colon).matches("[A-Za-z][A-Za-z0-9+.-]*")) {
            String after = rest.substring(colon + 1);
            boolean portOnly = !after.startsWith("//") && after.matches("\\d+(/.*)?") ;
            if (!portOnly) {
                scheme = rest.substring(0, colon);
                rest = after;
            }
        }
        String host = null;
        String user = null;
        String pass = null;
        Long port = null;
        if (rest.startsWith("//")) {
            rest = rest.substring(2);
            int slash = rest.indexOf('/');
            String authority = slash >= 0 ? rest.substring(0, slash) : rest;
            rest = slash >= 0 ? rest.substring(slash) : "";
            int at = authority.lastIndexOf('@');
            if (at >= 0) {
                String userInfo = authority.substring(0, at);
                authority = authority.substring(at + 1);
                int c = userInfo.indexOf(':');
                user = c >= 0 ? userInfo.substring(0, c) : userInfo;
                pass = c >= 0 ? userInfo.substring(c + 1) : null;
            }
            int portColon = authority.lastIndexOf(':');
            if (portColon >= 0 && !authority.endsWith("]") && authority.substring(portColon + 1).matches("\\d*")) {
                String digits = authority.substring(portColon + 1);
                if (!digits.isEmpty()) {
                    port = Long.parseLong(digits);
                    if (port > 65535) {
                        return Boolean.FALSE;
                    }
                }
                authority = authority.substring(0, portColon);
            }
            if (authority.isEmpty() && scheme != null && !scheme.equals("file")) {
                return Boolean.FALSE;
            }
            host = authority;
        }
        String path = rest.isEmpty() ? null : rest;
        Object[] values = {scheme, host, port, user, pass, path, query, fragment};
        if (component >= 0) {
            if (component >= values.length) {
                return Boolean.FALSE;
            }
            Object value = values[(int) component];
            return value == null ? PhpNull.NULL : value instanceof Long ? value : Str.of((String) value);
        }
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null) {
                parts.put(Str.of(COMPONENTS[i]), values[i] instanceof Long ? values[i] : Str.of((String) values[i]));
            }
        }
        return parts;
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
