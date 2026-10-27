# TLA+ model: per-request state in the shared engine

Started 2026-10-27. The model is `models/engine/SharedEngine.tla`; the tests are in `tests/shared/`.

## Question
`launcher.HttpMain` runs each request in a new Truffle context on one shared `Engine`, on a pool of threads (`--threads=4`). The language's policy is `ContextPolicy.SHARED` ([D10](decisions.md), step 4). So translated files, their ASTs, call targets, specialized nodes and compiled code belong to the language and serve every context on every thread. PHP-FPM instead gives each request a fresh process state.

The property to hold is **php-fpm's**:
- nothing one request does is observable by another: classes, functions, constants, globals, statics, `ini_set`, error handlers, output, object and resource ids, the time zone;
- each request starts as a fresh php-fpm request would;
- each request prints what PHP 8.5 prints for it run alone (`bench/gate1/php.sh php file.php`).

This page lists the state there is, models the mechanisms that can carry it from one request to another, checks the model with TLC, and turns each counterexample into a real test.

## State inventory
At `b4f3f52`. The lists come from reading `PhpContext`, `BuiltinState`, `PhpLanguage` and the node classes, and from a reflection scan over every compiled class: static fields, non-final fields of nodes, and the cached values of the DSL's generated nodes.

### Per context
Created with the context, reached only through it, released when it closes. `PhpLanguage.finalizeContext` runs `PhpContext.endRequest` and `BuiltinState.close`, also after `exit()` and after an uncaught exception; flock was checked in both cases.

| State | Where | Released at close |
|---|---|---|
| Declared functions, classes, constants (maps in declaration order, and arrays by language-level name id) | `PhpContext.functions`, `classes`, `constants`, `*Slots` | garbage |
| Built-in classes: a new `PhpClass` per context (`Builtins.install`), handlers set on it (`setHandlers`) | `PhpContext.classes` | garbage |
| Static properties, evaluated class constants and property defaults | the context's `PhpClass` | garbage |
| Function `static` variables, keyed by the language-level `PhpFunction` | `PhpContext.staticVariables` | garbage |
| Globals and superglobals, the globals version | `PhpContext.globals` | garbage |
| `include_once` set, prelude files run, autoloaders, shutdown functions | `PhpContext` | garbage |
| Output buffers, the CGI response and request body | `PhpContext` | flushed by `endRequest` |
| Object ids from 1, resource ids from 4 | `PhpContext.nextObjectId`, `nextResourceId` | garbage |
| Default time zone (`date_default_timezone_set`) | `PhpContext.timezone` | garbage |
| The callable resolved last (php-src's fci cache) | `PhpContext.lastCall` | garbage |
| ini values and their parsed settings | `BuiltinState.ini` (`Ini`) | garbage |
| Error and exception handlers, last error, exit status | `BuiltinState.errors` | garbage |
| PCRE: this context's regex views, match blocks, JIT stack, match context, replacement templates, subject buffers, `preg_last_error` | `BuiltinState.pcre` | native memory freed by `PcreFunctions.close` |
| `json_last_error`, mbstring substitute character, `strtok` | `BuiltinState` | garbage |
| Open streams (with their flock locks), the default stream context | `BuiltinState.openStreams`, `defaultStreamContext` | closed |
| mysqli connections, report mode, last connect error | `BuiltinState` | closed |
| curl handles (native), a pending callback exception | `BuiltinState.curl` | freed by `CurlFunctions.State.close` |

Not implemented, so there is no such state to leak: `setlocale` (no JVM default locale or time zone is ever set), `mt_srand`/`srand` (`mt_rand`, `rand` and `lcg_value` use `Math.random()`, which no request can seed), `putenv`, `chdir`, `set_include_path`.

### Language level (`PhpLanguage`, shared by the contexts)
| State | Keyed by | Holds | Bound |
|---|---|---|---|
| Translated files (`files`) | canonical path, AST export's mtime and size | `FileRootNode`s; made once (`FutureTask`); a failed translation isn't kept | the files that exist |
| Prelude translations | prelude file | idem | fixed |
| Name ids (`functionIds`, `classIds`, `constantIds`) | name | dense ints | names in code, plus every name `define()` is given (open note 1) |
| `ClassKey`s (`classKeys`) | template, parent key, interface and trait keys | interned keys | the templates |
| Interned literals (`literals`, `literalsByKey`) | the literal | immutable strings | literals in code |
| Compiled PCRE patterns (`pcrePatterns`) | regex string | immutable compiled and JIT-compiled `pcre2_code` | 4096; later patterns stay per context |
| Built-in functions (`builtins`) | — | `PhpFunction`s, constants; built by the first context with its `Debug` option, which `areOptionsCompatible` keeps equal | fixed |
| Anonymous class counter | — | the `$n` of `class@anonymous` names, given at translation | process-wide in php-src too: `CG(rtd_key_counter)` is reset only at startup |
| `singleContext` assumption | — | valid while one context exists | — |
| Language-level objects: `PhpFunction`, `ClassTemplate`, trait members per using class, frozen literal arrays | — | written only during translation, then published through the files' `FutureTask`; `PhpFunction.callTarget` is created under a lock | — |

### Process-wide statics
| Static | Holds | Verdict |
|---|---|---|
| `FileFunctions.FileLocks.FILES` | flock state per canonical path: holders, waiters, the JVM's OS lock | a stream's lock goes when the stream is closed, at the latest at its context's close; an unused entry is removed |
| `DateFunctions.ZONE_CACHE` | parsed zone per name given | **was unbounded**, keyed by any string a request passes (finding 4) |
| `DateFunctions.TzTables` | timelib's abbreviations and zones | filled in the class initializer, read-only after |
| `SslSocketStream.CA_CACHE` | parsed CA files and directories | keyed by path, size and mtime, so a changed file is read again |
| `CurlFunctions.EASIES`, `MULTIS`, `MIME_SOURCES`, `StderrFile.STREAMS`, `IDS` | libcurl callbacks' userdata ids to their handle | an entry goes when its handle is freed; the ids are never visible to PHP |
| `CurlFunctions.PERSISTENT_SHARES` (thread-local) | `curl_share_init_persistent` handles | shared across requests on purpose, as ZTS php-src keeps them per thread |
| `ByteBuilder.POOL` (thread-local) | large output buffers | borrowed and released within one built-in call; the bytes are copied out |
| `RuntimeFunctions.EXTENSIONS` | the extension list | changed once while the built-ins are built ("curl" without libcurl) |
| `Ini.DIRECTIVES`, `HtmlTables`, `CurlConstants`, `Callables.Parameter`s, PCRE and curl method handles | — | immutable |
| `EncodingFunctions.RANDOM` | `SecureRandom` | thread-safe, unseedable |
| `PcreRecorder`, `NativeClock` | measurement tools | `php.Debug` only |

### Values cached in shared nodes
| Node | Cache | Guard | Verdict |
|---|---|---|---|
| `ClassRefNode.Named`, `FunctionCallNode.Lookup`, `GlobalConstLookup`, `ClassConstLookup`, `StaticPropNode.Lookup`, `GlobalRefNode`, `InstantiateNode.sameClass` | a context's class, function, constant value, static property or global reference | the `singleContext` assumption | with an explicit `Engine` (the server, `SharedContexts`, `--contexts`) Truffle calls `initializeMultipleContexts` before the first context exists, so these never fill; checked with a probe |
| `FunctionCallNode.Dispatch`, `CallableDispatchNode.closure` | the `PhpFunction` | its identity (language-level) | valid |
| `MethodCallNode`, `StaticCallNode`, `NewNode`, `InstantiateNode.sameKey`, `SubclassTestNode`, `CallableDispatchNode.objectMethod` | method, constructor, property names and flags, the instanceof answer | the `ClassKey` | valid: the key fixes them |
| `CallableDispatchNode.named` | the function under a name | name, and the function the context has under its id | valid |
| `CallableDispatchNode.classMethod` | the target of `['Cls', 'm']` | class name, method name, the key the context has under the id | **was invalid** for a missing method (finding 1) |
| `PropNode.accessibleClass` (a hand-written field) | the class whose non-public property the site may access | identity of the `PhpClass` | **kept a context's class** after the context closed (finding 2) |
| `StableValueNode`, `ArrayKeyNode.constantString` | a constant's value, a string key | identity of an immutable value | valid; keeps one immutable string per site, never a context's mutable state |
| `ElementNodes.SlotCacheNode.slotGuess` | a hash slot | checked against the array on every use | a hint: any value is safe, so racing writes are |
| `ArrayLiteralNode.seenObjects` | whether arrays built there held objects | — | a profile: only the starting storage |
| `MethodCallNode.closureCall` | an inserted child | inserted under the AST lock | valid |
| `FunctionRootNode` scope and generator body, `StmtNode.line`, `ClassTemplate.declaredAt` | — | written during translation only | valid |

## The model
`models/engine/SharedEngine.tla` keeps the mechanisms through which state can cross from one context to another:
- **Contexts.** Threads take queued requests; each request creates a context, performs a few operations and closes it. `exit()` ends the script early and closes the same way. Each request declares class `Foo` from one of two templates, as a conditionally declared class is, so their `ClassKey`s differ between requests. It also picks the parent its lexical class gets.
- **Engine.** With an explicit engine the single-context assumption is invalid from the start. A bound engine has one context and closes with it.
- **Node caches**, in the three forms the runtime has:
  - a value kept under the single-context assumption (`ClassRefNode`);
  - DSL inline caches on a language-level key (a method call; `call_user_func(['Foo', 'missing'])` from code running with or without a `$this`). A DSL cache is specialized in two steps, the miss, then an atomic install that re-checks the entry, so two threads can specialize one node at once;
  - `PropNode`'s hand-written field, read and written without a lock.
- **Process-wide state**: flock's lock table for one file, and the PCRE cache with each context's native match blocks.
- **Ownership.** Every value carries its owner: a request (a `PhpClass`) or the language (a `PhpFunction`, a `ClassKey`, an immutable pattern).
- **Versions.** The constants pick the engine (`ExplicitEngine`) and the runtime: as it was at `b4f3f52` (`Fixed = FALSE`), as fixed, or with the wrong fix of finding 2 (`NaiveKey`).

Two threads specializing one node at once is safe in the model: the second install finds the first entry and uses it if its guard holds, or makes the node generic. Each thread's own call uses the value it computed itself. Two threads writing `PropNode`'s field at once is safe too, since either key they write is valid.

Abstracted away:
- the per-context state of the inventory's first table, which only the owning context reaches: it is the owner tag;
- the language-level caches, whose values don't depend on the context that made them (translations are made without a context);
- the `ClassKey`'s contents: a key stands for its template;
- slot hints and profiles (valid whatever their value);
- compiled code: an assumption read in the model is the interpreter's check, and invalidation deoptimizes compiled code before `invalidate()` returns;
- limits above 1 for inline caches.

## Properties
- **Isolation**: no request reads a value another request owns.
- **ValidHits**: every hit in a shared node's cache gives what the uncached path gives the context, and the call, reading it.
- **CloseReleases**: a closed context leaves nothing behind: no node cache, flock entry or native match block refers to it.
- **SingleUntilSecond**: the single-context assumption is gone before a second context exists.

## TLC runs
TLC 2.19, `-workers 4 -deadlock`, 2 GB heap. Configurations:
- `SharedEngine.cfg`: the server (explicit engine), the runtime as fixed;
- `Bound.cfg`: a bound engine (`php script.php`);
- `BeforeHits.cfg`, `BeforeClose.cfg`: the runtime as it was at `b4f3f52`;
- `NaiveKey.cfg`: a first, wrong fix of finding 2.

The state space was widened step by step:

| Config | Threads, requests, operations | Result | Distinct states | Depth | Time |
|---|---|---|---|---|---|
| `BeforeHits.cfg` | 2, 2, 1 | ValidHits violated (finding 1) | 1,140 when found | 6 | 1 s |
| `BeforeClose.cfg` | 2, 2, 1 | CloseReleases violated (finding 2) | 892 when found | 6 | 1 s |
| `NaiveKey.cfg` | 2, 2, 1 | ValidHits violated | 3,826 when found | 7 | 1 s |
| `Bound.cfg` | 1, 2, 4 | holds | 2,533 | 10 | 1 s |
| server, fixed | 2, 2, 2 | holds | 86,665 | 13 | 1 s |
| `SharedEngine.cfg` | 2, 3, 3 | holds | 12,512,623 | 19 | 2 min |
| server, fixed | 3, 3, 2 | holds | 36,210,817 | 17 | 7 min |
| server, fixed | 2, 4, 2 | holds | 48,842,897 | 20 | 15 min |

With one operation per request, a cache hit in a trace can only be on another request's entry, so the `BeforeHits` and `NaiveKey` traces cross requests. With more operations per request, TLC first finds the same violations within one request.

## Findings
Each counterexample was reproduced on the runtime, compared with PHP 8.5.12 running each request alone, and fixed. One was a model error.

### 1. A callable's cache entry depended on the caller's `$this` (ValidHits; fixed)
**Trace** (`BeforeHits.cfg`):
1. r1 and r2 both declare `Foo` from T1, which has `__call` and `__callStatic`.
2. r1 calls `call_user_func(['Foo', 'missing'])` from code not running on a `Foo`. The site specializes `CallableDispatchNode.classMethod` and keeps `__callStatic`, a static target, under the class name, the method name and T1's key.
3. r2 makes the same call from code running on a `Foo`. The guards hold, so the site gives `__callStatic`, where the uncached path (`Callables.method`, as php-src's `zend_is_callable_check_func`) gives `__call`. For a class without `__call` the uncached path gives no target at all: PHP's TypeError.

**Real:** `tests/shared/requests/callable-lib.php` makes the closure; `callable-unbound.php` calls it unbound, `callable-bound.php` bound to a `Magic` with `Closure::bind`.
- PHP prints `__callStatic` and `__call` for them alone.
- On one engine, after `callable-unbound.php`, `callable-bound.php` printed `__callStatic`.
- `isolation.php`'s version, both orders in one request, printed `corrupted 300` in all 40 contexts.

The same happened within one request; sharing made it cross requests.

**Fix** (`3879e11`): a method standing in for a missing one (a magic name) is never kept; such a callable takes the generic path. The guard reads only cached values, so it is evaluated when the entry is made, not per call.

### 2. `PropNode` kept a context's `PhpClass` (CloseReleases; fixed)
**Trace** (`BeforeClose.cfg`):
1. r1 reads a non-public property from a method of its class. The node's `accessibleClass` field is empty, so r1 writes its own `PhpClass` there.
2. r1 closes. The shared AST still refers to r1's class.

**Real:** `tests/shared/requests/retained.php` leaves 256 MB in a static property and reads a private property in a method. After its context closed, 259 MB stayed in use; the same script without the property read left 10 MB. A class keeps its static properties, so a WordPress class holding a singleton kept a whole request's object graph. Every node that reached the slow path first in a different context kept a different one.

The field also served only the context whose class it held. In the server, every other context took `canAccessProperty`, a boundary call with map lookups, for every read of a private property and every protected one not read through `$this`.

**Fix** (`3879e11`): the field keeps the object's `ClassKey`, and only for an access the key decides (`PhpClass.isPropertyAccessKeyed`).

**What the fix must not do** (`NaiveKey.cfg`, ValidHits): keep the key for every access it allowed.
- A protected property can be accessible through the scope's own ancestors (`scopeClass.isSubclassOf(root)`). They depend on the parent the scope's class was declared with in each request, and the object's key doesn't fix that.
- Trace: r1 (T1, the lexical class extends P1) is allowed and keeps T1's key. r2 (T1, the lexical class extends P2) hits, and is allowed where the uncached path refuses.
- `isolation.php` runs this case in 40 contexts: `Reader` extends `Middle`, which extends `Root` in about half the contexts, and reads `Root`'s protected property of a `Leaf`.

**Hot path:** property access. A non-public property read outside `$this->protected` now compares `phpClass.getKey()` with the kept key, one more field load than the identity check. In the server it hits where the old check always missed. Needs an A/B.

### 3. Model error: a bound engine with a second context
The first version followed Truffle's general contract: `initializeMultipleContexts` before the second context exists, whatever the engine. TLC broke CloseReleases:
1. On a bound engine, r1 specializes `ClassRefNode` under the single-context assumption with its own class.
2. r1 closes.
3. A second context could then come, and the node still refers to r1's class.

The real code can't do that:
- a probe (`PhpLanguage.get(null).singleContext()` from an entered context) shows the assumption invalid while the first context of an explicit `Engine` is open, and valid in a bound one;
- a bound engine refuses a second context and is closed with its context.

The model now gives a bound engine one context and drops its caches when it closes (`1a1da6c`). With an explicit engine the single-context specializations never fill.

### 4. The time zone cache grew with request data (found in the inventory; fixed)
`DateFunctions.ZONE_CACHE` kept the parse of every zone name a script passed, valid or not: `new DateTimeZone($input)`, or a zone that `DateTime::createFromFormat` reads from its input.

`tests/shared/requests/retained-zones.php` asks for 300,000 zones that don't exist: 84 MB stayed in use after its context closed. It's 5 MB with the cache stopped at 4096 names (`0f0e2b3`). Later names are parsed on each use; the identifiers, abbreviations and offsets in use stay cached.

### Open notes
1. **Name ids for `define()`d constants.** `PhpContext.defineConstant` gives every name a language-level id, also a name made at run time (`define('X_' . $id, ...)`). The engine's id table then grows with every such name any request defines. A context that defines one sizes its slot array to the largest id.

   Proposed fix:
   - give a slot only to names some code refers to (an id that exists already);
   - let `GlobalConstLookup`'s miss path look the name up in the context's map and fill the slot. That path already leaves compiled code.

   Not done here: WordPress core defines constants under literal names.
2. **Immutable values kept by identity caches.** `StableValueNode` and `ArrayKeyNode.constantString` keep one string per site, also one a request computed (a constant defined from a large string). That is bounded by the sites and never a context's mutable state, as in GraalJS's identity caches. Accepted.
3. **`PHP_SAPI` is folded to `"cli"`**, also under the CGI SAPI, where php-fpm has `fpm-fcgi`. `Sapi` isn't in `areOptionsCompatible`, but nothing language-level depends on it, so this is a single-context deviation, not a leak between requests.
4. **Callables beyond this model's scope.** `[$obj, 'parent::m']` resolves `parent` against the object's class, where php-src uses the caller's scope, and method visibility isn't checked for callables. The caches agree with the uncached path, so these are single-context deviations.
5. **flock after `exit()` and an uncaught exception.** Checked on the runtime: a lock taken before `exit(0)`, or before an uncaught exception, is free for the next request on the engine (`finalizeContext` runs).
6. **`curl_share_init_persistent`** handles live per thread across requests, on purpose (ZTS php-src keeps them per thread). HttpMain's pool threads play php-fpm's workers.

## How to rerun
- `models/engine/run.sh` checks `SharedEngine.cfg`; `models/engine/run.sh BeforeHits.cfg BeforeClose.cfg NaiveKey.cfg Bound.cfg` the others. It uses `~/tools/tla2tools.jar` (`TLA2TOOLS`) and GraalVM's java, with a 2 GB heap (`TLC_HEAP`). Widen the state space by raising `Threads`, `Requests` and `Ops` in a copy of a config.
- `tests/shared/run.sh` (build first) runs:
  - `isolation.php` in 40 contexts on 4 threads;
  - the request sequences, each request against PHP's output for it alone;
  - the retained-heap checks.
