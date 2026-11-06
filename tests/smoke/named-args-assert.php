<?php
// assert() by name (php-src's zend_compile_assert, zend.assertions = 1 as the reference's php.ini-development): the
// message it generates keeps the names, "assert(assertion: false)"; it appends that message as a positional second
// argument, so naming only the description overwrites it. Truffle PHP compiles assert() out (zend.assertions = -1,
// issue 030): this test waits for it.

function t($label, $call) {
    try {
        echo $label, ': ', json_encode($call()), "\n";
    } catch (Throwable $e) {
        echo $label, ': ', get_class($e), ': ', $e->getMessage(), ' (line ', $e->getLine(), ")\n";
    }
}

function main() {
    t('assert by name', function () {
        assert(assertion: true);
        assert(assertion: false);
    });
    t('assert description by name', fn() => assert(assertion: false, description: 'Description'));
    t('assert description only', fn() => assert(description: 'Description'));
}

main();
