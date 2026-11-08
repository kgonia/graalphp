# 066: `array_walk` keeps walking the array it started with after the callback replaces it

**State:** Open. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

**Symptom:** `array_walk($a, ...)` whose callback assigns `$a = ['x' => 1]` at the first key (`array-walk-reassign`):

| | Keys visited |
|---|---|
| PHP 8.5 | `0x`: it goes on with the new array |
| Truffle PHP | `012` |

**Cause:** `ArrayFunctions.java:194-219` reads the target's array once and iterates it. php-src's `php_array_walk`
keeps a hash iterator on the referenced variable's current array. After each callback, it takes the array again
from the reference and goes on from the iterator's position in it.

**Fix:** to do. After each callback, take the array again from the reference, with a position that survives the
change, as php-src does.

**Test:** `tests/smoke/array-walk-reassign.php`.
