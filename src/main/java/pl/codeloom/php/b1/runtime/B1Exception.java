package pl.codeloom.php.b1.runtime;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Snapshot the PHP call frames while the allocating instruction is current. */
final class B1Exception {
    private B1Exception() {}

    static B1Array trace(B1RequestContext ctx,B1Frame frame) {
        var trace = new B1Array(4);
        for (B1Frame f = frame; f != null && !f.isTop(); f = f.caller) {
            var row = new B1Array(6);
            if (f.caller != null) {
                row.put(ctx,"file", f.caller.file);
                row.put(ctx,"line", (long) f.caller.line);
            }
            int scope = f.function.startsWith("{closure:") ? -1 : f.function.indexOf("::");
            row.put(ctx,"function", f.function.substring(scope < 0 ? 0 : scope + 2));
            if (scope >= 0) {
                row.put(ctx,"class", f.function.substring(0, scope));
                row.put(ctx,"type", f.staticCall ? "::" : "->");
            }
            B1Array args = f.traceArguments(ctx);
            row.put(ctx,"args",args); B1Object.release(ctx,args);
            trace.append(ctx,row); B1Object.release(ctx,row);
        }
        return trace;
    }

    static String traceString(B1RequestContext ctx, B1Array trace) {
        var result = new StringBuilder();
        int n = 0;
        for (int i = 0; i < trace.size(); i++) {
            B1Array row = (B1Array)trace.valueAt(i);
            result.append('#').append(n++).append(' ');
            if (row.contains("file")) result.append(row.get("file")).append('(').append(row.get("line")).append("): ");
            else result.append("[internal function]: ");
            if (row.contains("class")) result.append(row.get("class")).append(row.get("type"));
            result.append(row.get("function")).append('(');
            var args = new StringJoiner(", ");
            B1Array values = (B1Array)row.get("args");
            for (int j = 0; j < values.size(); j++) {
                Object value = values.valueAt(j);
                args.add(value instanceof String || value instanceof B1Bytes ? quoted(value)
                        : value instanceof B1Object ? "Object(" + ctx.typeName(value) + ")"
                        : value instanceof Map || value instanceof B1Array ? "Array" : value == null ? "NULL" : value.toString());
            }
            result.append(args).append(")\n");
        }
        return result.append('#').append(n).append(" {main}").toString();
    }

    static String format(B1RequestContext ctx, B1Object exception) {
        var chain = new ArrayList<B1Object>();
        for (Object cur = exception; cur instanceof B1Object obj && !chain.contains(obj); cur = obj.read("previous")) {
            chain.add(obj);
        }
        var out = new StringBuilder();
        for (int i = chain.size() - 1; i >= 0; i--) {
            if (out.length() > 0) out.append("\n\nNext ");
            B1Object ex = chain.get(i);
            String cls = ctx.typeName(ex);
            String msg = ex.read("message") == null ? "" : ctx.string(ex.read("message"));
            String file = ex.read("file") == null ? "" : ctx.string(ex.read("file"));
            Object line = ex.read("line");
            out.append(cls);
            if (!msg.isEmpty()) out.append(": ").append(msg);
            out.append(" in ").append(file).append(':').append(line == null ? 0 : line)
                    .append("\nStack trace:\n").append(traceString(ctx, (B1Array) ex.read("trace")));
        }
        return out.toString();
    }

    private static String quoted(Object text) {
        byte[] bytes = B1Bytes.bytes(text);
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
