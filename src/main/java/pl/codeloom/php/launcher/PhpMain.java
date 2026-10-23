package pl.codeloom.php.launcher;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Runs a PHP script: {@code PhpMain [--ast-root=dir] [--source-root=dir] [--contexts=n] script.php [args...]}.
 * The script's AST export must exist under the AST root (tools/ast-export.php).
 *
 * <p>With --contexts=n the script runs n times, each in a new context on one shared engine, as a server with a
 * context per request would; only the last run's output is kept. Code is then compiled for several contexts, so
 * benchmarks measure the server's mode rather than the single-context speculation.
 */
public final class PhpMain {
    private PhpMain() {
    }

    public static void main(String[] args) throws IOException {
        String astRoot = "target/ast";
        String sourceRoot = ".";
        int contexts = 1;
        int i = 0;
        for (; i < args.length && args[i].startsWith("--"); i++) {
            if (args[i].startsWith("--ast-root=")) {
                astRoot = args[i].substring("--ast-root=".length());
            } else if (args[i].startsWith("--source-root=")) {
                sourceRoot = args[i].substring("--source-root=".length());
            } else if (args[i].startsWith("--contexts=")) {
                contexts = Integer.parseInt(args[i].substring("--contexts=".length()));
            } else {
                System.err.println("unknown option " + args[i]);
                System.exit(2);
            }
        }
        if (i >= args.length) {
            System.err.println("usage: PhpMain [--ast-root=dir] [--source-root=dir] [--contexts=n] script.php [args...]");
            System.exit(2);
        }
        String script = args[i];
        String[] phpArgs = Arrays.copyOfRange(args, i, args.length);
        Source source = Source.newBuilder("php", new File(script)).build();
        if (contexts <= 1) {
            run(Context.newBuilder("php"), source, astRoot, sourceRoot, phpArgs, true);
            return;
        }
        try (Engine engine = Engine.newBuilder("php").allowExperimentalOptions(true).build()) {
            for (int n = 1; n <= contexts; n++) {
                run(Context.newBuilder("php").engine(engine), source, astRoot, sourceRoot, phpArgs, n == contexts);
            }
        }
    }

    private static void run(Context.Builder builder, Source source, String astRoot, String sourceRoot,
                            String[] phpArgs, boolean keepOutput) {
        if (!keepOutput) {
            builder.out(OutputStream.nullOutputStream());
        }
        try (Context context = builder
                .option("php.AstRoot", astRoot)
                .option("php.SourceRoot", sourceRoot)
                .arguments("php", phpArgs)
                .allowAllAccess(true)
                .build()) {
            context.eval(source);
        } catch (PolyglotException e) {
            if (e.isExit()) {
                // exit() ends this run; an earlier run of --contexts=n goes on to the next
                if (keepOutput) {
                    System.exit(e.getExitStatus());
                }
                return;
            }
            System.out.flush();
            System.err.println("PHP Fatal error:  " + e.getMessage());
            if (System.getenv("PHP_TRACE") != null) {
                for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
                    if (frame.isGuestFrame()) {
                        System.err.println("  at " + frame);
                    }
                }
            }
            if (e.isInternalError() || !e.isGuestException()) {
                e.printStackTrace();
            }
            System.exit(255);
        }
    }
}
