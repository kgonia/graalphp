package pl.codeloom.php.b1;

import pl.codeloom.php.b1.runtime.B1RequestContext;
import java.nio.file.Path;

public final class B1Main {
    public static void main(String[] args) throws Exception {
        try { run(args); }
        catch (pl.codeloom.php.ir.PhpCompileFatal fatal) {
            System.out.write(fatal.diagnostic().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            System.exit(255);
        }
    }
    private static void run(String[] args) throws Exception {
        var ctx = new B1RequestContext(System.out);
        var path = Path.of(args[0]);
        var file = B1Compiler.compile(pl.codeloom.php.ir.PhpIrLowerer.lowerFile(
                args.length > 1 ? args[1] : path.toString(), java.nio.file.Files.readAllBytes(path)), ctx,
                args.length > 2 ? Integer.parseInt(args[2]) : 7500);
        ctx.runScript(file.entry());
        System.err.printf("B1Lint: %d hidden classes (%d eval), 0 violations; live destructibles: %d; outline helpers: %d; max Code: %d%n",
                ctx.lintedClasses(), ctx.lintedClasses() - file.lintedClasses(), ctx.liveDestructibleCount(),
                ctx.outlineHelpers(), ctx.largestMethod());
    }
}
