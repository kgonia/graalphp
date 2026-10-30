package pl.codeloom.php.parser;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the JSON that tools/ast-export.php writes into {@link Ast} trees. A function's body is left unread, as an
 * {@link AstBody} over the export's bytes, until it is needed.
 */
public final class AstReader {
    private static final JsonFactory JSON = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(100_000)
                    .maxStringLength(Integer.MAX_VALUE)
                    .build())
            .build();

    /** The kinds whose "stmts" child is a function body, translated on the function's first call. */
    private static final Set<String> FUNCTIONS = Set.of("AST_FUNC_DECL", "AST_METHOD", "AST_CLOSURE", "AST_ARROW_FUNC");

    private final JsonParser parser;
    private final byte[] json;
    /** Where the bytes being read start in json: the parser's offsets count from there. */
    private final int base;

    private AstReader(JsonParser parser, byte[] json, int base) {
        this.parser = parser;
        this.json = json;
        this.base = base;
    }

    public static Ast read(InputStream in) throws IOException {
        byte[] json = in.readAllBytes();
        return (Ast) read(json, 0, json.length);
    }

    /** The value in json[offset, offset + length): a tree, or a leaf value. */
    static Object read(byte[] json, int offset, int length) throws IOException {
        try (JsonParser parser = JSON.createParser(json, offset, length)) {
            parser.nextToken();
            return new AstReader(parser, json, offset).value();
        }
    }

    private Object value() throws IOException {
        JsonToken token = parser.currentToken();
        switch (token) {
            case VALUE_NULL:
                return null;
            case VALUE_STRING:
                return parser.getText();
            case VALUE_NUMBER_INT:
                return parser.getLongValue();
            case VALUE_TRUE:
                return Boolean.TRUE;
            case VALUE_FALSE:
                return Boolean.FALSE;
            case START_OBJECT:
                return object();
            default:
                throw new IOException("unexpected JSON token " + token);
        }
    }

    private Object object() throws IOException {
        String kind = null;
        int flags = 0;
        int line = 0;
        int endLine = 0;
        List<Object> list = null;
        Map<String, Object> named = null;
        Object scalar = null;
        while (parser.nextToken() == JsonToken.FIELD_NAME) {
            String field = parser.currentName();
            parser.nextToken();
            switch (field) {
                case "k" -> kind = parser.getText();
                case "f" -> flags = parser.getIntValue();
                case "l" -> line = parser.getIntValue();
                case "e" -> endLine = parser.getIntValue();
                case "c" -> {
                    if (parser.currentToken() == JsonToken.START_ARRAY) {
                        list = new ArrayList<>();
                        while (parser.nextToken() != JsonToken.END_ARRAY) {
                            list.add(value());
                        }
                    } else {
                        // the exporter writes a node's kind before its children
                        boolean function = FUNCTIONS.contains(kind);
                        named = new LinkedHashMap<>();
                        while (parser.nextToken() == JsonToken.FIELD_NAME) {
                            String name = parser.currentName();
                            parser.nextToken();
                            named.put(name, function && name.equals("stmts")
                                    && parser.currentToken() == JsonToken.START_OBJECT ? body() : value());
                        }
                    }
                }
                case "b" -> scalar = Base64.getDecoder().decode(parser.getText());
                case "d" -> scalar = switch (parser.getText()) {
                    case "INF" -> Double.POSITIVE_INFINITY;
                    case "-INF" -> Double.NEGATIVE_INFINITY;
                    case "NAN" -> Double.NaN;
                    default -> Double.parseDouble(parser.getText());
                };
                default -> throw new IOException("unexpected AST field " + field);
            }
        }
        if (kind == null) {
            return scalar;
        }
        return new Ast(kind, flags, line, endLine, list, named);
    }

    /** Skips the body at the current token, leaving its byte range. */
    private AstBody body() throws IOException {
        int start = base + (int) parser.currentTokenLocation().getByteOffset();
        parser.skipChildren();
        int end = base + (int) parser.currentLocation().getByteOffset();
        return new AstBody(json, start, end - start);
    }
}
