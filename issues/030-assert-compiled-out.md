# 030: assert() is compiled out (zend.assertions = -1), the reference runs it

**State:** Open

**Symptom:** the reference PHP 8.5 runs with `zend.assertions = 1` (php.ini-development's), so `assert(false)` throws
`AssertionError`; Truffle PHP's `assert()` is a built-in returning true (`RuntimeFunctions`: "as in production").
With named arguments (`tests/smoke/named-args-assert.php`, waiting for this):
```php
assert(assertion: false);                 // PHP: AssertionError "assert(assertion: false)"
assert(assertion: false, description: 'D'); // PHP: AssertionError "D"
assert(description: 'D');                 // PHP: Error "Named parameter $description overwrites previous argument"
```

**Cause:** php-src compiles `assert()` specially (`zend_compile_assert`): under `zend.assertions = 1` it evaluates
the assertion, and when the call has one argument it appends the message generated from the AST
(`zend_ast_export`, names kept: "assert(assertion: false)") as a positional second argument, which a named
`description` then overwrites. With `zend.assertions = -1` it emits nothing.

**Fix:** honour `zend.assertions` (1, 0, -1; -1 only from php.ini, as php-src) and `assert.exception`; compile
`assert()` as php-src: the generated message appended positionally (the named-arguments binder then gives the
"overwrites" Error), `AssertionError` or a `Throwable` description thrown, `assert_options()`.
