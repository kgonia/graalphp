# 004: A resumed generator evaluates the yielding statement again

**State:** Open

**Symptom:** side effects in a statement before its `yield` happen twice, and some statements can't hold a yield:
```php
function g() { $a = 0; $x = f($a++, yield 1); var_dump($a); }  // PHP: int(1); Truffle PHP: int(2)
function h() { $log[] = yield; }                                 // the array target is evaluated twice
```
`yield` inside `switch`, and a foreach by reference or into `list()` around a yield, are rejected as unsupported (`Translator.resumableStatement`).

**Cause:** `GeneratorNodes` resumes by running the body again on the same frame. Each statement on the path to the yield has a resumable variant that skips what already ran, but expressions have no such state: the statement that yielded is evaluated from its start, and the yield, seeing its own slot set, returns the sent value. php-src keeps the suspended `zend_execute_data` with its temporaries and resumes at the next opcode.

**Fix:** to do. Either the Truffle Bytecode DSL's continuations (`@GenerateBytecode(enableYield = true)`: the yield keeps the bytecode index and the operand stack, which is php-src's model), which needs the interpreter on the DSL; or, in the tree interpreter, spilling every operand already evaluated in an expression that contains a yield to hidden slots.
