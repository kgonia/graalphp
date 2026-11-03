# 014: An unqualified call in a namespace: built-ins compiled in place ignore the namespace's function, and the fallback isn't kept per request

**State:** Open

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

**Fix:** to do: an unqualified call in a namespace takes an intrinsic only behind a test that the namespace
declares no such function (as the fallback lookup); the fallback's result kept per context and site.
