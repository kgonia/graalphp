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

/** Reads the JSON that tools/ast-export.php writes into {@link Ast} trees. */
public final class AstReader {
    private static final JsonFactory JSON = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(100_000)
                    .maxStringLength(Integer.MAX_VALUE)
                    .build())
            .build();

    private AstReader() {
    }

    public static Ast read(InputStream in) throws IOException {
        try (JsonParser parser = JSON.createParser(in)) {
            parser.nextToken();
            return (Ast) value(parser);
        }
    }

    private static Object value(JsonParser parser) throws IOException {
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
                return object(parser);
            default:
                throw new IOException("unexpected JSON token " + token);
        }
    }

    private static Object object(JsonParser parser) throws IOException {
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
                            list.add(value(parser));
                        }
                    } else {
                        named = new LinkedHashMap<>();
                        while (parser.nextToken() == JsonToken.FIELD_NAME) {
                            String name = parser.currentName();
                            parser.nextToken();
                            named.put(name, value(parser));
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
}
