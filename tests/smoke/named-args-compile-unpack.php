<?php
// php-src's zend_compile_args: argument unpacking after named arguments is a compile error (unpacking first, then
// names, is allowed).
echo "not printed\n";
function f() {
    test(...[1], a: 2);
    test(a: 42, ...[]);
}
