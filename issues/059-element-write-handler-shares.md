# 059: An element write lands in an array the error handler shared during it

**State:** Open. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

**Symptom:** `$a['y'] .= 'z'` on a missing key, with an error handler that copies `$a` to `$c`
(`element-write-handler-shares`):

| | Result |
|---|---|
| PHP 8.5 | the write is dropped, and neither `$a` nor `$c` has `y` |
| Truffle PHP | both have `y => "z"` |

**Cause:** the element place protocol (`ElementChain.java:38-115`) separates the array, then raises "Undefined array
key", then writes into the storage it separated. The handler's copy shares that storage, so it gets the write too.

php-src's `zend_undefined_index_write` and `zend_undefined_offset_write` take a reference to the array across the
warning. They drop the write if the array was released or changed (`GC_DELREF(ht) != 1`).

**Fix:** to do. After a warning that runs user code, check whether the array is still unshared and still the
variable's. If not, drop the write as php-src does.

**Test:** `tests/smoke/element-write-handler-shares.php`.
