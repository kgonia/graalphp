package pl.codeloom.php.launcher;

import pl.codeloom.php.runtime.Phases;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.io.IOAccess;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs a script as one request of a web SAPI, as the servers (HttpMain, FastCgiMain) call it: a new context on the
 * shared engine (the compiled code is shared, the PHP state is not), the request's CGI variables as its only
 * environment, its body on stdin, the CGI response (header block, then the body) on the output stream given.
 */
final class CgiRequests {
    private final Engine engine;
    /** php.Sapi: cgi or fpm. */
    private final String sapi;
    /** Each request's php.ini settings, as php.Ini options. */
    private final Map<String, String> ini;
    private final String astRoot;
    private final String sourceRoot;
    /** The PHP requests begun so far (for the servers' php.http.closeAfter). */
    private final AtomicInteger started = new AtomicInteger();

    CgiRequests(Engine engine, String sapi, Map<String, String> ini, String astRoot, String sourceRoot) {
        this.engine = engine;
        this.sapi = sapi;
        this.ini = ini;
        this.astRoot = astRoot;
        this.sourceRoot = sourceRoot;
    }

    int started() {
        return started.get();
    }

    /**
     * Runs script with the request's environment and body, its output (the CGI response) written to out and flushed.
     * A failure outside PHP's own error handling is logged with label (the request's URI); PHP reports its errors
     * itself, to the server's stderr.
     */
    void run(Path script, Map<String, String> environment, byte[] body, OutputStream out, RequestPhases phases,
             String label) {
        started.incrementAndGet();
        HeapProbe.IN_FLIGHT.incrementAndGet();
        try (Context context = Context.newBuilder("php")
                .engine(engine)
                // files and native code (PCRE2); the environment is the request's CGI variables, not the server's
                .allowIO(IOAccess.ALL)
                .allowNativeAccess(true)
                // a fiber runs on a thread of its own (PhpFiber)
                .allowCreateThread(true)
                .allowExperimentalOptions(true)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .environment(environment)
                // a web SAPI runs the script in its directory (php_execute_script's VCWD_CHDIR_FILE)
                .currentWorkingDirectory(script.getParent())
                .in(new ByteArrayInputStream(body))
                .out(out)
                .option("php.Sapi", sapi)
                .options(ini)
                .option("php.AstRoot", astRoot)
                .option("php.SourceRoot", sourceRoot)
                .arguments("php", new String[] {script.toString()})
                .build()) {
            if (phases != null) {
                phases.contextReady();
            }
            try {
                context.eval(Source.newBuilder("php", script.toFile()).build());
            } finally {
                if (phases != null) {
                    phases.evaluated();
                }
            }
        } catch (PolyglotException e) {
            if (!e.isExit()) {
                // the runtime reports PHP's errors itself (to stderr with log_errors); this failed outside them
                System.err.println("PHP Fatal error:  " + e.getMessage() + " (" + label + ")");
                if (e.isInternalError() || !e.isGuestException()) {
                    e.printStackTrace();
                }
            }
        } catch (IOException e) {
            // Source.newBuilder(File) reads the script: gone between the server's check and the read
            System.err.println("PHP Fatal error:  reading " + script + " failed: " + e.getMessage() + " (" + label
                    + ")");
        } finally {
            HeapProbe.IN_FLIGHT.decrementAndGet();
        }
    }

    /** -Dphp.phases=true: a request's phases, or null. */
    static RequestPhases phases() {
        return Phases.ENABLED ? new RequestPhases() : null;
    }
}
