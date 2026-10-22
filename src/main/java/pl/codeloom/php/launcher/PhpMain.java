package pl.codeloom.php.launcher;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/**
 * Runs a PHP script: {@code PhpMain [--ast-root=dir] [--source-root=dir] script.php [args...]}. The script's AST
 * export must exist under the AST root (tools/ast-export.php).
 */
public final class PhpMain {
    private PhpMain() {
    }

    public static void main(String[] args) throws IOException {
        String astRoot = "target/ast";
        String sourceRoot = ".";
        int i = 0;
        for (; i < args.length && args[i].startsWith("--"); i++) {
            if (args[i].startsWith("--ast-root=")) {
                astRoot = args[i].substring("--ast-root=".length());
            } else if (args[i].startsWith("--source-root=")) {
                sourceRoot = args[i].substring("--source-root=".length());
            } else {
                System.err.println("unknown option " + args[i]);
                System.exit(2);
            }
        }
        if (i >= args.length) {
            System.err.println("usage: PhpMain [--ast-root=dir] [--source-root=dir] script.php [args...]");
            System.exit(2);
        }
        String script = args[i];
        String[] phpArgs = Arrays.copyOfRange(args, i, args.length);
        Source source = Source.newBuilder("php", new File(script)).build();
        try (Context context = Context.newBuilder("php")
                .option("php.AstRoot", astRoot)
                .option("php.SourceRoot", sourceRoot)
                .arguments("php", phpArgs)
                .allowAllAccess(true)
                .build()) {
            context.eval(source);
        } catch (PolyglotException e) {
            if (e.isExit()) {
                System.exit(e.getExitStatus());
            }
            System.out.flush();
            System.err.println("PHP Fatal error:  " + e.getMessage());
            if (e.isInternalError() || !e.isGuestException()) {
                e.printStackTrace();
            }
            System.exit(255);
        }
    }
}
