# 010: A foreach by reference loops forever after array_unshift

**State:** Open

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

**Cause:** to find. php-src moves a by-reference foreach's iterator with the element it stands on when `array_unshift` renumbers the array (`zend_hash_iterators_update`); ours appears to restart from the new first element.

**Fix:** to do.
