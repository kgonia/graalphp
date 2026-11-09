# Implicit state machines (audit 2026-11-08)

An implicit state machine is state that code keeps in flags, nullable fields and counters instead of a named type:
- a phase ("started", "running", "closed");
- a protocol (call A before B);
- a guard against reentrancy.

Such state goes wrong when a transition is missed, typically:
- on an exception;
- on reentry from user code (handlers, magic methods, destructors);
- when the object is dropped half-way;
- when a cache outlives the state it was keyed on.

Four read-only audits, one per area (runtime; bytecode and nodes; parser; built-ins), listed the machines in main
438231d. Each suspected defect got a small script, run on PHP 8.5 and on Truffle PHP. Lines are in
`src/main/java/pl/codeloom/php/`.

## Confirmed defects

| Machine | Where | Issue | Smoke test |
|---|---|---|---|
| Output-buffer stack: no "handler running" state, no "handler failed" state | `PhpContext.java:117`, `981-1043` | [055](../issues/055-output-handler-state.md), fixed | `ob-handler-nested-end`, `ob-handler-throws` |
| `Callables.LastCall` cache: not keyed on the scope for `static::`/`self::` | `Callables.java:455-536` | [056](../issues/056-callable-static-cache.md) | `callable-static-cache` |
| Lazy class constants: no "being evaluated" state | `PhpClass.java:643-650` | [057](../issues/057-self-referencing-constant.md), fixed | `class-constant-self-reference` |
| Method cache in a file root: the scope changes per include, the cache doesn't | `MethodSite.java:49`, `PhpBytecodeRootNode.java:4113`, `4324` | [058](../issues/058-method-cache-include-scope.md), fixed | `method-cache-include-scope` |
| Element place protocol: separate, warn, write; the handler shares the array in between | `ElementChain.java:38-115` | [059](../issues/059-element-write-handler-shares.md) | `element-write-handler-shares` |
| Suspended generator frame: `@` level, foreach iterations, scope not undone on destroy | generator close path | [060](../issues/060-abandoned-generator-cleanup.md), fixed | `generator-abandoned-silence` |
| `ArrayIterator`: a snapshot of keys and an index | `spl.php:38-168` | [061](../issues/061-arrayiterator-snapshot.md) | `arrayiterator-unset-during-foreach` |
| `DirectoryIterator`: `SplFileInfo` path fixed at entry 0 | `spl.php:856-976` | [062](../issues/062-directoryiterator-entry-path.md) | `directoryiterator-entry-type` |
| `include_once` state: marked before the file opens; a missing file is an Error | `IncludeNode.java:37-44` | [063](../issues/063-include-once-missing-file.md), fixed | `include-once-missing` |
| `var_dump` recursion guard: per call, not per object | `Dump.java:29`, `74-80` | [064](../issues/064-var-dump-recursion-guard.md), fixed | `var-dump-debuginfo-recursion` |
| `flock`: a lock outlives its dropped handle; `FileLocks` is static | `FileFunctions.java:464`, `606-676` | [065](../issues/065-flock-dropped-handle.md) | `flock-dropped-handle` |
| `array_walk`: holds the array it started with | `ArrayFunctions.java:194-219` | [066](../issues/066-array-walk-reassigned.md) | `array-walk-reassign` |
| Translator one-shot effects (`made()`, compile warnings) run per copy of a finally body | `BytecodeTranslator.java:1738-1769`, `2152`, `6406` | [067](../issues/067-finally-emitted-per-exit.md), fixed | `finally-emitted-once` |
| goto's pending target (`entering`) is shared with a goto inside the finally | `BytecodeTranslator.java:379-380`, `1079`, `1264` | [068](../issues/068-goto-in-finally-loses-pending.md), fixed | `goto-inside-finally` |
| Generator mode decided by node kind: arrow functions excluded | `Translator.java:898` | [069](../issues/069-arrow-fn-yield.md), fixed | `arrow-fn-yield` |

Found on the way, not state machines:
- A `declare(...) { }` block's body is never translated ([070](../issues/070-declare-block-body.md),
  `declare-ticks-block`).
- Compile-time diagnostics in function bodies come at the first call
  ([071](../issues/071-compile-diagnostics-at-first-call.md), `compile-diagnostics-in-function`).

## Likely, not probed

The scenarios that need a server, sockets, processes or a crafted failure are in
[072](../issues/072-state-machines-unprobed.md). They cover:
- `proc_open` cleanup;
- TLS flags;
- `curl_multi_close`;
- mysqli after close;
- `exit()` during request end;
- the `strict_types` registry;
- the prelude;
- class linking;
- lazy objects;
- syslog;
- `writeFetched`.

Two more need watching but showed no mismatch:
- **The translator's replay invariant (`made`, `madeIndex`, `firstRun`).** Later runs must call `made()` and
  `createLocal` in the same order. `refPlace()` and `delayedProp` consume slots as predicates
  (`BytecodeTranslator.java:6242`, `3606`).
- **`destroyGenerators` at request end.**

## Benign, or already modelled

- **Copy-on-write holders, FROZEN, reference bindings:** [tla-cow.md](tla-cow.md) (F1, F3, F4 open). Write methods
  don't check FROZEN themselves.
- **The translation cache, `singleContext`, single-context caches, `PropNode`'s `accessibleKey`:**
  [tla-engine.md](tla-engine.md).
- **curl handle lifetimes:** [tla-curl.md](tla-curl.md).
- **`PhpGenerator`'s flags** (`open`, `running`, `atFirstYield`, `doInit`, `forcedClose`, phases encoded in nulls).
  The static `unlinkVersion` is not volatile, which affects cache invalidation only.
- **In-place `.=` (`Appender`: none, lazy, in place):** sound. The builder is kept for the frame's life.
- **Calls:** the named-extras handshake, `SpreadArguments`' bind and set protocol, the root `initialize*` order, the
  compilation-policy fields, monotonic profiles, `ScriptRootNode`'s lazy `file` child.
- **Parser:** the lexer's heredoc scan-ahead, one-shot lexer values, re-parsing a body by its brace offset,
  `PhpAst` conversion, `JumpChecks`, `constantExpression`, `closureTypes`, the `ScriptText` cursor.
- **Checked and matching php-src:**
  - the error handler's reentrancy;
  - the `serialize` lock;
  - the JSON recursion guard;
  - PCRE reentrancy;
  - `Response.sent`;
  - `strtok`;
  - `Process.cachedExit`.

Totals: 71 machines.

| Area | Machines |
|---|---:|
| Runtime | 18 |
| Bytecode, nodes | 15 |
| Parser | 18 |
| Built-ins | 20 (11 with gaps, 9 clean) |

Of these, 15 have confirmed defects (19 cases), and 12 are likely and unprobed.
