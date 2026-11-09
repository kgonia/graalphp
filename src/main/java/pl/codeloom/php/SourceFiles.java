package pl.codeloom.php;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.source.Source;
import pl.codeloom.php.builtins.ErrorFunctions;
import com.oracle.truffle.api.nodes.RootNode;
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
import pl.codeloom.php.runtime.Phases;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;
import com.oracle.truffle.api.strings.TruffleString;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
public final class SourceFiles {
    /** Where php-src's error says it is when there is no running code to take the location from. */
    private static final SourceLocation NO_ACTIVE_FILE = new SourceLocation("[no active file]", 0);

    private SourceFiles() {
    }

    /** The translated top-level code of the file at canonical (path as the script named it). */
    @TruffleBoundary
    static RootNode load(PhpContext context, String path, String canonical) {
        // the lookup and a translation it makes; the file's diagnostics are its code's to raise (CompileDiagnostics)
        RootNode root;
        int outer = Phases.enter(Phases.INCLUDE);
        try {
            root = lookUp(context, path, canonical);
        } finally {
            Phases.exit(outer);
        }
        return root;
    }

    /**
     * eval(code) at location (zend_eval_stringl): the code compiled as a file named "FILE(LINE) : eval()'d code"
     * that starts in PHP mode, every time (php-src caches no eval); a parse error is php-src's ParseError at the
     * eval, the parse's warnings raised in the eval'd code. Empty code compiles to nothing (compile_string): null.
     */
    @TruffleBoundary
    public static RootNode eval(PhpContext context, SourceLocation location, TruffleString code) {
        String name = location.file() + "(" + location.line() + ") : eval()'d code";
        byte[] body = Str.bytes(code);
        if (body.length == 0) {
            return null;
        }
        byte[] script = new byte[6 + body.length];
        System.arraycopy("<?php ".getBytes(StandardCharsets.ISO_8859_1), 0, script, 0, 6);
        System.arraycopy(body, 0, script, 6, body.length);
        ScriptText text = ScriptText.decode(script);
        Source source = Source.newBuilder(PhpLanguage.ID, text.text(), name).build();
        boolean shortTags = context.ini().shortOpenTag();
        ZendParser.Result parsed;
        int outer = Phases.enter(Phases.PARSE);
        try {
            parsed = ZendParser.parse(script, text, shortTags);
        } catch (ZendCompileError error) {
            throw thrown(context, name, error, true);
        } finally {
            Phases.exit(outer);
        }
        for (CompilerGlobals.Diagnostic diagnostic : parsed.diagnostics()) {
            ErrorFunctions.raise(context, diagnostic.type(), Str.of(diagnostic.message()),
                    new SourceLocation(name, diagnostic.line()));
        }
        outer = Phases.enter(Phases.FILE_TRANSLATE);
        try {
            return new Translator(context.getLanguage(), name, source, false).translateEval(parsed.ast(),
                    () -> ZendParser.parse(script, text, shortTags).ast());
        } finally {
            Phases.exit(outer);
        }
    }

    /** The file's translation: the cached one for its version, else a new one. */
    private static RootNode lookUp(PhpContext context, String path, String canonical) {
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
        try {
            return context.getLanguage().file(canonical, version, size,
                    () -> translate(context.getLanguage(), path, canonical, file, shortTags));
        } catch (ZendCompileError error) {
            if (SourceLocation.current().equals(SourceLocation.UNKNOWN)) {
                // no PHP code runs: this is the script
                return failing(context.getLanguage(), canonical, error);
            }
            throw thrown(context, canonical, error, true);
        }
    }

    private static RootNode translate(PhpLanguage language, String path, String canonical, TruffleFile file,
                                          boolean shortTags) {
        byte[] script;
        Source source;
        ScriptText text;
        int outer = Phases.enter(Phases.READ);
        try {
            script = file.readAllBytes();
            text = ScriptText.decode(script);
            source = Source.newBuilder(PhpLanguage.ID, file).content(text.text()).build();
        } catch (IOException | SecurityException e) {
            throw cannotOpen(path, e);
        } finally {
            Phases.exit(outer);
        }
        Phases.fileRead(script.length);
        ZendParser.Result parsed;
        outer = Phases.enter(Phases.PARSE);
        try {
            parsed = ZendParser.parse(script, text, shortTags);
        } finally {
            Phases.exit(outer);
        }
        outer = Phases.enter(Phases.FILE_TRANSLATE);
        try {
            return new Translator(language, canonical, source, false).translateFile(parsed.ast(),
                    () -> ZendParser.parse(text.text().getBytes(text.charset()), text, shortTags).ast(),
                    parsed.diagnostics());
        } finally {
            Phases.exit(outer);
        }
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
    private static RootNode failing(PhpLanguage language, String canonical, ZendCompileError error) {
        return new RootNode(language) {
            @Override
            public Object execute(VirtualFrame frame) {
                throw thrown(PhpContext.get(this), canonical, error, false);
            }
        };
    }
}
