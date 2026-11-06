<?php
// php-src's zend_compile_const_expr_args: in a constant expression's `new`, a positional argument after a named one
// is a compile error too.
echo "not printed\n";
class P {
    public function __construct(public $a = 1, public $b = 2) {}
}
function f($p = new P(b: 1, 2)) {
}
