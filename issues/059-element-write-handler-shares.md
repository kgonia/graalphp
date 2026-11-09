# 059: An element write lands in an array the error handler shared during it

**State:** Fixed. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

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

**Fix:** as `zend_undefined_index_write`, the "Undefined array key" of a read-modify-write is surrounded by a look at
the array's holder count (`ElementChain.undefinedForWrite`). If the handler changed it (shared the array, or
released it), the write is dropped:
- a container on the way is a fresh array nothing holds, as php-src's null result, so the rest of the chain warns
  as PHP's does and writes into nothing;
- the last element gives `.=`'s value null, and `++`/`--` step a null.

The last element's read is one lookup, as before.

**Test:** `tests/smoke/element-write-handler-shares.php`.
