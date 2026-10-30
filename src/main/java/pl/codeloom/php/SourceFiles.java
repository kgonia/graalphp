package pl.codeloom.php;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.frame.FrameDescriptor;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.source.Source;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.nodes.FileRootNode;
import pl.codeloom.php.nodes.StmtNode;
import pl.codeloom.php.nodes.stmt.BasicStmts;
import pl.codeloom.php.parser.Translator;
import pl.codeloom.php.parser.zend.CompilerGlobals;
import pl.codeloom.php.parser.zend.ScriptText;
import pl.codeloom.php.parser.zend.ZendCompileError;
import pl.codeloom.php.parser.zend.ZendParser;
import pl.codeloom.php.runtime.Backtrace;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpException;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpThrowable;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Files as php.Parser=java loads them: the file itself parsed by the runtime's parser (pl.codeloom.php.parser.zend),
 * as php-src compiles an included file, instead of its php-ast export. The tree is the one the export would hold;
 * function bodies are left as ranges of the file's text, which the file's Source holds, and parsed when translated.
 * <ul>
 * <li>A translation is shared as the export's is (PhpLanguage.file), for the version of the file it was parsed
 * from: its modification time and size, and short_open_tag, the one setting the parse reads.
 * <li>The warnings and deprecations the parse raises (E_COMPILE_WARNING, E_DEPRECATED) are raised in the context
 * that parses the file, when it does: as php-src with opcache (the reference's CLI has it, opcache.record_warnings
 * off) raises them when it compiles a file and not when it takes the file from its cache.
 * <li>A file that doesn't parse throws php-src's ParseError or CompileError at the include, at the file and line
 * of the error, or at the include's own when php-src gives the running code's (an error found while no file was
 * compiled). The script itself, which nothing includes, gets a file that throws the error when run, so that it is
 * reported as php-src reports it, uncaught, and the request ends as after any fatal error.
 * </ul>
 */
final class SourceFiles {
    /** Where php-src's error says it is when there is no running code to take the location from. */
    private static final SourceLocation NO_ACTIVE_FILE = new SourceLocation("[no active file]", 0);

    private SourceFiles() {
    }

    /** The translated top-level code of the file at canonical (path as the script named it). */
    @TruffleBoundary
    static FileRootNode load(PhpContext context, String path, String canonical) {
        TruffleFile file = context.getEnv().getPublicTruffleFile(canonical);
        long modified;
        long size;
        try {
            modified = file.getLastModifiedTime().toMillis();
            size = file.size();
        } catch (IOException | SecurityException e) {
            throw cannotOpen(path, e);
        }
        boolean shortTags = context.ini().shortOpenTag();
        // the parse's inputs: the file's version, and short_open_tag
        long version = modified * 2 + (shortTags ? 1 : 0);
        // the diagnostics of the parse, if this load made it
        AtomicReference<List<CompilerGlobals.Diagnostic>> parsed = new AtomicReference<>();
        FileRootNode root;
        try {
            root = context.getLanguage().file(canonical, version, size,
                    () -> translate(context.getLanguage(), path, canonical, file, shortTags, parsed));
        } catch (ZendCompileError error) {
            if (SourceLocation.current().equals(SourceLocation.UNKNOWN)) {
                // no PHP code runs: this is the script
                return failing(context.getLanguage(), canonical, error);
            }
            throw thrown(context, canonical, error, true);
        }
        List<CompilerGlobals.Diagnostic> diagnostics = parsed.get();
        if (diagnostics != null) {
            for (CompilerGlobals.Diagnostic diagnostic : diagnostics) {
                ErrorFunctions.raise(context, diagnostic.type(), Str.of(diagnostic.message()),
                        new SourceLocation(canonical, diagnostic.line()));
            }
        }
        return root;
    }

    private static FileRootNode translate(PhpLanguage language, String path, String canonical, TruffleFile file,
                                          boolean shortTags,
                                          AtomicReference<List<CompilerGlobals.Diagnostic>> diagnostics) {
        byte[] script;
        Source source;
        ScriptText text;
        try {
            script = file.readAllBytes();
            text = ScriptText.decode(script);
            source = Source.newBuilder(PhpLanguage.ID, file).content(text.text()).build();
        } catch (IOException | SecurityException e) {
            throw cannotOpen(path, e);
        }
        ZendParser.Result parsed = ZendParser.parse(script, text, shortTags);
        diagnostics.set(parsed.diagnostics());
        return new Translator(language, canonical, source, false).translateFile(parsed.ast());
    }

    private static PhpError cannotOpen(String path, Exception e) {
        return new PhpError("Failed opening '" + path + "' for inclusion (" + e.getMessage() + ")");
    }

    /**
     * The ParseError or CompileError php-src throws for error in the file at canonical: at its line there, or, for
     * one php-src places at the running code, at the include (included true: the statement it leaves) or nowhere.
     */
    @TruffleBoundary
    private static PhpThrowable thrown(PhpContext context, String canonical, ZendCompileError error,
                                       boolean included) {
        if (error.atExecutedLine() && included) {
            // the include's statement is where a PhpError surfaces. The one such error php-src has is the heredoc
            // scan-ahead's "Invalid body indentation level", in ASCII and with no earlier exception (a parse stops
            // at the first), so a message and a class say all of it.
            return new PhpError(error.className(), new String(error.messageBytes(), StandardCharsets.ISO_8859_1));
        }
        return new PhpException(throwable(context, canonical, error));
    }

    private static PhpObject throwable(PhpContext context, String canonical, ZendCompileError error) {
        SourceLocation at = error.atExecutedLine() ? NO_ACTIVE_FILE : new SourceLocation(canonical, error.line());
        PhpObject object = context.getClass(error.className()).instantiate(at, Backtrace.forThrowable());
        object.setProperty("message", Str.of(error.messageBytes()));
        if (error.previous() != null) {
            object.setProperty("previous", throwable(context, canonical, error.previous()));
        }
        return object;
    }

    /** The script's file when it doesn't parse: running it throws the error, which nothing catches. */
    private static FileRootNode failing(PhpLanguage language, String canonical, ZendCompileError error) {
        return new FileRootNode(language, FrameDescriptor.newBuilder().build(), canonical, false,
                new BasicStmts.BlockNode(new StmtNode[0]), new CompileErrorNode(canonical, error), null);
    }

    private static final class CompileErrorNode extends StmtNode {
        private final String canonical;
        private final ZendCompileError error;

        CompileErrorNode(String canonical, ZendCompileError error) {
            this.canonical = canonical;
            this.error = error;
        }

        @Override
        public void execute(VirtualFrame frame) {
            throw thrown(PhpContext.get(this), canonical, error, false);
        }
    }
}
