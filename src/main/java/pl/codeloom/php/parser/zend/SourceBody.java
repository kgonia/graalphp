/*
 * Hand-written.
 */
package pl.codeloom.php.parser.zend;

import pl.codeloom.php.parser.Ast;
import pl.codeloom.php.parser.AstBody;

/**
 * A function's, method's or closure's body left unparsed: the range of the script's text from its '{' to its '}',
 * and what the parser's state was at the '{' (the line, CG(doc_comment), the next decl id). Reading it parses that
 * range again ({@link ZendParser#parseBody}), which gives the tree the whole file's parse gives for the body.
 */
final class SourceBody extends AstBody {
    private final ScriptText text;
    private final int charFrom;
    private final int charTo;
    private final int line;
    private final byte[] docComment;
    private final long firstDeclId;
    private final int declKind;
    private final boolean shortTags;

    SourceBody(ScriptText text, int charFrom, int charTo, int line, byte[] docComment, long firstDeclId,
               int declKind, boolean shortTags) {
        this.text = text;
        this.charFrom = charFrom;
        this.charTo = charTo;
        this.line = line;
        this.docComment = docComment;
        this.firstDeclId = firstDeclId;
        this.declKind = declKind;
        this.shortTags = shortTags;
    }

    @Override
    protected Ast read() {
        return ZendParser.parseBody(text, charFrom, charTo, line, docComment, firstDeclId, declKind, shortTags);
    }

    @Override
    public String toString() {
        return "SourceBody[" + charFrom + ".." + charTo + " line " + line + "]";
    }
}
