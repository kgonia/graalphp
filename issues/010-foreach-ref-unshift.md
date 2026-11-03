# 010: A foreach by reference loops forever after array_unshift

**State:** Fixed (2026-11-03, branch `fix-lifetime-generators`)

**Symptom:** php-src's `Zend/tests/foreach/foreach_016` (2026-10-30, on 2219744 and after; it passed in the runs of 2026-10-23 and 10-27):
```php
$a = [1,2,3];
foreach ($a as &$v) {
    echo "$v\n";
    if ($v == 2) {
        array_unshift($a, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
// PHP: 1 2 3, then int(11); Truffle PHP: 1 2, then 0 without end
```

**Cause:** `array_unshift` (the built-in, `ArrayFunctions.unshift`) built a new array and stored it in the variable.
The loop's `RefIteration` then saw "another array" and started it at its internal pointer, the new first element,
again and again. php-src builds the new table and swaps it into the *same* `HashTable`, then moves every iterator
on it forward by the number of values (`zend_hash_iterators_advance`). The one-value intrinsic (`PhpArray.prepend`)
shifted in place but didn't move the iterator either. Both also dropped the elements' references
(`$r = &$a[1]; array_unshift($a, 0); $r = 9;` didn't reach `$a[2]`).

**Fix:** `PhpArray.prependAll` rebuilds the elements in the same array with their raw values (references kept,
the nested arrays' holder counts unchanged), resets the internal pointer and calls `RefIteration.advanced`, which
moves the positions (and the copies' positions) into that array as `zend_hash_iterators_advance` does. The
built-in and the intrinsic's slow path use it; the intrinsic's in-place shift is kept for arrays no foreach by
reference walks.

**Test:** `tests/smoke/foreach-ref-unshift.php` (foreach_016, one value, string keys, references, the pointer, a
nested loop over a copy, unshifting the array into itself, the same in a function on bytecode). It looped
forever before the fix.
