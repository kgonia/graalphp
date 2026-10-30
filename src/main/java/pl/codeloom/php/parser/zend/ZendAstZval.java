/*
 * Hand-written: a port of php-src 8.5.12's zend_ast_zval (Zend/zend_ast.h). php-src is under the Zend Engine
 * License 2.00 (its Zend/LICENSE).
 */
package pl.codeloom.php.parser.zend;

import static pl.codeloom.php.parser.zend.derived.ZendConstants.ZEND_AST_ZVAL;

/**
 * zend_ast_zval: a value in the tree. val is a {@link zval}'s value: Long, Double, byte[] (a string's bytes) or
 * null (IS_NULL). Its line (Z_LINENO in php-src) is the node's lineno.
 */
public final class ZendAstZval extends ZendAst {
    public Object val;

    ZendAstZval(Object val, int attr, int lineno) {
        super(ZEND_AST_ZVAL, attr, lineno, null);
        this.val = val;
    }
}
