package pl.codeloom.php.b1.runtime;

import java.util.HashSet;
import java.util.Set;
import pl.codeloom.php.ir.PhpFloatFormat;

/** PHP's var_dump emits binary strings verbatim and guards only the active recursion path. */
final class B1Dump {
    private final B1RequestContext ctx;
    private final Set<B1GcNode> active = new HashSet<>();

    B1Dump(B1RequestContext ctx) { this.ctx = ctx; }

    void value(Object value, int depth) {
        String indent = "  ".repeat(depth);
        ctx.echo(indent);
        boolean reference = value instanceof B1Ref ref && ref.reference() && ref.shared();
        if (value instanceof B1Ref ref) value = ref.get();
        if (value instanceof B1GcNode node && !active.add(node)) {
            ctx.echo("*RECURSION*\n");
            return;
        }
        if (reference) ctx.echo("&");
        try {
            switch (value) {
                case null -> ctx.echo("NULL\n");
                case Long n -> ctx.echo("int(" + n + ")\n");
                case Boolean b -> ctx.echo("bool(" + b + ")\n");
                case Double d -> ctx.echo("float(" + PhpFloatFormat.doubleToRepr(d) + ")\n");
                case B1Array array -> {
                    ctx.echo("array(" + array.size() + ") {\n");
                    entries(array,depth+1);
                    ctx.echo(indent + "}\n");
                }
                case B1Object obj -> obj.dump(ctx,this,depth);
                case B1Closure fn -> {
                    var info = fn.debugInfo(ctx);
                    try { object("Closure",fn.id,info,depth); } finally { info.release(ctx); }
                }
                default -> {
                    ctx.echo("string(" + B1Bytes.bytes(value).length + ") \"");
                    ctx.echo(value);
                    ctx.echo("\"\n");
                }
            }
        } finally {
            if (value instanceof B1GcNode node) active.remove(node);
        }
    }

    void object(String type,long id,B1Array properties,int depth) {
        ctx.echo("object(" + type + ")#" + id + " (" + properties.size() + ") {\n");
        entries(properties,depth+1);
        ctx.echo("  ".repeat(depth) + "}\n");
    }
    private void entries(B1Array array,int depth) {
        String indent = "  ".repeat(depth);
        for (int i = 0; i < array.size(); i++) {
            Object key = array.keyAt(i);
            boolean quoted = !(key instanceof Long);
            ctx.echo(indent + (quoted ? "[\"" : "["));
            ctx.echo(key);
            ctx.echo(quoted ? "\"]=>\n" : "]=>\n");
            value(array.rawAt(i),depth);
        }
    }
}
