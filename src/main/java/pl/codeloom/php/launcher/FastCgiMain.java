package pl.codeloom.php.launcher;

import pl.codeloom.php.runtime.Phases;

import org.graalvm.polyglot.Engine;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A FastCGI server in php-fpm's place (wiki/decisions.md, D11): a web server (nginx's fastcgi_pass) sends it
 * requests, it runs each in a new context on one shared engine, on a pool of threads that plays php-fpm's
 * pm=static workers. The language runs under the fpm SAPI: php_sapi_name() and PHP_SAPI are fpm-fcgi, the request's
 * FastCGI params are its CGI variables, as php-fpm with clear_env=yes gives them; the web server does routing,
 * static files, TLS and HTTP/2.
 *
 * <p>{@code FastCgiMain [--listen=127.0.0.1:9000 | --listen=/path/to.sock] [--threads=n] [--ast-root=dir]
 * [--source-root=dir] [-d name=value...]}: -d is php.ini's, with php's defaults when none is given, as php-fpm
 * without a php.ini. A request body is read up to post_max_size's default, 8 MB (413 past it).
 *
 * <p>For profiling, as HttpMain's: -Dphp.http.closeAfter=n closes the engine after n PHP requests,
 * -Dphp.http.closeFile=path after the first connection that ends with the file there; the server stops taking
 * connections, lets the requests in flight finish (a minute at most), closes the engine (its tools write their output:
 * the compilation statistics, the splitting summary) and exits.
 */
public final class FastCgiMain {
    private static final long MAX_BODY = 8 * 1024 * 1024;

    private FastCgiMain() {
    }

    public static void main(String[] args) throws IOException {
        PhpMain.compilerDefaults();
        String listen = "127.0.0.1:9000";
        int threads = Runtime.getRuntime().availableProcessors();
        String astRoot = "target/ast";
        String sourceRoot = ".";
        Map<String, String> ini = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            String value = arg.substring(arg.indexOf('=') + 1);
            if (arg.startsWith("--listen=")) {
                listen = value;
            } else if (arg.startsWith("--threads=")) {
                threads = Integer.parseInt(value);
            } else if (arg.startsWith("--ast-root=")) {
                astRoot = value;
            } else if (arg.startsWith("--source-root=")) {
                sourceRoot = value;
            } else if (arg.equals("-d") && i + 1 < args.length) {
                PhpMain.putIni(ini, args[++i]);
            } else if (arg.startsWith("-d")) {
                PhpMain.putIni(ini, arg.substring(2));
            } else {
                System.err.println("usage: FastCgiMain [--listen=127.0.0.1:9000 | --listen=/path/to.sock] "
                        + "[--threads=n] [--ast-root=dir] [--source-root=dir] [-d name=value...]");
                System.exit(2);
            }
        }
        // the language's experimental options (php.Parser) come as system properties, as PhpMain's
        Engine engine = Engine.newBuilder("php").allowExperimentalOptions(true).build();
        CgiRequests requests = new CgiRequests(engine, "fpm", ini, astRoot, sourceRoot);
        boolean unix = listen.contains("/");
        ServerSocketChannel server;
        SocketAddress address;
        if (unix) {
            Path path = Path.of(listen);
            Files.deleteIfExists(path);
            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            address = UnixDomainSocketAddress.of(path);
        } else {
            int colon = listen.lastIndexOf(':');
            server = ServerSocketChannel.open();
            server.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            address = new InetSocketAddress(listen.substring(0, colon), Integer.parseInt(listen.substring(colon + 1)));
        }
        // php-fpm's listen.backlog default
        server.bind(address, 511);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        HeapProbe.startIfAsked();
        GuestSampler.startIfAsked(engine);
        if (Phases.ENABLED) {
            RequestPhases.printStart();
        }
        System.err.println("listening on " + listen + " (FastCGI, " + threads + " threads)");
        int maxConns = threads;
        int closeAfter = Integer.getInteger("php.http.closeAfter", 0);
        String closeFileName = System.getProperty("php.http.closeFile");
        Path closeFile = closeFileName == null ? null : Path.of(closeFileName);
        AtomicBoolean closing = new AtomicBoolean();
        ServerSocketChannel listener = server;
        Runnable close = () -> {
            try {
                listener.close();
                pool.shutdown();
                pool.awaitTermination(60, TimeUnit.SECONDS);
            } catch (IOException | InterruptedException e) {
                System.err.println("closing: " + e);
            }
            GuestSampler.writeIfRunning();
            engine.close(true);
            System.exit(0);
        };
        while (!closing.get()) {
            SocketChannel channel;
            try {
                channel = server.accept();
            } catch (IOException e) {
                if (closing.get()) {
                    break;
                }
                throw e;
            }
            if (!unix) {
                // a response's last records (END_REQUEST) go out at once, not after the peer's delayed ACK
                channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            }
            pool.execute(() -> {
                serve(requests, maxConns, channel);
                boolean done = (closeAfter > 0 && requests.started() >= closeAfter)
                        || (closeFile != null && Files.exists(closeFile));
                if (done && closing.compareAndSet(false, true)) {
                    new Thread(close).start();
                }
            });
        }
    }

    private static void serve(CgiRequests requests, int threads, SocketChannel channel) {
        try (channel) {
            new FastCgiConnection(requests, threads, MAX_BODY, Channels.newInputStream(channel),
                    Channels.newOutputStream(channel)).serve();
        } catch (IOException | RuntimeException e) {
            // the web server closed the connection mid-request, or sent what isn't FastCGI
            System.err.println("FastCGI connection failed: " + e);
        }
    }
}
