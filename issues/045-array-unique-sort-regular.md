# 045: `array_unique(..., SORT_REGULAR)` on arrays of arrays warns and removes the wrong elements

**State:** Open. Found 2026-11-08 by the WordPress PHPUnit suite, in the data providers of the HTML API's tests.

**Symptom:**
```php
var_dump(count(array_unique([['#x', 'a'], ['#X', 'A'], ['#x', 'a']], SORT_REGULAR)));
// PHP 8.5: int(2)
// Truffle PHP: Warning: Array to string conversion (3 times), then int(1)
```

**Cause:** not diagnosed. SORT_REGULAR seems to convert the elements to strings, as SORT_STRING does, instead of
comparing them with `zend_compare` (`php_array_data_compare_unstable_i`).

**Fix:** to do. For SORT_REGULAR, use PHP's standard comparison, as php-src does: sort with it, then drop each
element equal to its neighbour, keeping the earliest by position.
