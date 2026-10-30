package pl.codeloom.php.parser;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * A body left unread in the file's php-ast export: its byte range in the export, whose bytes all the file's bodies
 * share. A function that is never called then holds bytes, about a sixth of its tree.
 */
final class ExportBody extends AstBody {
    private final byte[] json;
    private final int offset;
    private final int length;

    ExportBody(byte[] json, int offset, int length) {
        this.json = json;
        this.offset = offset;
        this.length = length;
    }

    @Override
    protected Ast read() {
        try {
            return (Ast) AstReader.read(json, offset, length);
        } catch (IOException e) {
            // the bytes were read as JSON when the file was, so this is a bug, not a missing export
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String toString() {
        return "ExportBody[" + offset + "+" + length + "]";
    }
}
