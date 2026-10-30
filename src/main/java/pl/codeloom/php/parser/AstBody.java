package pl.codeloom.php.parser;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * A function's, method's or closure's body (its "stmts") as left unread in the file's export: its byte range in the
 * export, whose bytes all the file's bodies share. {@link Ast#body()} reads it into a tree each time the body is
 * needed, which is when the function is translated (and, for a closure, when the code around it is). A function
 * that is never called then holds bytes, about a sixth of its tree. Holding the bytes rather than the export's path
 * keeps the body of the file as it was included, as php-src keeps a compiled file, whatever happens to the export
 * later.
 */
public final class AstBody {
    private final byte[] json;
    private final int offset;
    private final int length;

    AstBody(byte[] json, int offset, int length) {
        this.json = json;
        this.offset = offset;
        this.length = length;
    }

    /** The body as a fresh tree; its own nested bodies are again unread. */
    Ast read() {
        try {
            return (Ast) AstReader.read(json, offset, length);
        } catch (IOException e) {
            // the bytes were read as JSON when the file was, so this is a bug, not a missing export
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString() {
        return "AstBody[" + offset + "+" + length + "]";
    }
}
