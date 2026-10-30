/*
 * Hand-written: a port of php-src 8.5.12's zend_ast_decl (Zend/zend_ast.h). php-src is under the Zend Engine
 * License 2.00 (its Zend/LICENSE).
 */
package pl.codeloom.php.parser.zend;

/** zend_ast_decl: a function, method, closure, arrow function, property hook or class declaration. */
public final class ZendAstDecl extends ZendAst {
    public int end_lineno;
    public int flags;
    public byte[] doc_comment;
    public byte[] name;

    ZendAstDecl(int kind, int flags, int start_lineno, int end_lineno, byte[] doc_comment, byte[] name,
                ZendAst[] child) {
        super(kind, 0, start_lineno, child);
        this.end_lineno = end_lineno;
        this.flags = flags;
        this.doc_comment = doc_comment;
        this.name = name;
    }
}
