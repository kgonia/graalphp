package pl.codeloom.php.b1.runtime;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Snapshot the PHP call frames while the allocating instruction is current. */
final class B1Exception {
    private B1Exception() {}

    static Map<Long, Object> trace(B1Frame frame) {
        var trace = new LinkedHashMap<Long, Object>();
        for (B1Frame f = frame; f != null && !f.isTop(); f = f.caller) {
            var row = new LinkedHashMap<String, Object>();
            if (f.caller != null) {
                row.put("file", f.caller.file);
                row.put("line", (long) f.caller.line);
            }
            int scope = f.function.indexOf("::");
            row.put("function", f.function.substring(scope < 0 ? 0 : scope + 2));
            if (scope >= 0) {
                row.put("class", f.function.substring(0, scope));
                row.put("type", "->");
            }
            row.put("args", f.traceArguments());
            trace.put((long)trace.size(), row);
        }
        return trace;
    }

    static String traceString(B1RequestContext ctx, Map<?, ?> trace) {
        var result = new StringBuilder();
        int n = 0;
        for (Object entry : trace.values()) {
            Map<?, ?> row = (Map<?, ?>) entry;
            result.append('#').append(n++).append(' ');
            if (row.containsKey("file")) result.append(row.get("file")).append('(').append(row.get("line")).append("): ");
            else result.append("[internal function]: ");
            if (row.containsKey("class")) result.append(row.get("class")).append(row.get("type"));
            result.append(row.get("function")).append('(');
            var args = new StringJoiner(", ");
            for (Object value : ((Map<?, ?>)row.get("args")).values()) {
                args.add(value instanceof String str ? quoted(str)
                        : value instanceof B1Object ? "Object(" + ctx.typeName(value) + ")"
                        : value instanceof Map ? "Array" : value == null ? "NULL" : value.toString());
            }
            result.append(args).append(")\n");
        }
        return result.append('#').append(n).append(" {main}").toString();
    }

    private static String quoted(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        var result = new StringBuilder("'");
        for (int i = 0; i < Math.min(15, bytes.length); i++) {
            int c = Byte.toUnsignedInt(bytes[i]);
            switch (c) {
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                case '\f' -> result.append("\\f");
                case 11 -> result.append("\\v");
                case 27 -> result.append("\\e");
                case '\\' -> result.append("\\\\");
                default -> {
                    if (c >= 32 && c <= 126) result.append((char)c);
                    else result.append("\\x").append(java.util.HexFormat.of().withUpperCase().toHexDigits(bytes[i]));
                }
            }
        }
        if (bytes.length > 15) result.append("...");
        return result.append("'").toString();
    }
}
