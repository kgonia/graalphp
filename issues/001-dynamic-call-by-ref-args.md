# 001: Dynamic calls don't pass arguments by reference

**State:** Fixed (branch `fix-objects-calls`, commit "Dynamic calls pass by-reference arguments...")

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

On bytecode the dynamic calls already resolved the callee first (`ResolveCallable`, `TargetFunction`); only `$closure->__invoke($a)` passed values (`ResolveMethod` gave every Closure method `BY_VALUE`). The tree, which still runs file top-level code, had all of the above.

Found with it: a value that is neither a variable nor a call (a literal, `$a = 1`, `$a++`, a cast, `clone`, `$GLOBALS`, a built-in compiled in place such as `strlen()`) sent to a by-reference parameter is php-src's Error "f(): Argument #1 ($x) could not be passed by reference" (SEND_VAL_EX), raised once the value is evaluated and before the later arguments are; both interpreters passed it as a value. `new`, include and yield (IS_VAR results) go as a call's result does (SEND_VAR_NO_REF: the notice "Only variables should be passed by reference"); the tree passed them without it. `f($a = &$b)` passes the reference ASSIGN_REF leaves.

**Fix:** the tree's `$f(args)`, `$obj->$name(args)` and `$closure->__invoke(args)` resolve the target first (`Callables.resolveCall`) and build the arguments against its function (`ArgumentsNode.build`), by value for `__call`. On bytecode `ResolveMethod` gives a Closure's `__invoke` its function and `CallMethod` calls it with the closure's `$this` and scope. A value argument goes through `SendValue` (bytecode) or `ArgumentsNode.send` (tree), which raise SEND_VAL_EX's Error for a by-reference parameter; `$this` stays a plain value (SEND_VAR_EX of FETCH_THIS); `$a = &$b` is the assignment, then `$a` sent as a variable. Test: `tests/smoke/dynamic-call-by-ref.php`.

Simplified (`ponytail:` comments): a reference assigned to an element or a property (`f($a[0] = &$b)`) and `f($a = &g())` are sent as values, unchecked.
