# B1 + Option 1 Performance & Safety Lint Rules (2026-11-26)

Part of the [wiki](README.md). Derived empirically from the blunders caught and measured across
Probe A ([b1-precheck.md](b1-precheck.md)), Option 1 ([bootstrap-freeze.md](bootstrap-freeze.md)), and Probe B Rev 1–4
([bench/b1-precheck/probe-b/RESULTS.md](../bench/b1-precheck/probe-b/RESULTS.md)).

## 1. Tier 1 — Automated Bytecode Linter (`B1Lint.java`)

Zero-dependency JDK 25 `java.lang.classfile` verifier: [B1Lint.java](../bench/b1-precheck/probe-b/tools/B1Lint.java).
Scans all compiled `.class` files in `< 1 s`:

```sh
java --source 25 bench/b1-precheck/probe-b/tools/B1Lint.java <classes-dir>
```

| Rule | Blunder Prevented | Enforcement |
|---|---|---|
| **`B1-01`** | HotSpot's `8,000`-byte `DontCompileHugeMethods` cliff silently drops huge methods (`WP_Query::get_posts` with `D17`) to the interpreter; giant `<clinit>` array literals (`states.php`) overflow method limits | Fail if any method exceeds **`7,500`** bytecode bytes (hard cliff `8,000`) or `<clinit>` exceeds **`4,000`** bytes (requires outlining / side-blob `ConstantDynamic`) |
| **`B1-02`** | Probe A: per-site `invokedynamic` linkage blew zero-request warmed RSS to `455 MiB` (`2.2–2.8×` code cache, `3×` slower warmup) | Zero `invokedynamic` instructions in emitted PHP classes; forbid non-concat `invokedynamic` in hot runtime methods |
| **`B1-03`** | Probe B Rev 2→3: megamorphic `invokeinterface` on callable interfaces hits HotSpot's linear `itable` stub scan instead of O(1) vtable jump | Require callable/method/closure bases (`PhpCallable`, `PhpInstanceMethod`, `PhpClosure`) to be `abstract class` and dispatch sites to emit `invokevirtual` |
| **`B1-04`** | Global `SwitchPoint` deopt storms, `ThreadLocal` fiber overhead, and cross-request mutable state leaks (WooCommerce `_customer_id` collision in Option 1) | Forbid `ThreadLocal`, `ScopedValue`, and non-`final` `static` fields in runtime/emitted classes; require explicit `PhpRequestContext` as parameter 0 |
| **`B1-05`** | UTF-16 `java.lang.String` conversions on PHP values/keys (`new String(bytes, ISO_8859_1)` in `ArrayKey`, wrong binary/NUL/UTF-8 lengths) | Forbid `StringBuilder`, `String.getBytes`, and `new String(byte[], ...)` inside hot PHP runtime classes (outside `toString`/`of`/`ofUtf8`) |
| **`B1-06`** | Probe B Rev 3→4: `Long.valueOf` boxing, `Object...` varargs arrays, and 160 `PhpArray` slice allocations/op cost **−37% on `mega` and `hooks`** | Forbid `Long.valueOf` and `Object[]` varargs allocations inside zero-box `@HotPath` dispatchers and property accessors; require arity-specialized `invoke0`/`invoke1`/`invoke2` + `callSlice` fusion |
| **`B1-07`** | Probe B Rev 3→4: repeated `toLowerAscii()` on mixed-case class/method names (`"Hot_C7"`) cloned `byte[]` and double-probed `HashMap` 40×/op | Require cached `ByteStr.lower` field and at most **1** `HashMap.get` probe in `findFunction`, `findClass`, `findMethod`, `findStaticMethod` |
| **`B1-08`** | Accidental Truffle coupling leaking into B1 runtime/emitted bytecode or `B1Lint` passing vacuously on real compiler output (D19 amendment) | Lint exact emitted `byte[]` **before** `defineHiddenClass` using explicit emitted-class/`@HotPath` tags (no Probe B name hardcoding); forbid any symbolic reference to `com/oracle/truffle/*` (including `LocalVariableTable`/`Signature`/annotations, while allowing PHP string data); allow `ConstantDynamic` for hidden-class `classData` side blobs; verify `0` Truffle class loads under `-Xlog:class+load` on a Truffle-free classpath |
| **`B1-09`** | Oversized inline string constants (`> 65 KB` breaks `CONSTANT_Utf8` limit) or constant-pool bloat in emitted PHP classes | Reject any emitted `StringEntry > 16,384` UTF-8 bytes or constant pool `> 2,000` entries (forces `B1LiteralBlob` `ConstantDynamic` side blobs) |
| **`B1-10`** | Outlined `fragment*` helpers breaking the single logical PHP frame / single epilog `D17` invariant | Forbid emitted `fragment*` methods from calling `B1Frame.leave`, `B1RequestContext.pushFrame`, or `B1RequestContext.popFrame` |
| **`B1-11`** | Cross-request state leaks sneaking past `B1-04` via `static final` mutable containers (`HashMap`, `ArrayList`, `AtomicReference`, etc.) | Flag `static final` fields of mutable collection/atomic container types in emitted & runtime classes |
| **`B1-12`** | Retained AST / IR / CFG graphs creeping into loaded PHP hidden classes (preventing Truffle's `~190 MB` retained-IR footprint) | Forbid any field or symbolic reference to `pl/codeloom/php/ir/*`, `pl/codeloom/php/parser/*`, or `pl/codeloom/php/b1/B1Cfg*` in emitted `B1Callable` classes |
| **`B1-13`** | Virtual-thread carrier pinning & starvation when a PHP `Fiber` suspends inside a native `libcurl` FFM callback | Forbid `Thread.ofVirtual`, `Thread.startVirtualThread`, and `Executors.newVirtualThreadPerTaskExecutor` in B1 runtime/emitted classes (use `FiberThread` platform-thread handoff) |

## 2. Tier 2 — `D17` & Option 1 Correctness Gates (CI / JUnit)

| Rule | Blunder Prevented | Enforcement |
|---|---|---|
| **`D17-01`** | Use-after-free on `PhpRef.set([a] → a)`, negative `rc` on reentrant `WPPost` writes, freed parameters in `call1`/`call2`, wrong `wp_current_filter` stack depth on destructor unwind, lost callback exceptions | Mandatory retain → publish → release ordering + exception chaining regression suite (`Checks.java`) across every container (`PhpRef`, `PhpArray`, `PhpObject`, `PhpRequestContext`, `call1`/`call2`/`callSlice`) |
| **`D17-02`** | Dead arrays accumulating in `ctx.gcArrayRoots` (36 dead arrays after 10 `hooks` calls) and 12,000 dead tokens lingering in `destructibles` until shutdown | Assert `liveDestructibleCount == 0`, zero released arrays in `gcArrayRoots`, and zero leaked `rc > 0` kernel objects after `shutdown()` + byte-for-byte `d17_fixture.php` diff vs. pinned PHP 8.5 |
| **`FREEZE-01`** | Mutating frozen Option 1 pure-data graphs or serving stale `wp_options` across concurrent requests | End-of-request structural diff (`0/0/0/0` missed writes), multi-thread isolation stress, and `get_option()` read-set invalidation |

## 3. Tier 3 — JIT Stability & Benchmark Hygiene Gates

| Rule | Blunder Prevented | Enforcement |
|---|---|---|
| **`JIT-01`** | Alternating-context deoptimization / invalidation storms under concurrent request heterogeneity (`WP_ADMIN`, `REST_REQUEST`, `'all'` hooks) | Parse `-XX:+LogCompilation` strictly within `[STEADY_START_MS, STEADY_END_MS]`; fail if `<uncommon_trap> > 2` (target `0`) |
| **`JIT-02`** | Mutable Docker tags, silently disabled PHP JIT (`jit_on=0`), unpaired two-context comparisons, or leaked plugin classloaders | Pin image digests, assert `PHP_VERSION` / `jit_on=1` / `kind=5` inside every run, compute paired per-round two-context penalties, and assert `0` surviving retired loaders after reload GC |
