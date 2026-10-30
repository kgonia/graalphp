package pl.codeloom.php.parser;

/**
 * A function's, method's or closure's body (its "stmts") left unread: {@link Ast#body()} reads it into a tree each
 * time the body is needed, which is when the function is translated (and, for a closure, when the code around it
 * is). A function that is never called then holds only what it takes to read the body again: a byte range of the
 * file's php-ast export ({@link ExportBody}), or a range of the file's source text to parse again
 * (pl.codeloom.php.parser.zend.SourceBody). Either keeps the body of the file as it was included, as php-src keeps
 * a compiled file, whatever happens to the file or its export later.
 */
public abstract class AstBody {
    /** The body as a fresh tree; its own nested bodies are again unread. */
    protected abstract Ast read();
}
