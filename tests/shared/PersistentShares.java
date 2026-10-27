import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Source;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Checks that a thread's persistent curl shares are cleaned up when the thread ends, as php-src's GSHUTDOWN destroys
 * them: a context on its own thread fetches from a local keep-alive server through a share of connections
 * (curl_share_init_persistent with CURL_LOCK_DATA_CONNECT), the context closes and the thread ends, and the server
 * must then see the connection closed. Usage (from tests/shared/run.sh): java -cp ... PersistentShares.java script.php
 */
public final class PersistentShares {
    public static void main(String[] args) throws Exception {
        Source source = Source.newBuilder("php", new File(args[0])).build();
        CountDownLatch served = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
             Engine engine = Engine.create("php")) {
            Thread acceptor = new Thread(() -> serve(server, served, closed));
            acceptor.setDaemon(true);
            acceptor.start();
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread worker = new Thread(() -> {
                try (Context context = Context.newBuilder("php").engine(engine).out(out)
                        .option("php.AstRoot", "target/ast").allowAllAccess(true)
                        .arguments("php", new String[] {args[0], url}).build()) {
                    context.eval(source);
                }
            });
            worker.start();
            worker.join();
            String output = out.toString(StandardCharsets.UTF_8).trim();
            if (!output.equals("ok") || !served.await(1, TimeUnit.SECONDS)) {
                System.out.println("FAILED: the transfer printed \"" + output + "\"");
                System.exit(1);
            }
            boolean cleaned = false;
            for (int i = 0; i < 100 && !cleaned; i++) {
                System.gc();
                cleaned = closed.await(100, TimeUnit.MILLISECONDS);
            }
            System.out.println(cleaned ? "ok: the ended thread's persistent share closed its connection"
                    : "FAILED: the connection a persistent share kept stayed open after its thread ended");
            System.exit(cleaned ? 0 : 1);
        }
    }

    /** Answers each request on a connection with "ok", keeping it alive; counts down when the client closes it. */
    private static void serve(ServerSocket server, CountDownLatch served, CountDownLatch closed) {
        try (Socket socket = server.accept()) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            while (readRequest(in)) {
                out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nContent-Type: text/plain\r\n\r\nok"
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();
                served.countDown();
            }
            closed.countDown();
        } catch (IOException e) {
            closed.countDown();
        }
    }

    /** Reads a request's head; false at the end of the stream. */
    private static boolean readRequest(InputStream in) throws IOException {
        int matched = 0;
        int b;
        while ((b = in.read()) >= 0) {
            matched = b == "\r\n\r\n".charAt(matched) ? matched + 1 : b == '\r' ? 1 : 0;
            if (matched == 4) {
                return true;
            }
        }
        return false;
    }
}
