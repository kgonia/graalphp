# 044: `version_compare` refuses the `=` operator

**State:** Open. Found 2026-11-08 by the WordPress PHPUnit suite. PHPUnit 9 calls it with `=` at start-up, and 7
tests fail on it (`Admin_Includes_Plugin_Install_*`).

**Symptom:**
```php
version_compare('1.0', '1.0', '=');
// PHP 8.5: true
// Truffle PHP: ValueError: version_compare(): Argument #3 ($operator) must be a valid comparison operator
```
PHP accepts these operators (php-src `ext/standard/versioning.c`):
- `<`, `lt`;
- `<=`, `le`;
- `>`, `gt`;
- `>=`, `ge`;
- `==`, `=`, `eq`;
- `!=`, `<>`, `ne`.

Only `=` was seen failing. The probe stopped at the first error, so the others are unchecked.

**Fix:** to do. Accept php-src's full list, with a smoke test over every operator.
