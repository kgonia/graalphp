/*
 * Hand-written: php-src's zval, as the parser uses it (Zend/zend_types.h). php-src is under the Zend Engine
 * License 2.00 (its Zend/LICENSE).
 */
package pl.codeloom.php.parser.zend;

/**
 * A zval as the scanner and the grammar's actions use one: a holder a function fills in (zend_lex_tstring's
 * out-parameter, a token's value). Named as php-src's type, since the grammar's actions declare it so
 * ("zval zv;"). The value is Long, Double, byte[] (a string), null (IS_NULL) or {@link #UNDEF}.
 */
public final class zval {
    /** IS_UNDEF: no value yet. */
    public static final Object UNDEF = new Object() {
        @Override
        public String toString() {
            return "UNDEF";
        }
    };

    public Object value = UNDEF;
}
