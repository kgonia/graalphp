<?php
// php-src's zend_compile_attributes: a name given twice in an attribute's arguments is a compile error ("Duplicate
// named parameter"), unlike a call's, whose duplicate is a run-time Error.
echo "not printed\n";
function f() {
    test(a: 1, a: 2);
}
#[MyAttribute(a: 'A', a: 'A')]
class Test {}
