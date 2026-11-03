<?php
// php-src's zend_compile_args: a positional argument after a named one is a compile error, the whole file's.
echo "not printed\n";
function f() {
    test(a: 1, 2);
}
