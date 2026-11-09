# 093: A server's requests look names up by hash and declare everything again: ~12% of its CPU

**State:** Open, in part fixed (below). Found 2026-11-09 on `fastcgi` by a JFR recording of FastCgiMain under Gate
2's load (`native.sh diag` with `DIAG_OPTS=-XX:StartFlightRecording=delay=720s,duration=300s,settings=profile`: 8
users through nginx, the recording after a 12-minute warm-up, 73179 execution samples). Graal keeps the debug info
of Truffle's compiled code, so the samples name the interpreter's Java methods that compiled PHP is made of.

**What happens:** with a context per request, the shared code's caches that a single context keeps as constants
(`singleContext()` assumptions) fall back to lookups by name, and every request declares the files' functions and
links their classes again. Share of the samples whose stack (12 frames) holds the code, a lower bound:

| what | share | where |
|---|---|---|
| `global $x` | 4.3% | GlobalRefNode's uncached specialization: `PhpContext.globalRef`'s HashMap.computeIfAbsent |
| class declarations | 2.5%+ | `DeclareNodes$EarlyClassNode.bind` → `ClassTemplate.instantiate`/`link`: a new PhpClass, its methods and checks, `Str.lower` of names |
| string callbacks | 2.3% | `CallableDispatchNode`'s generic path (a hook's site meets more names than its cache): `Callables.resolve` → `Str.name`, `Str.lower`, `findFunction` |
| function declarations | 1.9% | `DeclareFunctionNode.declare`: `Str.lower` twice, the context's and the language's maps |
| class constants | 1.4% | `ClassConstant.perCall`: `hasConstant`, `canAccessConstant`, `getConstant`, three HashMap lookups |
| PCRE's cache | 0.9% | `PcreFunctions.compile`: the pattern's HashMap lookup per call |
| `static $x` | 0.7% | `StaticBind` → `PhpContext.findStaticVariable` |
| method visibility | 0.5% | `PhpClass.accessibleMethod` → `declaringClass` → `Str.lower` |

Beside them: `PhpArray$Hash.find` 5.9% (most from ElementNodes' slot cache misses: a hash lookup php-src makes
too), the arrays' holder counts 4.5%, JSON 1.7% (theme.json decoded per request), PCRE 3.3%. Not a cost: the
85845 Java exceptions (NoSuchFileException from `file_exists()` of missing files, JsonError) are 0.03% of the
samples.

**Fixed:**
- `global $x`: 1ff0501, a per-context `PhpRef[]` by the name's language id.
- function declarations: dc4c494, the PhpFunction's lowercase name and id made once, the check by slot.

**To do**, in the order of the table:
- class declarations: link a class's language-level parts once per ClassKey (methods table, interfaces, checks),
  per context only what is the context's (statics, constants' values, the PhpClass itself) — php-src's opcache
  keeps linked classes immutable in shared memory and a request only inserts them into its class table.
- string callbacks: the generic path resolves a TruffleString name through one language-level map (name to
  function id), then the context's slot.
- class constants: one lookup (the value, the access check only for a class with restricted constants), then a
  site's cache by ClassKey of the constant's index in a per-class array.
- PCRE: a site with a constant pattern keeps its compiled pattern (language-level, immutable).
- `static $x`, method visibility: as `global`.

**Test:** per fix, a smoke test of its edge cases in one context and three (tests/smoke/run.sh), the same JFR
profile (the share gone), and `native.sh load` A/B with a 12-minute warm-up.
