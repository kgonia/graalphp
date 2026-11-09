# 037: Comparing an object with a number raises no notice

**State:** Open. Found 2026-11-08 while writing the destructor fixture `tests/rcaudit/sequence.php` in the
`wordpress-destructors` worktree. Not a destructor matter.

PHP 8.5 converts the object to the number's type for `==`, `!=`, `<`, `<=`, `>`, `>=` and `<=>`
(`compare_function` → `zendi_smart_compare`, `ZEND_TRY_BINARY_OP*_OBJECT_OPERATION`). For a plain object the
conversion fails and raises `E_NOTICE` "Object of class D could not be converted to int" (or "to float"). The
comparison then goes on with 1. Truffle PHP gives the same result but raises no notice.

```php
class D {}
var_dump(new D == 1);   // PHP: Notice ... converted to int, bool(true)
var_dump(1 == new D);   // same
var_dump(new D < 2.5);  // PHP: Notice ... converted to float, bool(true)
var_dump(new D == "1"); // PHP: bool(false), no notice
```

Truffle PHP prints the four results without the notices.

**Fix:** the object-to-number branch of the comparison helpers should raise the notice exactly as the arithmetic
operators' conversion does. This must not apply to `==` against a string, null or bool, where PHP raises no notice.
Then add a smoke case.
