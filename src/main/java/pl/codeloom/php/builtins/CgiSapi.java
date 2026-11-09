package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.SourceLocation;
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
 * line, then the headers) before the first byte of output. The embedder speaks HTTP (launcher.HttpMain) or FastCGI
 * (launcher.FastCgiMain).
 */
public final class CgiSapi {
    private CgiSapi() {
    }

    /** Fills $_GET, $_POST, $_COOKIE and $_REQUEST (request_order "GP") from the request. */
    @TruffleBoundary
    public static void installRequest(PhpContext context, Map<String, String> environment, byte[] body) {
        PhpArray get = UrlFunctions.parseQuery(Str.of(environment.getOrDefault("QUERY_STRING", "")));
        PhpArray post = new PhpArray();
        String contentType = Str.lower(environment.getOrDefault("CONTENT_TYPE", ""));
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
        // with filter.default, the superglobals have the values through the default filter, ext/filter the raw ones
        PhpArray getVariables = FilterFunctions.registeredArray(context, get, key -> true);
        PhpArray postVariables = FilterFunctions.registeredArray(context, post, key -> true);
        PhpArray request = getVariables.copy();
        for (int p = postVariables.firstPosition(); p >= 0; p = postVariables.nextPosition(p)) {
            request.put(postVariables.keyAt(p), postVariables.valueAt(p));
        }
        context.globalRef("_GET").set(getVariables);
        context.globalRef("_POST").set(postVariables);
        context.globalRef("_COOKIE").set(FilterFunctions.registeredArray(context, cookies, key -> true));
        context.globalRef("_REQUEST").set(request);
        // ext/filter's copies: one exists once a variable went through the input filter, even a variable whose
        // name registers nothing ("=x")
        FilterFunctions.Input input = context.builtinState().filterInput;
        input.get = anyFiltered(environment.getOrDefault("QUERY_STRING", ""), false) ? FilterFunctions.Input.kept(get)
                : null;
        input.post = body.length > 0 && contentType.startsWith("application/x-www-form-urlencoded")
                ? FilterFunctions.Input.kept(post) : null;
        input.cookie = anyFiltered(environment.getOrDefault("HTTP_COOKIE", ""), true)
                ? FilterFunctions.Input.kept(cookies) : null;
    }

    /**
     * Whether php_default_treat_data passes a variable of the query string (or Cookie header) to the input filter:
     * a token between separators that isn't empty; for a cookie, one with a name after the leading whitespace.
     */
    private static boolean anyFiltered(String input, boolean cookie) {
        for (String token : input.split(cookie ? ";" : "&")) {
            int start = 0;
            while (cookie && start < token.length() && " \t\n\u000b\f\r".indexOf(token.charAt(start)) >= 0) {
                start++;
            }
            if (start < token.length() && !(cookie && token.charAt(start) == '=')) {
                return true;
            }
        }
        return false;
    }

    /**
     * The reason php's CGI SAPIs (php-cgi, php-fpm) write after a status code without a status line
     * (main/http_status_codes.h), or null: read from php-fpm 8.5.12's FastCGI output for every code 100-599.
     */
    static String reason(int code) {
        return switch (code) {
            case 100 -> "Continue";
            case 101 -> "Switching Protocols";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 203 -> "Non-Authoritative Information";
            case 204 -> "No Content";
            case 205 -> "Reset Content";
            case 206 -> "Partial Content";
            case 300 -> "Multiple Choices";
            case 301 -> "Moved Permanently";
            case 302 -> "Found";
            case 303 -> "See Other";
            case 304 -> "Not Modified";
            case 305 -> "Use Proxy";
            case 307 -> "Temporary Redirect";
            case 308 -> "Permanent Redirect";
            case 400 -> "Bad Request";
            case 401 -> "Unauthorized";
            case 402 -> "Payment Required";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 406 -> "Not Acceptable";
            case 407 -> "Proxy Authentication Required";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 410 -> "Gone";
            case 411 -> "Length Required";
            case 412 -> "Precondition Failed";
            case 413 -> "Request Entity Too Large";
            case 414 -> "Request-URI Too Long";
            case 415 -> "Unsupported Media Type";
            case 416 -> "Requested Range Not Satisfiable";
            case 417 -> "Expectation Failed";
            case 426 -> "Upgrade Required";
            case 428 -> "Precondition Required";
            case 429 -> "Too Many Requests";
            case 431 -> "Request Header Fields Too Large";
            case 451 -> "Unavailable For Legal Reasons";
            case 500 -> "Internal Server Error";
            case 501 -> "Not Implemented";
            case 502 -> "Bad Gateway";
            case 503 -> "Service Unavailable";
            case 504 -> "Gateway Timeout";
            case 505 -> "HTTP Version Not Supported";
            case 506 -> "Variant Also Negotiates";
            case 511 -> "Network Authentication Required";
            default -> null;
        };
    }

    /** The response's status and headers, as header(), setcookie() and http_response_code() leave them. */
    public static final class Response {
        private int status = 200;
        /**
         * header('HTTP/...')'s line (SG(sapi_headers).http_status_line), sent as the Status; dropped when a header()
         * changes the code (sapi_update_response_code), kept by http_response_code().
         */
        private String statusLine;
        /** Header lines ("Name: value") in the order set. */
        private final List<String> headers = new ArrayList<>();
        private boolean sent;
        /** Where the output that sent the headers started (php_output_header); null outside PHP code. */
        private SourceLocation outputStart;

        public boolean isSent() {
            return sent;
        }

        public SourceLocation outputStart() {
            return outputStart;
        }

        public int status() {
            return status;
        }

        /** http_response_code(): the code, a status line kept (it is still what is sent). */
        public void setCode(int status) {
            this.status = status;
        }

        public boolean hasStatusLine() {
            return statusLine != null;
        }

        /** sapi_update_response_code: a new code drops the status line. */
        private void updateStatus(int code) {
            if (status != code) {
                statusLine = null;
                status = code;
            }
        }

        /** sapi_extract_response_code: atoi of what follows the line's first space not followed by one, else 200. */
        private static int extractCode(String line) {
            for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) == ' ' && (i + 1 == line.length() || line.charAt(i + 1) != ' ')) {
                    int end = i + 1;
                    if (end < line.length() && (line.charAt(end) == '-' || line.charAt(end) == '+')) {
                        end++;
                    }
                    int digits = end;
                    while (end < line.length() && Character.isDigit(line.charAt(end)) && end - digits < 9) {
                        end++;
                    }
                    return end == digits ? 0 : Integer.parseInt(line.substring(i + 1, end));
                }
            }
            return 200;
        }

        public List<String> headers() {
            return headers;
        }

        /** header(): php-src's sapi_header_op for SAPI_HEADER_REPLACE / ADD. */
        @TruffleBoundary
        public void header(String line, boolean replace, int code) {
            String header = line.stripTrailing();
            if (header.regionMatches(true, 0, "HTTP/", 0, 5)) {
                updateStatus(extractCode(header));
                statusLine = header;
                return;
            }
            int colon = header.indexOf(':');
            if (colon <= 0 || header.indexOf('\n') >= 0 || header.indexOf('\r') >= 0) {
                return;
            }
            String name = header.substring(0, colon).trim();
            String value = header.substring(colon + 1).trim();
            // a Status header stays a header, the code as it was: the CGI SAPIs send it as the status
            if (name.equalsIgnoreCase("Location") && status != 201 && (status < 300 || status > 399)) {
                // a redirect unless the code is one: the code given, else a 302 (the 303 sapi_header_op gives a POST
                // over HTTP/1.1 needs SG(request_info).proto_num, which the CGI SAPIs leave at 1000: php-fpm sends 302)
                updateStatus(code > 0 ? code : 302);
            }
            if (code > 0) {
                updateStatus(code);
            }
            if (replace) {
                remove(name);
            }
            headers.add(name + ": " + value);
        }

        private boolean hasStatusHeader() {
            for (String header : headers) {
                if (header.length() > 7 && header.regionMatches(true, 0, "Status:", 0, 7)) {
                    return true;
                }
            }
            return false;
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

        /**
         * The CGI header block, once: Status, the headers, a default Content-type, which sapi_send_headers adds to
         * the header list (headers_list() shows it after). start: where the output that sends them started.
         */
        @TruffleBoundary
        public byte[] block(SourceLocation start) {
            sent = true;
            outputStart = start;
            boolean contentType = false;
            for (String header : headers) {
                contentType |= header.regionMatches(true, 0, "Content-Type:", 0, 13);
            }
            if (!contentType) {
                headers.add("Content-type: text/html; charset=UTF-8");
            }
            StringBuilder block = new StringBuilder();
            // sapi_cgi_send_headers: a Status for a code that isn't 200, from the status line, or else from the code
            // unless a Status header gives it
            if (status != 200) {
                int space = statusLine == null ? -1 : statusLine.indexOf(' ');
                if (space >= 5) {
                    block.append("Status:").append(statusLine, space, statusLine.length()).append("\r\n");
                } else if (!hasStatusHeader()) {
                    String reason = reason(status);
                    block.append("Status: ").append(status).append(reason == null ? "" : " " + reason)
                            .append("\r\n");
                }
            }
            for (String header : headers) {
                block.append(header).append("\r\n");
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
                throw new PhpError("ValueError", function + "(): Argument #1 ($name) must not be empty");
            }
            if (contains(name, "=" + ILLEGAL)) {
                throw new PhpError("ValueError", function + "(): Argument #1 ($name) cannot contain \"=\", "
                        + ILLEGAL_LISTED);
            }
            if (!urlEncode && value != null && contains(value, ILLEGAL)) {
                throw new PhpError("ValueError", function + "(): Argument #2 ($value) cannot contain "
                        + ILLEGAL_LISTED);
            }
            if (path != null && contains(path, ILLEGAL)) {
                throw new PhpError("ValueError", function + "(): \"path\" option cannot contain " + ILLEGAL_LISTED);
            }
            if (domain != null && contains(domain, ILLEGAL)) {
                throw new PhpError("ValueError", function + "(): \"domain\" option cannot contain "
                        + ILLEGAL_LISTED);
            }
            if (expires >= YEAR_10000) {
                throw new PhpError("ValueError", function + "(): \"expires\" option cannot have a year greater "
                        + "than 9999");
            }
            if (partitioned && !secure) {
                throw new PhpError("ValueError", function + "(): \"partitioned\" option cannot be used without "
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
