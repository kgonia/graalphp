# 004: A resumed generator evaluates the yielding statement again

**State:** Fixed for every generator whose body the bytecode translator covers (2026-11-02, branch
`bytecode-generators`); open for the few that stay on the tree (below).

**Symptom:** side effects in a statement before its `yield` happen twice, and some statements can't hold a yield:
```php
function g() { $a = 0; $x = f($a++, yield 1); var_dump($a); }  // PHP: int(1); Truffle PHP: int(2)
function h() { $log[] = yield; }                                 // the array target is evaluated twice
```
`yield` inside `switch`, and a foreach by reference or into `list()` around a yield, are rejected as unsupported (`Translator.resumableStatement`).

**Cause:** `GeneratorNodes` resumes by running the body again on the same frame. Each statement on the path to the yield has a resumable variant that skips what already ran, but expressions have no such state: the statement that yielded is evaluated from its start, and the yield, seeing its own slot set, returns the sent value. php-src keeps the suspended `zend_execute_data` with its temporaries and resumes at the next opcode.

**Fix:** generators run on the Truffle Bytecode DSL's continuations (`wiki/bytecode-dsl.md`, "S3").
- A generator function goes to bytecode whenever `BytecodeTranslator` covers its body, in the default configuration too (`Translator.translateBody`: `language.bytecode() || generator`).
- `GeneratorStart`, a custom yield, makes the Generator once the parameters are bound. `YieldValue` and `YieldFrom` suspend with the bytecode index and the operand stack kept, which is php-src's model: a resume continues after the yield.
- `PhpGenerator` follows `zend_generators.c`: the delegation tree of `yield from`, send targets, throw, rewind and the closed-generator errors, and destruction running `finally` blocks.
- Tests: `tests/smoke/generators-resume.php` (issue 004's cases, yields in every expression position, `switch`, `match`, foreach by reference and `list()` around a yield) and `tests/smoke/generators-api.php`. Both failed on the tree and pass on bytecode.

**Still on the tree, so still resumed by re-evaluation:** generators that use a construct the bytecode translator doesn't cover. `-Dphp.bytecode.report=all` lists them with the construct. In the inventory (`wiki/bytecode-dsl.md`, S3) these are:
- an anonymous class (`new class ... {}`): 3 of php-src's generator tests;
- a superglobal (`$_GET`) as an argument: 1;
- an assignment to a property of an expression (`f()->x = v`): 1.

The same goes for these constructs anywhere a generator contains them: argument unpacking `f(...$a)`, functions or classes declared inside the body, `compact`/`extract`/`get_defined_vars`, variable variables, `include` in the body, `goto`, `static::$name()`. Named arguments, variable variables and `eval` keep a generator on neither interpreter: the tree can't translate them either.
