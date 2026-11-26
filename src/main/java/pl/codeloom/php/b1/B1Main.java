package pl.codeloom.php.b1;

import pl.codeloom.php.b1.runtime.B1RequestContext;
import java.nio.file.Path;

public final class B1Main {
    public static void main(String[] args) throws Exception {
        var ctx = new B1RequestContext(System.out);
        var file = B1Compiler.compile(Path.of(args[0]), ctx);
        ctx.runScript(file.entry());
        System.err.printf("B1Lint: %d emitted hidden classes, 0 violations; live destructibles: %d%n",
                file.lintedClasses(), ctx.liveDestructibleCount());
    }
}
