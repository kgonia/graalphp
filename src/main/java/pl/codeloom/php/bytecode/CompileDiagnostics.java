package pl.codeloom.php.bytecode;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.parser.zend.CompilerGlobals.Diagnostic;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.util.List;

/**
 * A file's compile-time diagnostics, its parse's then its compile's (JumpChecks), in the order php-src raises them.
 * php-src raises them when it compiles the file, before the file runs; with OPcache (the reference's CLI) that is
 * once per file in a request, as OPcache doesn't replay them (opcache.record_warnings=0). The translation is made
 * once and shared by the contexts, so its code raises them as it starts, the first time in each context
 * (RaiseCompileDiagnostics). eval'd code is compiled at each eval: each its own.
 */
public record CompileDiagnostics(String file, List<Diagnostic> diagnostics) {
    @TruffleBoundary
    public void raise(PhpContext context) {
        if (!context.firstCompile(this)) {
            return;
        }
        for (Diagnostic diagnostic : diagnostics) {
            ErrorFunctions.raise(context, diagnostic.type(), Str.of(diagnostic.message()),
                    new SourceLocation(file, diagnostic.line()));
        }
    }
}
