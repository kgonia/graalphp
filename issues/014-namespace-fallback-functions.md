# 014: An unqualified call in a namespace: built-ins compiled in place ignore the namespace's function, and the fallback isn't kept per request

**State:** Fixed

**Symptom:**
```php
namespace X;
function strlen($s) { return 'mine'; }
var_dump(strlen('a'));   // PHP: string(4) "mine"; Truffle PHP: int(1)
```
And on a shared engine (a server, `--contexts=3`), a call that fell back to the global function in one request
calls a namespaced function declared later in another request, where php-src keeps the function its runtime cache
slot found first in each request:
```php
namespace L;
function probe() { return str_repeat('ab', 2); }
var_dump(probe());
if (true) { function str_repeat($s, $n) { return 'declared late'; } }
var_dump(probe());       // PHP: "abab" (INIT_NS_FCALL_BY_NAME's cache slot); Truffle PHP's third context: "declared late"
```
Found while pinning `defined()` for partial evaluation round 4 (wiki/pe-round4.md); `defined()` itself is not
compiled in place for an unqualified call in a namespace, as php-src.

**Cause:** `Translator.call` gives the intrinsics (`Intrinsics.create`) the global name even when the call is
unqualified in a namespace (`resolved[1] != null`), so they bind to the built-in at translation. And
`FunctionCallNode.Lookup.perContext` looks the namespaced name up first on every call, with no memory per request
of what the site found.

**Fix:**
- The function a call by an unqualified name in a namespace finds is kept for the request
  (`PhpContext.fallbackFunction`, by a site number from `PhpLanguage.nextFallbackSite`; `FunctionCallNode.lookUp`), for
  calls on both interpreters and for `f(...)`. php-src's cache slot (INIT_NS_FCALL_BY_NAME's) is per call, but the
  reference runs with opcache, whose optimizer shares one slot between the calls of an op_array that name the same
  function (compact_literals): the site number is per name in each function (`Translator.fallbackSite`,
  `BytecodeTranslator.fallbackSite`), so two calls of `strlen($s)` in one function see the same function.
- The built-ins compiled in place for such a call (the tree's intrinsics, and `compact()`, `extract()`,
  `func_get_args()`, `call_user_func()`... on both interpreters) run behind a test that the call finds the global
  function (`FunctionCallNode.FallbackNode`; on bytecode `FallsBack` choosing between the in-place code and the
  call); else the namespace's function is called.

Test: `tests/smoke/namespace-fallback.php`.

Not copied, one more effect of the reference's opcache: its optimizer folds a call of a pure built-in with constant
arguments (`strlen('abc')`) at compile time when the file doesn't declare such a function in the namespace
unconditionally (SCCP), whatever the namespace declares later; the test avoids constant arguments there. Code that
declares a namespace's own `strlen` conditionally after calling it with constants is unlikely enough to leave.
