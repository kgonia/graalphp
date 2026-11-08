# 038: Writing a string offset (`$s[i] = 'c'`) throws "Cannot use a scalar value as an array"

**State:** Open. Found 2026-11-08 by the WordPress PHPUnit suite (`tests/wp-phpunit`). 7 tests fail on it, among
them `Tests_Secrets_WPSecretsCipher` and the keyring conformance tests.

**Symptom:** any write to a string offset fails, at the top level, in a function and in a closure:
```php
$f = 'abcdef';
$f[2] = 'X';
echo $f;
// PHP 8.5: abXdef
// Truffle PHP: Fatal error: Uncaught Error: Cannot use a scalar value as an array
```
WordPress's tests do it in `class-wp-secrets-keyring-conformance.php:101` (`$flipped[$flip_at] = chr(...)`).

**Cause:** not diagnosed. The element write paths (`ElementChain`, `Places`, `Variables`) treat any scalar
container as an error. The string case of php-src's `zend_assign_to_string_offset` isn't implemented, and none of
its messages exist in the runtime:
- "Cannot assign an empty string to a string offset";
- "Only the first byte will be assigned to the string offset";
- "Illegal string offset" on a write;
- padding with spaces past the end.

**Fix:** to do. Port `zend_assign_to_string_offset`:
- the offset's conversion and its warnings;
- negative offsets;
- padding past the end;
- only the first byte assigned;
- the assignment's value, which is the byte assigned.

`$s[i] .= 'x'` and `$s[] = 'x'` are Errors in PHP; they need the right message. The string's copy-on-write must
hold.
