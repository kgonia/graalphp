/*
 * Hand-written. php-src is under the Zend Engine License 2.00 (its Zend/LICENSE).
 */
package pl.codeloom.php.parser.zend;

import java.nio.charset.StandardCharsets;

/**
 * A ParseError or CompileError php-src's parser throws: the class, the message (worded as php-src words it, as
 * bytes, since a syntax error quotes the source) and where: the line being compiled (CG(zend_lineno), as php-src's
 * exception takes zend_get_compiled_lineno()), or, for one thrown while no file was being compiled (during the
 * heredoc scan-ahead), the code running at the time, as php-src gives it then.
 */
public final class ZendCompileError extends RuntimeException {
    private final boolean parseError;
    private final byte[] message;
    private final int line;
    private final boolean atExecutedLine;
    private final ZendCompileError previous;

    ZendCompileError(boolean parseError, byte[] message, int line, boolean atExecutedLine,
                     ZendCompileError previous) {
        super(new String(message, StandardCharsets.UTF_8), null, false, false);
        this.parseError = parseError;
        this.message = message;
        this.line = line;
        this.atExecutedLine = atExecutedLine;
        this.previous = previous;
    }

    /** True for a ParseError, false for a CompileError (which ParseError extends in PHP). */
    public boolean isParseError() {
        return parseError;
    }

    /** "ParseError" or "CompileError". */
    public String className() {
        return parseError ? "ParseError" : "CompileError";
    }

    /** The message's bytes, as PHP has them. */
    public byte[] messageBytes() {
        return message.clone();
    }

    /** The line in the file being compiled; meaningless when {@link #atExecutedLine()}. */
    public int line() {
        return line;
    }

    /**
     * True when php-src gives the error the file and line of the code running (an include's, or none at the top
     * level) rather than a line of the file being compiled.
     */
    public boolean atExecutedLine() {
        return atExecutedLine;
    }

    /** The exception pending when this one was thrown, or null. */
    public ZendCompileError previous() {
        return previous;
    }
}
