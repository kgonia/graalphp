package pl.codeloom.php.launcher;

import org.graalvm.polyglot.Engine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The FastCGI side of FastCgiMain: records in, the script's CGI response out, as php-fpm answers a web server. */
class FastCgiConnectionTest {
    private static Engine engine;
    private static CgiRequests requests;

    @TempDir
    static Path dir;

    @BeforeAll
    static void engine() {
        engine = Engine.newBuilder("php").allowExperimentalOptions(true).option("engine.WarnInterpreterOnly", "false")
                .option("php.Parser", "java").build();
        requests = new CgiRequests(engine, "fpm", Map.of(), "target/ast", ".");
    }

    @AfterAll
    static void close() {
        engine.close();
    }

    /** A record as the web server writes it. */
    private static void record(ByteArrayOutputStream out, int type, int id, byte[] content) {
        int padding = -content.length & 7;
        out.writeBytes(new byte[] {1, (byte) type, (byte) (id >>> 8), (byte) id, (byte) (content.length >>> 8),
                (byte) content.length, (byte) padding, 0});
        out.writeBytes(content);
        out.writeBytes(new byte[padding]);
    }

    private static void request(ByteArrayOutputStream out, int id, boolean keep, Map<String, String> params,
                                String body) {
        record(out, FastCgiConnection.BEGIN_REQUEST, id, new byte[] {0, 1, (byte) (keep ? 1 : 0), 0, 0, 0, 0, 0});
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        for (Map.Entry<String, String> param : params.entrySet()) {
            FastCgiConnection.encodeParam(encoded, param.getKey(), param.getValue());
        }
        record(out, FastCgiConnection.PARAMS, id, encoded.toByteArray());
        record(out, FastCgiConnection.PARAMS, id, new byte[0]);
        if (!body.isEmpty()) {
            record(out, FastCgiConnection.STDIN, id, body.getBytes(StandardCharsets.ISO_8859_1));
        }
        record(out, FastCgiConnection.STDIN, id, new byte[0]);
    }

    private record Record(int type, int id, byte[] content) {
    }

    private static List<Record> serve(ByteArrayOutputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new FastCgiConnection(requests, 4, 1 << 20, new ByteArrayInputStream(input.toByteArray()), output).serve();
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(output.toByteArray()));
        List<Record> records = new ArrayList<>();
        while (in.available() > 0) {
            byte[] header = new byte[8];
            in.readFully(header);
            assertEquals(1, header[0]);
            byte[] content = new byte[((header[4] & 0xff) << 8) | (header[5] & 0xff)];
            in.readFully(content);
            in.skipNBytes(header[6] & 0xff);
            records.add(new Record(header[1], ((header[2] & 0xff) << 8) | (header[3] & 0xff), content));
        }
        return records;
    }

    /** The STDOUT of request id, up to its empty record. */
    private static String stdout(List<Record> records, int id) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Record r : records) {
            if (r.type == FastCgiConnection.STDOUT && r.id == id) {
                out.writeBytes(r.content);
            }
        }
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    private static Map<String, String> params(Path script, String method, String query) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("SCRIPT_FILENAME", script.toString());
        params.put("SCRIPT_NAME", "/" + script.getFileName());
        params.put("REQUEST_METHOD", method);
        params.put("QUERY_STRING", query);
        params.put("REQUEST_URI", "/" + script.getFileName() + (query.isEmpty() ? "" : "?" + query));
        return params;
    }

    @Test
    void runsTheScriptUnderFpm() throws IOException {
        Path script = Files.writeString(dir.resolve("info.php"), "<?php header('X-A: 1'); echo php_sapi_name(), ' ',"
                + " PHP_SAPI, ' ', $_GET['q'], ' ', $_POST['p'], ' ', $_SERVER['FCGI_ROLE'], ' ', $_SERVER['PHP_SELF'];");
        Map<String, String> params = params(script, "POST", "q=1");
        params.put("CONTENT_TYPE", "application/x-www-form-urlencoded");
        params.put("CONTENT_LENGTH", "3");
        ByteArrayOutputStream in = new ByteArrayOutputStream();
        request(in, 1, false, params, "p=2");
        List<Record> records = serve(in);
        String out = stdout(records, 1);
        assertTrue(out.contains("X-A: 1\r\n"), out);
        assertTrue(out.endsWith("\r\n\r\nfpm-fcgi fpm-fcgi 1 2 RESPONDER /info.php"), out);
        Record last = records.get(records.size() - 1);
        assertEquals(FastCgiConnection.END_REQUEST, last.type);
        assertEquals(FastCgiConnection.REQUEST_COMPLETE, last.content[4]);
    }

    @Test
    void writesTheStatusAsPhpFpm() throws IOException {
        Path script = Files.writeString(dir.resolve("status.php"), "<?php switch ($_GET['m']) {"
                + " case 'code': http_response_code(404); break;"
                + " case 'line': header('HTTP/1.1 404 Custom Reason'); break;"
                + " case 'teapot': http_response_code(418); break;"
                + " case 'location': header('Location: /next'); break; }");
        ByteArrayOutputStream in = new ByteArrayOutputStream();
        request(in, 1, true, params(script, "GET", "m=ok"), "");
        request(in, 2, true, params(script, "GET", "m=code"), "");
        request(in, 3, true, params(script, "GET", "m=line"), "");
        request(in, 4, true, params(script, "GET", "m=teapot"), "");
        Map<String, String> post = params(script, "POST", "m=location");
        post.put("SERVER_PROTOCOL", "HTTP/1.1");
        request(in, 5, true, post, "");
        request(in, 6, false, params(script, "GET", "m=location"), "");
        List<Record> records = serve(in);
        assertTrue(stdout(records, 1).startsWith("X-Powered-By: "), stdout(records, 1));
        assertTrue(stdout(records, 2).startsWith("Status: 404 Not Found\r\n"), stdout(records, 2));
        assertTrue(stdout(records, 3).startsWith("Status: 404 Custom Reason\r\n"), stdout(records, 3));
        assertTrue(stdout(records, 4).startsWith("Status: 418\r\n"), stdout(records, 4));
        assertTrue(stdout(records, 5).startsWith("Status: 302 Found\r\n"), stdout(records, 5));
        assertTrue(stdout(records, 6).startsWith("Status: 302 Found\r\n"), stdout(records, 6));
    }

    @Test
    void keepsTheConnectionForTheNextRequest() throws IOException {
        Path script = Files.writeString(dir.resolve("echo.php"), "<?php echo $_GET['n'];");
        ByteArrayOutputStream in = new ByteArrayOutputStream();
        request(in, 1, true, params(script, "GET", "n=first"), "");
        request(in, 2, false, params(script, "GET", "n=second"), "");
        List<Record> records = serve(in);
        assertTrue(stdout(records, 1).endsWith("first"));
        assertTrue(stdout(records, 2).endsWith("second"));
        assertEquals(2, records.stream().filter(r -> r.type == FastCgiConnection.END_REQUEST).count());
    }

    @Test
    void answersMissingAndForbiddenScriptsAsPhpFpm() throws IOException {
        ByteArrayOutputStream in = new ByteArrayOutputStream();
        request(in, 1, true, params(dir.resolve("missing.php"), "GET", ""), "");
        request(in, 2, false, params(dir.resolve("style.css"), "GET", ""), "");
        List<Record> records = serve(in);
        assertTrue(stdout(records, 1).startsWith("Status: 404 Not Found\r\n"));
        assertTrue(stdout(records, 1).endsWith("\r\n\r\nFile not found.\n"));
        assertTrue(stdout(records, 2).startsWith("Status: 403 Forbidden\r\n"));
        assertTrue(stdout(records, 2).endsWith("\r\n\r\nAccess denied.\n"));
    }

    @Test
    void refusesASecondRequestOnTheConnection() throws IOException {
        Path script = Files.writeString(dir.resolve("one.php"), "<?php echo 'one';");
        ByteArrayOutputStream in = new ByteArrayOutputStream();
        record(in, FastCgiConnection.BEGIN_REQUEST, 1, new byte[] {0, 1, 0, 0, 0, 0, 0, 0});
        record(in, FastCgiConnection.BEGIN_REQUEST, 2, new byte[] {0, 1, 0, 0, 0, 0, 0, 0});
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        for (Map.Entry<String, String> param : params(script, "GET", "").entrySet()) {
            FastCgiConnection.encodeParam(encoded, param.getKey(), param.getValue());
        }
        record(in, FastCgiConnection.PARAMS, 1, encoded.toByteArray());
        record(in, FastCgiConnection.PARAMS, 1, new byte[0]);
        record(in, FastCgiConnection.STDIN, 1, new byte[0]);
        List<Record> records = serve(in);
        Record refused = records.get(0);
        assertEquals(FastCgiConnection.END_REQUEST, refused.type);
        assertEquals(2, refused.id);
        assertEquals(FastCgiConnection.CANT_MPX_CONN, refused.content[4]);
        assertTrue(stdout(records, 1).endsWith("one"));
    }

    @Test
    void answersGetValues() throws IOException {
        ByteArrayOutputStream names = new ByteArrayOutputStream();
        FastCgiConnection.encodeParam(names, "FCGI_MAX_CONNS", "");
        FastCgiConnection.encodeParam(names, "FCGI_MPXS_CONNS", "");
        ByteArrayOutputStream in = new ByteArrayOutputStream();
        record(in, FastCgiConnection.GET_VALUES, 0, names.toByteArray());
        List<Record> records = serve(in);
        assertEquals(FastCgiConnection.GET_VALUES_RESULT, records.get(0).type);
        assertEquals(Map.of("FCGI_MAX_CONNS", "4", "FCGI_MPXS_CONNS", "0"),
                FastCgiConnection.decodeParams(records.get(0).content));
    }

    @Test
    void decodesLongParams() throws IOException {
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        String cookie = "x".repeat(300);
        FastCgiConnection.encodeParam(encoded, "HTTP_COOKIE", cookie);
        FastCgiConnection.encodeParam(encoded, "EMPTY", "");
        Map<String, String> params = FastCgiConnection.decodeParams(encoded.toByteArray());
        assertEquals(cookie, params.get("HTTP_COOKIE"));
        assertEquals("", params.get("EMPTY"));
    }
}
