# 001: Dynamic calls don't pass arguments by reference

**State:** Open

**Symptom:** a by-reference parameter of a function called through a value gets a copy:
```php
$f = function (&$x) { $x = 1; };
$a = 0;
$f($a);          // PHP: $a is 1; Truffle PHP: 0
$name = 'g';     // function g(&$x) { $x = 2; }
$name($a);       // PHP: $a is 2; Truffle PHP: unchanged
```
The same holds for `$obj->$method($a)`, `[$obj, 'm']($a)` and `$closure->__invoke($a)`.

**Cause:** `CallableCallNode`, `DynamicMethodCallNode` and the closure path of `MethodCallNode` evaluate their arguments with `ArgumentsNode.values()` before `CallableDispatchNode` resolves the callee, so no argument is made a reference (php-src's INIT_DYNAMIC_CALL resolves the callee first and SEND_VAR_EX then looks at its parameters). Only `call_user_func_array` passes the references an array holds.

**Fix:** resolve the callable before the arguments and build them with `ArgumentsNode.build()` against the resolved function, as the direct call nodes do. Found while implementing return by reference (`tests/smoke/return-ref.php` avoids by-reference parameters on closures for now).
