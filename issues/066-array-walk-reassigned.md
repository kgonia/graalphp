# 066: `array_walk` keeps walking the array it started with after the callback replaces it

**State:** Fixed. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

**Symptom:** `array_walk($a, ...)` whose callback assigns `$a = ['x' => 1]` at the first key (`array-walk-reassign`):

| | Keys visited |
|---|---|
| PHP 8.5 | `0x`: it goes on with the new array |
| Truffle PHP | `012` |

**Cause:** `ArrayFunctions.java:194-219` reads the target's array once and iterates it. php-src's `php_array_walk`
keeps a hash iterator on the referenced variable's current array. After each callback, it takes the array again
from the reference and goes on from the iterator's position in it.

**Fix:** `array_walk` walks with a `RefIteration`, the hash iterator a foreach by reference has, as
`php_array_walk` does:
- it moves past each element before the element's callback runs;
- after the callback it takes the array from the reference again, the same one going on from the iterator, a copy
  from the copy's position, another array from its internal pointer;
- a value that is no longer an array is the TypeError "Iterated value is no longer an array or object".

`array_walk_recursive` walks each nested array so in turn, through the element's reference, under the array's
recursion mark (the context's `RecursionGuard`): "Recursion detected" for an array inside itself, the TypeError when
the element no longer holds an array, and the mark kept, as php-src's apply count, when the element was given another
array meanwhile.

**Test:** `tests/smoke/array-walk-reassign.php`.
