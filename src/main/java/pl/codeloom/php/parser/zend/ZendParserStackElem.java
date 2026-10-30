/*
 * Hand-written: php-src 8.5.12's zend_parser_stack_elem (Zend/zend_compile.h). php-src is under the Zend Engine
 * License 2.00 (its Zend/LICENSE).
 */
package pl.codeloom.php.parser.zend;

/**
 * zend_parser_stack_elem as the scanner fills it in for the parser: a token's value, a node (ast, for tokens with
 * a value) or the offset of its text (ident, for keywords, which the parser may take as names). value is the one
 * set last, as the union's member the parser reads.
 */
public final class ZendParserStackElem {
    public ZendAst ast;
    public int ident;
    public Object value;
}
