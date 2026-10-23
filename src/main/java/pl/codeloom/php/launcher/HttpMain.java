package pl.codeloom.php.launcher;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * An HTTP server running PHP scripts as php-fpm would: each request in a new context on one shared engine (the
 * compiled code is shared, the PHP state is not), on a pool of threads. The language runs under its CGI SAPI:
 * the request goes in as CGI variables and stdin, the response comes back as a CGI response.
 *
 * <p>{@code HttpMain [--host=127.0.0.1] [--port=8080] [--threads=n] [--docroot=dir] [--ast-root=dir]
 * [--source-root=dir] [--server-name=name]}: SERVER_NAME is the request's host unless set. Routing is php -S's:
 * an existing .php file runs, another existing file is served as is, a directory runs its index.php, and anything
 * else runs the document root's index.php (a front controller, as WordPress's permalinks need).
 *
 * <p>A test and development server, like php -S: it listens on the loopback interface unless told otherwise.
 * Files are judged by their real path (symbolic links resolved, the name as the filesystem spells it), so a link
 * can't lead out of the document root and `/WP-CONFIG.PHP` on a case-insensitive filesystem runs the script
 * instead of serving its source. A request body is read up to post_max_size's default, 8 MB (413 past it), and a
 * request must arrive within 30 seconds.
 */
public final class HttpMain {
    private static final Map<String, String> TYPES = Map.ofEntries(Map.entry("css", "text/css"),
            Map.entry("js", "text/javascript"), Map.entry("json", "application/json"), Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"),
            Map.entry("svg", "image/svg+xml"), Map.entry("ico", "image/x-icon"), Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"), Map.entry("txt", "text/plain"), Map.entry("html", "text/html"),
            Map.entry("xml", "application/xml"), Map.entry("webp", "image/webp"));

    private static final int MAX_BODY = 8 * 1024 * 1024;

    private final Engine engine;
    private final Path docroot;
    private final String astRoot;
    private final String sourceRoot;
    private final int port;
    private final String serverName;

    private HttpMain(Engine engine, Path docroot, String astRoot, String sourceRoot, int port, String serverName) {
        this.serverName = serverName;
        this.engine = engine;
        this.docroot = docroot;
        this.astRoot = astRoot;
        this.sourceRoot = sourceRoot;
        this.port = port;
    }

    public static void main(String[] args) throws IOException {
        String host = "127.0.0.1";
        int port = 8080;
        int threads = Runtime.getRuntime().availableProcessors();
        String docroot = ".";
        String astRoot = "target/ast";
        String sourceRoot = ".";
        String serverName = null;
        for (String arg : args) {
            String value = arg.substring(arg.indexOf('=') + 1);
            if (arg.startsWith("--host=")) {
                host = value;
            } else if (arg.startsWith("--port=")) {
                port = Integer.parseInt(value);
            } else if (arg.startsWith("--threads=")) {
                threads = Integer.parseInt(value);
            } else if (arg.startsWith("--docroot=")) {
                docroot = value;
            } else if (arg.startsWith("--ast-root=")) {
                astRoot = value;
            } else if (arg.startsWith("--source-root=")) {
                sourceRoot = value;
            } else if (arg.startsWith("--server-name=")) {
                serverName = value;
            } else {
                System.err.println("usage: HttpMain [--host=127.0.0.1] [--port=8080] [--threads=n] [--docroot=dir] "
                        + "[--ast-root=dir] [--source-root=dir] [--server-name=name]");
                System.exit(2);
            }
        }
        // the JDK server's time limit for receiving a request (seconds; read when the server class loads)
        System.setProperty("sun.net.httpserver.maxReqTime", System.getProperty("sun.net.httpserver.maxReqTime", "30"));
        Engine engine = Engine.create("php");
        HttpMain server = new HttpMain(engine, Path.of(docroot).toRealPath(), astRoot, sourceRoot, port, serverName);
        HttpServer http = HttpServer.create(new InetSocketAddress(host, port), 128);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        http.setExecutor(pool);
        http.createContext("/", exchange -> {
            try (exchange) {
                server.handle(exchange);
            } catch (RuntimeException | IOException e) {
                System.err.println("request failed: " + e);
            }
        });
        http.start();
        System.err.println("listening on " + host + ":" + port + " (" + threads + " threads), docroot "
                + server.docroot);
    }

    private void handle(HttpExchange exchange) throws IOException {
        URI uri = exchange.getRequestURI();
        String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
        Path target = docroot.resolve(path.substring(1)).normalize();
        if (Files.exists(target)) {
            target = target.toRealPath();
        }
        if (!target.startsWith(docroot)) {
            respond(exchange, 403, "text/plain", "Forbidden".getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (Files.isDirectory(target)) {
            if (!path.endsWith("/")) {
                exchange.getResponseHeaders().set("Location", path + "/" + (uri.getRawQuery() == null ? ""
                        : "?" + uri.getRawQuery()));
                respond(exchange, 301, "text/plain", new byte[0]);
                return;
            }
            target = target.resolve("index.php");
            if (Files.isRegularFile(target)) {
                runScript(exchange, target, path + "index.php");
                return;
            }
        } else if (Files.isRegularFile(target)) {
            String name = target.getFileName().toString();
            String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (extension.equals("php")) {
                runScript(exchange, target, path);
            } else {
                respond(exchange, 200, TYPES.getOrDefault(extension, "application/octet-stream"),
                        Files.readAllBytes(target));
            }
            return;
        }
        runScript(exchange, docroot.resolve("index.php"), "/index.php");
    }

    /** Runs a script as a CGI request and sends its CGI response. */
    private void runScript(HttpExchange exchange, Path script, String scriptName) throws IOException {
        byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
        if (body.length > MAX_BODY) {
            respond(exchange, 413, "text/plain", "Request body too large".getBytes(StandardCharsets.UTF_8));
            return;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String failure = null;
        try (Context context = Context.newBuilder("php")
                .engine(engine)
                .allowAllAccess(true)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .environment(cgiEnvironment(exchange, script, scriptName, body.length))
                .in(new ByteArrayInputStream(body))
                .out(out)
                .option("php.Sapi", "cgi")
                .option("php.AstRoot", astRoot)
                .option("php.SourceRoot", sourceRoot)
                .arguments("php", new String[] {script.toString()})
                .build()) {
            context.eval(Source.newBuilder("php", script.toFile()).build());
        } catch (PolyglotException e) {
            if (!e.isExit()) {
                failure = e.getMessage();
                System.err.println("PHP Fatal error:  " + failure + " (" + exchange.getRequestURI() + ")");
                if (e.isInternalError() || !e.isGuestException()) {
                    e.printStackTrace();
                }
            }
        }
        sendCgiResponse(exchange, out.toByteArray(), failure);
    }

    private Map<String, String> cgiEnvironment(HttpExchange exchange, Path script, String scriptName, int length) {
        Map<String, String> env = new HashMap<>();
        URI uri = exchange.getRequestURI();
        String host = exchange.getRequestHeaders().getFirst("Host");
        String hostName = host == null ? "localhost" : host.replaceFirst(":\\d+$", "");
        env.put("GATEWAY_INTERFACE", "CGI/1.1");
        env.put("SERVER_SOFTWARE", "Truffle PHP");
        env.put("SERVER_PROTOCOL", exchange.getProtocol());
        env.put("SERVER_NAME", serverName != null ? serverName : hostName);
        env.put("SERVER_ADDR", exchange.getLocalAddress().getAddress().getHostAddress());
        env.put("SERVER_PORT", Integer.toString(port));
        env.put("REMOTE_ADDR", exchange.getRemoteAddress().getAddress().getHostAddress());
        env.put("REMOTE_PORT", Integer.toString(exchange.getRemoteAddress().getPort()));
        env.put("REQUEST_METHOD", exchange.getRequestMethod());
        env.put("REQUEST_URI", uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
        env.put("QUERY_STRING", uri.getRawQuery() == null ? "" : uri.getRawQuery());
        env.put("DOCUMENT_ROOT", docroot.toString());
        env.put("SCRIPT_FILENAME", script.toString());
        env.put("SCRIPT_NAME", scriptName);
        env.put("PHP_SELF", scriptName);
        if (length > 0 || exchange.getRequestHeaders().containsKey("Content-Length")) {
            env.put("CONTENT_LENGTH", Integer.toString(length));
        }
        for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
            String name = header.getKey().toUpperCase(Locale.ROOT).replace('-', '_');
            String value = String.join(", ", header.getValue());
            if (name.equals("CONTENT_TYPE")) {
                env.put("CONTENT_TYPE", value);
            } else if (!name.equals("CONTENT_LENGTH") && !name.equals("PROXY")) {
                // a Proxy header would become HTTP_PROXY, which HTTP clients take as their proxy (httpoxy)
                env.put("HTTP_" + name, value);
            }
        }
        return env;
    }

    /** A CGI response: a header block (Status and headers) up to an empty line, then the body. */
    private static void sendCgiResponse(HttpExchange exchange, byte[] output, String failure) throws IOException {
        int status = 200;
        int bodyStart = 0;
        Headers headers = exchange.getResponseHeaders();
        int end = headerEnd(output);
        if (end >= 0) {
            String block = new String(output, 0, end, StandardCharsets.ISO_8859_1);
            for (String line : block.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (name.equalsIgnoreCase("Status")) {
                    status = Integer.parseInt(value.split(" ")[0]);
                } else {
                    headers.add(name, value);
                }
            }
            bodyStart = end + 4;
        }
        byte[] body = java.util.Arrays.copyOfRange(output, bodyStart, output.length);
        if (failure != null) {
            if (end < 0) {
                status = 500;
                headers.set("Content-Type", "text/html; charset=UTF-8");
            }
            byte[] message = ("<br />\n<b>Fatal error</b>:  " + failure.replace("&", "&amp;").replace("<", "&lt;")
                    + "<br />\n").getBytes(StandardCharsets.UTF_8);
            byte[] joined = java.util.Arrays.copyOf(body, body.length + message.length);
            System.arraycopy(message, 0, joined, body.length, message.length);
            body = joined;
        }
        boolean noBody = exchange.getRequestMethod().equals("HEAD") || status == 204 || status == 304;
        exchange.sendResponseHeaders(status, noBody ? -1 : body.length == 0 ? -1 : body.length);
        if (!noBody && body.length > 0) {
            try (OutputStream stream = exchange.getResponseBody()) {
                stream.write(body);
            }
        }
    }

    private static int headerEnd(byte[] output) {
        for (int i = 0; i + 3 < output.length; i++) {
            if (output[i] == '\r' && output[i + 1] == '\n' && output[i + 2] == '\r' && output[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static void respond(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream stream = exchange.getResponseBody()) {
                stream.write(body);
            }
        }
    }
}
