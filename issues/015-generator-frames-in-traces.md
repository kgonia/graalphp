# 015: A delegating generator's frame is missing from traces (gh8289)

**State:** Fixed (2026-11-03, branch `fix-lifetime-generators`)

**Symptom:** php-src's `Zend/tests/generators/gh8289.phpt` passed on the tree and fails on bytecode:
```php
function g() {
    yield from new class(new ArrayIterator([1, -2])) extends IteratorIterator {
        public function key(): mixed { if ($k = parent::key()) { throw new Exception; } return $k; }
    };
}
foreach (g() as $v) {}
// PHP:          #0 file(2): IteratorIterator@anonymous->key()   #1 file(6): g()   #2 {main}
// Truffle PHP:  #0 file(6): IteratorIterator@anonymous->key()   #1 {main}
```
The same for a generator that `yield from`s another generator: the inner one's trace lacked the outer one
(`#0 (8): inner()  #1 (12): outer()` in PHP). And an `ArgumentCountError` raised in a resumed generator's body (a
call with too few arguments) crashed with a `ClassCastException` (`Backtrace.userCaller` cast a continuation root
to `SourceLocation.Code`).

**Cause:** on bytecode, `PhpGenerator.resume` takes an Iterator's values and runs the delegate generator's body
from its own loop, outside any frame of the generators delegating. The tree's `yield from` iterated inside the
body, so the frame was there. php-src's `zend_generator_resume` links the running generator's frame, and
placeholders for the generators delegating to it (`zend_generator_check_placeholder_frame`), before it calls the
Iterator or the body.

**Fix:** `PhpGenerator.linked` runs that step inside one frame, a call of `PhpGenerator.LinkedFrame` (one root
per generator function, `PhpBytecodeRootNode.linkedFrame`), whose arguments are the innermost linked generator's
frame and the location it is suspended at (its `yield from`), and the chain. `SourceLocation.codeRoot`, `codeFrame`
and `callingInstruction` show it to every frame walker as that generator's frame at that line; `Backtrace.here`
unfolds it into every generator of the chain (`PhpGenerator.linkedLevels`), as php-src's placeholder frame is
unfolded only when a trace is built. So a resume costs one call however deep the chain (php-src's
`yield_from_deep_recursion`, 50,000 deep: a frame per generator overflowed the stack). A generator resumed without
delegation (the common case) and an array's values go on as before. `Backtrace.userCaller` maps roots and frames
like `Backtrace.here`.

**Test:** `tests/smoke/generators-trace.php` (gh8289; nested generators; a user Iterator one and two delegations
deep, by foreach and by `Generator::next()`, with `debug_backtrace()`; "passed in ... on line" from a body and
from an Iterator; a chain 20,000 deep).
