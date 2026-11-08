# 042: `var_export` doesn't warn about circular references, and hangs on a WordPress value

**State:** Open. Found 2026-11-08 by the WordPress PHPUnit suite. 6 `Tests_Link_GetAdjacentPostLink` tests hang on
it; in the first full run, the tests after them in the same process never ran.

**Symptom 1, reproduced:** the warning is missing, and the output is otherwise PHP's:
```php
$o = new stdClass; $o->self = $o; var_export($o);
// PHP 8.5: Warning: var_export does not handle circular references in ... on line 1
//          (object) array( 'self' => NULL, )
// Truffle PHP: the same output, without the warning
```
The same holds for an array that holds a reference to itself (`$a[] = &$a`).

**Symptom 2, not reduced:** the WordPress test suite's `wp_die` handler (`tests/phpunit/includes/functions.php:254`)
exports each non-scalar `$args` value with `var_export($value, true)`. In those tests a thread dump shows
`Dump.varExport` recursing without end. The value's shape isn't known, since the two simple cycles above
terminate. It may be a cycle through a reference, through an `ArrayObject` or through a closure.

**Fix:** to do. First reduce the WordPress case to a test. Then track what is being exported as php-src does
(`Z_PROTECT_RECURSION` on objects and on referenced arrays), and print the warning.
