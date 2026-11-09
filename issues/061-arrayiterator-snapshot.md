# 061: `ArrayIterator` iterates a snapshot of keys: `offsetUnset`/`offsetSet` during foreach are missed

**State:** Fixed. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

**Symptom:** (`arrayiterator-unset-during-foreach`)

| Case | PHP 8.5 | Truffle PHP |
|---|---|---|
| at `a`, `offsetUnset('b')` | `ac` | `a` |
| at `b`, `offsetUnset('a')`, `offsetSet('d', 4)` | `abcd` | `abd` |

**Cause:** `ArrayIterator` in `spl.php` (lines 38-168) takes its keys at rewind and walks them by index, so changes
through `offsetUnset` and `offsetSet` shift or miss the position. php-src walks the HashTable with a registered hash
iterator (`zend_hash_iterator_add`), which moves when the element under it is deleted, and appended elements are
reached.

**Fix:** `ArrayIterator` keeps its storage's hash slots and an iterator position over them, as php-src's HashTable and
hash iterator. All changes to the private storage go through its methods, so the slots stay exact:
- an append adds a slot at the end, which the walk reaches;
- a delete leaves a hole, and moves the iterator past it if it was under it (`zend_hash_iterators_update`), so the
  `next()` after that skips one, as in PHP;
- a sort compacts the slots in the new order and leaves the position's number (`zend_hash_sort_ex`);
- when holes are half the slots they are compacted with the iterator moving along (`zend_hash_rehash`), which
  nothing shows.

Probed case by case on PHP 8.5 (`arrayiterator-unset-during-foreach`).

**Test:** `tests/smoke/arrayiterator-unset-during-foreach.php`.
