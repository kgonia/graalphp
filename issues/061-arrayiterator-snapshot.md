# 061: `ArrayIterator` iterates a snapshot of keys: `offsetUnset`/`offsetSet` during foreach are missed

**State:** Open. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

**Symptom:** (`arrayiterator-unset-during-foreach`)

| Case | PHP 8.5 | Truffle PHP |
|---|---|---|
| at `a`, `offsetUnset('b')` | `ac` | `a` |
| at `b`, `offsetUnset('a')`, `offsetSet('d', 4)` | `abcd` | `abd` |

**Cause:** `ArrayIterator` in `spl.php` (lines 38-168) takes its keys at rewind and walks them by index, so changes
through `offsetUnset` and `offsetSet` shift or miss the position. php-src walks the HashTable with a registered hash
iterator (`zend_hash_iterator_add`), which moves when the element under it is deleted, and appended elements are
reached.

**Fix:** to do. Walk the array's own order with a position that survives deletes and appends, as foreach by
reference already does over arrays.

**Test:** `tests/smoke/arrayiterator-unset-during-foreach.php`.
