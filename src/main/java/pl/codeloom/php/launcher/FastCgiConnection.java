package pl.codeloom.php.launcher;

import pl.codeloom.php.builtins.Builtins;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One connection from the web server, as php-fpm's worker serves it (main/fastcgi.c, sapi/fpm/fpm/fpm_main.c): the
 * FastCGI records of one request at a time (no multiplexing, as php-fpm), the Responder role only. A request's
 * PARAMS are its CGI variables, its STDIN the body; the script's output goes back as STDOUT records while it runs, as
 * php's output layer flushes it, then END_REQUEST. With FCGI_KEEP_CONN the connection takes the next request,
 * otherwise it is closed after the response.
 */
final class FastCgiConnection {
    static final int VERSION = 1;
    static final int BEGIN_REQUEST = 1;
    static final int ABORT_REQUEST = 2;
    static final int END_REQUEST = 3;
    static final int PARAMS = 4;
    static final int STDIN = 5;
    static final int STDOUT = 6;
    static final int STDERR = 7;
    static final int DATA = 8;
    static final int GET_VALUES = 9;
    static final int GET_VALUES_RESULT = 10;
    static final int UNKNOWN_TYPE = 11;

    static final int RESPONDER = 1;
    static final int KEEP_CONN = 1;

    static final int REQUEST_COMPLETE = 0;
    static final int CANT_MPX_CONN = 1;
    static final int UNKNOWN_ROLE = 3;

    /** The most content one record carries. */
    static final int MAX_CONTENT = 0xffff;

    private final CgiRequests requests;
    private final int threads;
    private final long maxBody;
    private final DataInputStream in;
    private final OutputStream out;
    private final byte[] header = new byte[8];

    FastCgiConnection(CgiRequests requests, int threads, long maxBody, InputStream in, OutputStream out) {
        this.requests = requests;
        this.threads = threads;
        this.maxBody = maxBody;
        this.in = new DataInputStream(new BufferedInputStream(in, 1 << 16));
        this.out = new BufferedOutputStream(out, 1 << 16);
    }

    /** Serves the connection's requests until the web server closes it, or a request without KEEP_CONN ends. */
    void serve() throws IOException {
        Request request = null;
        while (true) {
            if (!readHeader()) {
                return;
            }
            int type = header[1] & 0xff;
            int id = ((header[2] & 0xff) << 8) | (header[3] & 0xff);
            int length = ((header[4] & 0xff) << 8) | (header[5] & 0xff);
            int padding = header[6] & 0xff;
            byte[] content = new byte[length];
            in.readFully(content);
            in.skipNBytes(padding);
            if (id == 0) {
                management(type, content);
                continue;
            }
            switch (type) {
                case BEGIN_REQUEST -> {
                    int role = length >= 3 ? ((content[0] & 0xff) << 8) | (content[1] & 0xff) : 0;
                    boolean keep = length >= 3 && (content[2] & KEEP_CONN) != 0;
                    if (request != null) {
                        // a second request while one is open: php-fpm doesn't multiplex
                        endRequest(id, 0, CANT_MPX_CONN);
                    } else if (role != RESPONDER) {
                        endRequest(id, 0, UNKNOWN_ROLE);
                        if (!keep) {
                            return;
                        }
                    } else {
                        request = new Request(id, keep);
                    }
                }
                case PARAMS -> {
                    if (request != null && request.id == id) {
                        request.params.write(content);
                    }
                }
                case STDIN -> {
                    if (request != null && request.id == id) {
                        if (length > 0) {
                            request.stdin(content);
                        } else {
                            boolean keep = request.keep;
                            respond(request);
                            request = null;
                            if (!keep) {
                                return;
                            }
                        }
                    }
                }
                case ABORT_REQUEST -> {
                    if (request != null && request.id == id) {
                        boolean keep = request.keep;
                        endRequest(id, 0, REQUEST_COMPLETE);
                        request = null;
                        if (!keep) {
                            return;
                        }
                    }
                }
                default -> {
                    // DATA belongs to the Filter role, other types to no request: ignored
                }
            }
        }
    }

    /** Reads a record's header; false at the end of the stream before one. */
    private boolean readHeader() throws IOException {
        int first = in.read();
        if (first < 0) {
            return false;
        }
        header[0] = (byte) first;
        in.readFully(header, 1, 7);
        if (first != VERSION) {
            throw new IOException("FastCGI version " + first);
        }
        return true;
    }

    /** A management record (request id 0): GET_VALUES answered as php-fpm answers it, others UNKNOWN_TYPE. */
    private void management(int type, byte[] content) throws IOException {
        if (type == GET_VALUES) {
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            for (String name : decodeParams(content).keySet()) {
                String value = switch (name) {
                    case "FCGI_MAX_CONNS", "FCGI_MAX_REQS" -> Integer.toString(threads);
                    case "FCGI_MPXS_CONNS" -> "0";
                    default -> null;
                };
                if (value != null) {
                    encodeParam(result, name, value);
                }
            }
            writeRecord(GET_VALUES_RESULT, 0, result.toByteArray(), 0, result.size());
        } else {
            byte[] body = {(byte) type, 0, 0, 0, 0, 0, 0, 0};
            writeRecord(UNKNOWN_TYPE, 0, body, 0, body.length);
        }
        out.flush();
    }

    /** Runs the request's script, its output as STDOUT records, then ends the request. */
    private void respond(Request request) throws IOException {
        Map<String, String> environment = decodeParams(request.params.toByteArray());
        Stdout stdout = new Stdout(request.id);
        String filename = environment.get("SCRIPT_FILENAME");
        if (request.tooLarge) {
            cgiError(stdout, "413 Request Entity Too Large", "Request body too large.\n");
        } else if (filename == null || !filename.endsWith(".php")) {
            // fpm_main.c: no script, or one security.limit_extensions (default .php) doesn't allow
            System.err.println("Access to the script '" + filename + "' has been denied (see "
                    + "security.limit_extensions)");
            cgiError(stdout, "403 Forbidden", "Access denied.\n");
        } else if (!Files.isRegularFile(Path.of(filename))) {
            // fpm_main.c: the primary script doesn't open, 404 with php's own body
            System.err.println("Primary script unknown: " + filename);
            cgiError(stdout, "404 Not Found", "File not found.\n");
        } else {
            Path script = Path.of(filename);
            environment.putIfAbsent("FCGI_ROLE", "RESPONDER");
            // php's PHP_SELF under FastCGI: the script's name and the path info after it
            environment.putIfAbsent("PHP_SELF", environment.getOrDefault("SCRIPT_NAME", "")
                    + environment.getOrDefault("PATH_INFO", ""));
            RequestPhases phases = CgiRequests.phases();
            String uri = environment.getOrDefault("REQUEST_URI", filename);
            requests.run(script, environment, request.body(), stdout, stdout::finish, phases, uri);
            if (phases != null) {
                phases.print(environment.getOrDefault("REQUEST_METHOD", "GET"), uri, stdout.status);
            }
        }
        stdout.end();
    }

    /** A response php-fpm writes without running a script: its status, X-Powered-By and php's content type. */
    private static void cgiError(Stdout stdout, String status, String body) throws IOException {
        String response = "Status: " + status + "\r\nX-Powered-By: PHP/" + Builtins.PHP_VERSION
                + "\r\nContent-type: text/html; charset=UTF-8\r\n\r\n" + body;
        stdout.write(response.getBytes(StandardCharsets.ISO_8859_1));
    }

    private void endRequest(int id, int appStatus, int protocolStatus) throws IOException {
        byte[] body = {(byte) (appStatus >>> 24), (byte) (appStatus >>> 16), (byte) (appStatus >>> 8),
                (byte) appStatus, (byte) protocolStatus, 0, 0, 0};
        writeRecord(END_REQUEST, id, body, 0, body.length);
        out.flush();
    }

    private void writeRecord(int type, int id, byte[] content, int offset, int length) throws IOException {
        int padding = -length & 7;
        out.write(VERSION);
        out.write(type);
        out.write(id >>> 8);
        out.write(id);
        out.write(length >>> 8);
        out.write(length);
        out.write(padding);
        out.write(0);
        out.write(content, offset, length);
        for (int i = 0; i < padding; i++) {
            out.write(0);
        }
    }

    /** FastCGI name-value pairs: each length one byte below 128, else four with the top bit set. */
    static Map<String, String> decodeParams(byte[] bytes) throws IOException {
        Map<String, String> params = new LinkedHashMap<>();
        int at = 0;
        while (at < bytes.length) {
            int[] lengths = new int[2];
            for (int i = 0; i < 2; i++) {
                if (at >= bytes.length) {
                    throw new IOException("FastCGI params truncated");
                }
                int b = bytes[at] & 0xff;
                if (b < 0x80) {
                    lengths[i] = b;
                    at++;
                } else {
                    if (at + 4 > bytes.length) {
                        throw new IOException("FastCGI params truncated");
                    }
                    lengths[i] = ((b & 0x7f) << 24) | ((bytes[at + 1] & 0xff) << 16) | ((bytes[at + 2] & 0xff) << 8)
                            | (bytes[at + 3] & 0xff);
                    at += 4;
                }
            }
            if (lengths[0] < 0 || lengths[1] < 0 || at + lengths[0] + lengths[1] > bytes.length) {
                throw new IOException("FastCGI params truncated");
            }
            // CGI variables are bytes; ISO-8859-1 keeps each byte as one char, as the HTTP server's are
            String name = new String(bytes, at, lengths[0], StandardCharsets.ISO_8859_1);
            String value = new String(bytes, at + lengths[0], lengths[1], StandardCharsets.ISO_8859_1);
            params.put(name, value);
            at += lengths[0] + lengths[1];
        }
        return params;
    }

    static void encodeParam(ByteArrayOutputStream out, String name, String value) {
        byte[] n = name.getBytes(StandardCharsets.ISO_8859_1);
        byte[] v = value.getBytes(StandardCharsets.ISO_8859_1);
        encodeLength(out, n.length);
        encodeLength(out, v.length);
        out.writeBytes(n);
        out.writeBytes(v);
    }

    private static void encodeLength(ByteArrayOutputStream out, int length) {
        if (length < 0x80) {
            out.write(length);
        } else {
            out.write((length >>> 24) | 0x80);
            out.write(length >>> 16);
            out.write(length >>> 8);
            out.write(length);
        }
    }

    /** A request being received: its params and body so far. */
    private final class Request {
        final int id;
        final boolean keep;
        final ByteArrayOutputStream params = new ByteArrayOutputStream();
        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        boolean tooLarge;

        Request(int id, boolean keep) {
            this.id = id;
            this.keep = keep;
        }

        void stdin(byte[] content) {
            if (body.size() + (long) content.length > maxBody) {
                tooLarge = true;
            } else if (!tooLarge) {
                body.write(content, 0, content.length);
            }
        }

        byte[] body() {
            return body.toByteArray();
        }
    }

    /**
     * The script's output as STDOUT records: each write goes out as it comes (php's output layer writes in its own
     * buffer's chunks), a flush sends what is buffered to the web server. The status is the header block's, for
     * the phase report.
     */
    private final class Stdout extends OutputStream {
        private final int id;
        int status = 200;
        private boolean headerSeen;
        /** The request was ended (fastcgi_finish_request, or the script's end): later output goes nowhere. */
        private boolean ended;

        Stdout(int id) {
            this.id = id;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (ended) {
                return;
            }
            if (!headerSeen && length > 0) {
                headerSeen = true;
                status = status(bytes, offset, length);
            }
            while (length > 0) {
                int chunk = Math.min(length, MAX_CONTENT & ~7);
                writeRecord(STDOUT, id, bytes, offset, chunk);
                offset += chunk;
                length -= chunk;
            }
        }

        @Override
        public void flush() throws IOException {
            if (!ended) {
                out.flush();
            }
        }

        /** The request's end: the empty STDOUT and END_REQUEST, once. */
        void end() throws IOException {
            if (!ended) {
                ended = true;
                out.flush();
                writeRecord(STDOUT, id, header, 0, 0);
                endRequest(id, 0, REQUEST_COMPLETE);
            }
        }

        /**
         * fastcgi_finish_request(): the web server gets the end of the response while the script goes on (php-fpm's
         * fcgi_end_request); a web server gone ends it too.
         */
        void finish() {
            try {
                end();
            } catch (IOException e) {
                ended = true;
            }
        }

        @Override
        public void close() {
            // the request's end writes the empty STDOUT record
        }

        /** The Status line of the header block at the output's start (CgiSapi writes it first), else 200. */
        private static int status(byte[] bytes, int offset, int length) {
            byte[] prefix = "Status: ".getBytes(StandardCharsets.ISO_8859_1);
            if (length < prefix.length + 3) {
                return 200;
            }
            for (int i = 0; i < prefix.length; i++) {
                if (bytes[offset + i] != prefix[i]) {
                    return 200;
                }
            }
            int code = 0;
            for (int i = 0; i < 3; i++) {
                int digit = bytes[offset + prefix.length + i] - '0';
                if (digit < 0 || digit > 9) {
                    return 200;
                }
                code = code * 10 + digit;
            }
            return code;
        }
    }
}
