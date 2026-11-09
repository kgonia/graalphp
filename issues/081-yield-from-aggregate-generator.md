# 081: `yield from` an IteratorAggregate whose iterator is a generator stops after its first element

**State:** Open. Found 2026-11-09 through `Zend/tests/fibers/gh15108-*` while fixing 031; confirmed on PHP 8.5 and on
the fiber branch (064367a).

**Symptom:**

```php
class It implements IteratorAggregate {
    public function getIterator(): Generator { yield 'foo'; echo "inner resumed\n"; yield 'bar'; }
}
function f() { yield from new It(); }
$g = f(); var_dump($g->current()); $g->next(); var_dump($g->current());
```

| | Output |
|---|---|
| PHP 8.5 | `string(3) "foo"`, `inner resumed`, `string(3) "bar"` |
| Truffle PHP | `string(3) "foo"`, `NULL`: the inner generator is never resumed, and no error shows |

`yield from (new It)->getIterator()` works: a Generator operand is delegated to directly.

**Cause:** not found yet. `PhpGenerator.yieldFrom` takes an IteratorAggregate's iterator (here a Generator object)
down the plain Iterator path (`values`, `nextDelegatedValue`, which calls `next`, `valid`, `current` as methods).
php-src does the same (`zend_user_it_get_new_iterator`, then the generator's own iterator handlers). The step
through `linked(generator, true)` ends the delegation without resuming the inner generator.

**Fix:** to do.

**Test:** none yet (the snippet above); `Zend/tests/fibers/gh15108-001` to 007 depend on it.
