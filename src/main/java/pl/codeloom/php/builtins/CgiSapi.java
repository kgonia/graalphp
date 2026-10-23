package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.Str;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The CGI SAPI (php.Sapi=cgi), as a server runs a script per request: the request comes as CGI variables in the
 * environment and the body on stdin; the response goes to stdout as a CGI response, the header block (a Status
 * line, then the headers) before the first byte of output. The embedder (launcher.HttpMain) speaks HTTP.
 */
public final class CgiSapi {
    private CgiSapi() {
    }

    /** Fills $_GET, $_POST, $_COOKIE and $_REQUEST (request_order "GP") from the request. */
    @TruffleBoundary
    public static void installRequest(PhpContext context, Map<String, String> environment, byte[] body) {
        PhpArray get = UrlFunctions.parseQuery(Str.of(environment.getOrDefault("QUERY_STRING", "")));
        PhpArray post = new PhpArray();
        String contentType = environment.getOrDefault("CONTENT_TYPE", "").toLowerCase(Locale.ROOT);
        if (contentType.startsWith("application/x-www-form-urlencoded")) {
            post = UrlFunctions.parseQuery(Str.of(body));
        }
        PhpArray cookies = new PhpArray();
        for (String cookie : environment.getOrDefault("HTTP_COOKIE", "").split(";")) {
            int eq = cookie.indexOf('=');
            if (eq > 0) {
                String name = cookie.substring(0, eq).trim();
                Object key = ArrayKey.of(Str.of(name));
                if (!name.isEmpty() && !cookies.containsKey(key)) {
                    cookies.put(key, StringFunctions.urldecode(Str.of(cookie.substring(eq + 1).trim()), true));
                }
            }
        }
        PhpArray request = get.copy();
        for (int p = post.firstPosition(); p >= 0; p = post.nextPosition(p)) {
            request.put(post.keyAt(p), post.valueAt(p));
        }
        context.globalRef("_GET").set(get);
        context.globalRef("_POST").set(post);
        context.globalRef("_COOKIE").set(cookies);
        context.globalRef("_REQUEST").set(request);
    }

    /** The response's status and headers, as header(), setcookie() and http_response_code() leave them. */
    public static final class Response {
        private int status = 200;
        /** Header lines ("Name: value") in the order set. */
        private final List<String> headers = new ArrayList<>();
        private boolean sent;

        public boolean isSent() {
            return sent;
        }

        public int status() {
            return status;
        }

        public void setStatus(int status) {
            this.status = status;
        }

        public List<String> headers() {
            return headers;
        }

        /** header(): php-src's sapi_header_op for SAPI_HEADER_REPLACE / ADD. */
        @TruffleBoundary
        public void header(String line, boolean replace, int code) {
            String header = line.stripTrailing();
            if (header.regionMatches(true, 0, "HTTP/", 0, 5)) {
                String[] parts = header.split(" ", 3);
                if (parts.length > 1 && parts[1].matches("\\d{3}")) {
                    status = Integer.parseInt(parts[1]);
                }
                return;
            }
            int colon = header.indexOf(':');
            if (colon <= 0 || header.indexOf('\n') >= 0 || header.indexOf('\r') >= 0) {
                return;
            }
            String name = header.substring(0, colon).trim();
            String value = header.substring(colon + 1).trim();
            if (name.equalsIgnoreCase("Status")) {
                if (value.length() >= 3 && value.substring(0, 3).matches("\\d{3}")) {
                    status = Integer.parseInt(value.substring(0, 3));
                }
                return;
            }
            if (name.equalsIgnoreCase("Location") && code == 0 && status != 201 && (status < 300 || status > 399)) {
                status = 302;
            }
            if (code > 0) {
                status = code;
            }
            if (replace) {
                remove(name);
            }
            headers.add(name + ": " + value);
        }

        /** header_remove(): one header by name, or all (name null). */
        @TruffleBoundary
        public void remove(String name) {
            if (name == null) {
                headers.clear();
                return;
            }
            headers.removeIf(h -> h.regionMatches(true, 0, name + ":", 0, name.length() + 1));
        }

        /** The CGI header block, once: Status, the headers, a default Content-type. */
        @TruffleBoundary
        public byte[] block() {
            sent = true;
            StringBuilder block = new StringBuilder("Status: ").append(status).append("\r\n");
            boolean contentType = false;
            for (String header : headers) {
                block.append(header).append("\r\n");
                contentType |= header.regionMatches(true, 0, "Content-Type:", 0, 13);
            }
            if (!contentType) {
                block.append("Content-type: text/html; charset=UTF-8\r\n");
            }
            return block.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1);
        }
    }

    private static final DateTimeFormatter COOKIE_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);

    /** The characters a cookie's parts can't contain (php-src's ILLEGAL_COOKIE_CHARACTER), and their listing. */
    private static final String ILLEGAL = ",; \t\r\n\u000b\u000c";
    private static final String ILLEGAL_LISTED = "\",\", \";\", \" \", \"\\t\", \"\\r\", \"\\n\", \"\\013\", or \"\\014\"";
    /** 10000-01-01T00:00:00Z: expires must stay within four-digit years. */
    private static final long YEAR_10000 = 253402300800L;

    /** A setcookie()/setrawcookie() call's parts, as bytes (a null part was not given). */
    static final class Cookie {
        final byte[] name;
        final byte[] value;
        long expires;
        byte[] path;
        byte[] domain;
        boolean secure;
        boolean httpOnly;
        byte[] sameSite;
        boolean partitioned;

        Cookie(byte[] name, byte[] value) {
            this.name = name;
            this.value = value;
        }

        /**
         * php-src's php_setcookie: the Set-Cookie header line, after its ValueErrors for an empty name, illegal
         * characters in the name, a raw value, the path or the domain, an expiry after year 9999 and Partitioned
         * without Secure. An empty value deletes the cookie; setcookie() rawurlencodes the value.
         */
        @TruffleBoundary
        String header(String function, boolean urlEncode) {
            if (name.length == 0) {
                throw new PhpError("ValueError: " + function + "(): Argument #1 ($name) must not be empty");
            }
            if (contains(name, "=" + ILLEGAL)) {
                throw new PhpError("ValueError: " + function + "(): Argument #1 ($name) cannot contain \"=\", "
                        + ILLEGAL_LISTED);
            }
            if (!urlEncode && value != null && contains(value, ILLEGAL)) {
                throw new PhpError("ValueError: " + function + "(): Argument #2 ($value) cannot contain "
                        + ILLEGAL_LISTED);
            }
            if (path != null && contains(path, ILLEGAL)) {
                throw new PhpError("ValueError: " + function + "(): \"path\" option cannot contain " + ILLEGAL_LISTED);
            }
            if (domain != null && contains(domain, ILLEGAL)) {
                throw new PhpError("ValueError: " + function + "(): \"domain\" option cannot contain "
                        + ILLEGAL_LISTED);
            }
            if (expires >= YEAR_10000) {
                throw new PhpError("ValueError: " + function + "(): \"expires\" option cannot have a year greater "
                        + "than 9999");
            }
            if (partitioned && !secure) {
                throw new PhpError("ValueError: " + function + "(): \"partitioned\" option cannot be used without "
                        + "\"secure\" option");
            }
            StringBuilder cookie = new StringBuilder("Set-Cookie: ").append(latin1(name)).append('=');
            if (value == null || value.length == 0) {
                cookie.append("deleted; expires=").append(COOKIE_DATE.format(Instant.ofEpochSecond(1)))
                        .append("; Max-Age=0");
            } else {
                cookie.append(urlEncode ? Str.java(StringFunctions.urlencode(Str.of(value), true)) : latin1(value));
                if (expires > 0) {
                    cookie.append("; expires=").append(COOKIE_DATE.format(Instant.ofEpochSecond(expires)))
                            .append("; Max-Age=").append(Math.max(0, expires - Instant.now().getEpochSecond()));
                }
            }
            if (path != null && path.length > 0) {
                cookie.append("; path=").append(latin1(path));
            }
            if (domain != null && domain.length > 0) {
                cookie.append("; domain=").append(latin1(domain));
            }
            if (secure) {
                cookie.append("; secure");
            }
            if (httpOnly) {
                cookie.append("; HttpOnly");
            }
            if (sameSite != null && sameSite.length > 0) {
                cookie.append("; SameSite=").append(latin1(sameSite));
            }
            if (partitioned) {
                cookie.append("; Partitioned");
            }
            return cookie.toString();
        }

        /** strpbrk: whether any of the characters occurs before the first NUL. */
        private static boolean contains(byte[] bytes, String characters) {
            for (byte b : bytes) {
                if (b == 0) {
                    return false;
                }
                if (characters.indexOf(b & 0xff) >= 0) {
                    return true;
                }
            }
            return false;
        }

        /** The header keeps the bytes: Response.block() writes the line as ISO-8859-1. */
        private static String latin1(byte[] bytes) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }
}
