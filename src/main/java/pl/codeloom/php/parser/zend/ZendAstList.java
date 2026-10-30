/*
 * Hand-written: a port of php-src 8.5.12's zend_ast_list (Zend/zend_ast.h). php-src is under the Zend Engine
 * License 2.00 (its Zend/LICENSE).
 */
package pl.codeloom.php.parser.zend;

/** zend_ast_list: a list kind's node, its children counted (child grows by doubling, as zend_ast_list_add does). */
public final class ZendAstList extends ZendAst {
    public int children;
    /**
     * Where a statement list a '{' opened starts in the source (see {@link ZendParserBase#zend_ast_create_list}):
     * the '{' token's offset, or -1. A function body's list keeps it, so the body can be parsed again on its own.
     */
    int braceOffset = -1;
    /** CG(doc_comment) and the line when the list was opened at braceOffset. */
    byte[] braceDocComment;
    int braceLine;

    ZendAstList(int kind, int lineno) {
        super(kind, 0, lineno, new ZendAst[4]);
    }
}
