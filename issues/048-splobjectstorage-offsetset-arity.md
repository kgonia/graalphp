# 048: `SplObjectStorage::offsetSet($object)` requires a second argument

**State:** Open. Found 2026-11-07 (issue 036, item 8), and on 2026-11-08 by the WordPress PHPUnit suite
(`Tests_Filesystem_MoveDir`, 2 tests).

**Symptom:**
```php
$s = new SplObjectStorage; $o = new stdClass; $s->offsetSet($o);
// PHP 8.5: works, and the data is null
// Truffle PHP: ArgumentCountError: Too few arguments to function SplObjectStorage::offsetSet(), 1 passed ... exactly 2 expected
```

**Cause:** the prelude declares `offsetSet($object, $info)` with no default. PHP's signature is
`offsetSet(object $object, mixed $info = null)`.

**Fix:** to do. Add the default. Then check the prelude's other SPL signatures against php-src's stubs (names,
defaults, types), which named arguments and Reflection expose.
