# 039: A subclass's property hides its parent's private property in the parent's methods

**State:** Open. Found 2026-11-08 by the WordPress PHPUnit suite. 78 tests fail on it:
- `Tests_Locale` (22): `setlocale(): Argument #1 ($category) must be of type int, string given`;
- `Tests_L10n_wpLocaleSwitcher` (30): `foreach() argument must be of type array|object, string given`;
- `Tests_L10n_DetermineLocale` (26): `foreach() argument must be of type array|object, null given`.

It was already listed as a gap in [gate-2.md](../wiki/gate-2.md) ("a private property declared again by a subclass
shares one slot with the parent's"). This is its issue.

**Symptom:** PHPUnit's `TestCase` has `private $locale = []`, and `tearDown` restores the locale with
`foreach ($this->locale ...)`. WordPress's test classes declare their own `protected $locale`. In PHP these are two
properties, and the parent's methods see the parent's private one. Here they share a slot:
```php
abstract class Base { private $locale = []; function restore() { return gettype($this->locale); } }
class Child extends Base { protected $locale = 'x'; }
var_dump((new Child)->restore());
// PHP 8.5: string(5) "array"
// Truffle PHP: string(6) "string"
```

**Cause:** property slots are laid out by name, so a private property and a subclass's property of the same name
are one slot. php-src keeps the parent's private property in its own slot, and a lookup from the parent's scope
finds it (`zend_get_property_offset` with the scope).

**Fix:** to do. Give a private property its own slot when a subclass declares the same name, and resolve
`$this->name` by the code's scope, as php-src does. Check the related cases too:
- `get_object_vars`, `(array)` casts, `var_dump`, `print_r`, `serialize` and `clone` show both properties (the
  parent's as `\0Base\0locale`);
- static properties.
