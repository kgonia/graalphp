# 052: `$r[] = $a`, with `$r` a reference to `$a`, appends a copy instead of the array itself

**State:** Open. Found 2026-11-09 by the destructors fuzzer (seed 1000, `p1741.php`: an object the array holds is
destroyed at `@21` instead of at the end). On main too, not a destructors change.

**Symptom:**
```php
$a = [1]; $r = &$a; $r[] = $a; var_dump($a);
// PHP 8.5: array(2) { [0]=> int(1) [1]=> *RECURSION* }
// Truffle PHP: array(2) { [0]=> int(1) [1]=> array(1) { [0]=> int(1) } }
```
Without the reference (`$b = [1]; $b[] = $b;`) both give the copy.

**Cause:** php-src's ZEND_ASSIGN_DIM fetches the container for writing first. The reference holds the array once,
so it isn't separated. Then the value (`$a`, the same array) is copied in with its count raised, so the array holds
itself: a cycle that lives until the cycle collector or the shutdown. Our write reads the value first and separates
the container because the value holds it too.

**Fix:** to do. Fetch the container for writing before taking the value's count, as ZEND_ASSIGN_DIM does, wherever
the container is a reference (or a variable the value aliases). The cycle also needs the cycle collector to free its
objects at the right time (issue 005).
