/*
 * Hand-written: a port of php-src 8.5.12's zend_ast structs (Zend/zend_ast.h). php-src is under the Zend Engine
 * License 2.00 (its Zend/LICENSE).
 */
package pl.codeloom.php.parser.zend;

/**
 * A node of php-src's AST as the parser builds it (zend_ast): kind, attr, lineno and children, under php-src's
 * names, since the grammar's actions use them as php-src's do. A list node ({@link ZendAstList}), a value
 * ({@link ZendAstZval}) and a declaration ({@link ZendAstDecl}) are subclasses, as php-src's structs that share
 * zend_ast's head. {@link PhpAst} turns the tree into the shape php-ast exports.
 */
public class ZendAst {
    public int kind;
    public int attr;
    /** The line; a declaration's start line (zend_ast_decl.start_lineno shares the field). */
    public int lineno;
    public ZendAst[] child;

    ZendAst(int kind, int attr, int lineno, ZendAst[] child) {
        this.kind = kind;
        this.attr = attr;
        this.lineno = lineno;
        this.child = child;
    }

    @Override
    public String toString() {
        return kind + "@" + lineno;
    }
}
