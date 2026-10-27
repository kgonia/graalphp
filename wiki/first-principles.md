# First principles: what graalphp solved, and what that means here (2026-10-23)

A step back from trimming WordPress kernels. This page covers three things:
- which problems graalphp actually solved, and how, read from its source;
- arithmetic kernels (K0) to check our core against them;
- PHP's own test suite as a correctness baseline beside WordPress.

## What graalphp did

Read from the source of [graalphp](https://github.com/abertschi/graalphp) (Truffle 20.2, about 6.7k lines). The assessment and decision D3 are in [graalphp-assessment.md](graalphp-assessment.md).

| Problem | graalphp's answer | Cost or limit |
|---|---|---|
| Unboxed numbers in locals | SimpleLanguage-style frame slots: Illegal → Long/Double/Boolean → Object, with specialized read and write nodes | none; standard Truffle |
| Int overflow | `Math.addExact` with `rewriteOn = ArithmeticException` to the double specialization | none |
| Unboxed array elements | `PhpArray` holds a `backend` that is a `long[]` or an `Object[]`, dispatched through a Truffle library (`ArrayLibrary`). A write of another type generalizes the backend (`generalizeForValue` → copy into `Object[]`). Growth doubles the capacity. | only int keys 0..capacity; an index past the Java int range throws |
| Value semantics of arrays | `AssignByValueNode` **deep-copies** the array on every by-value assignment, argument pass and return (`copyDeepContents`) | no copy-on-write: binarytrees with real value semantics ran at **0.16×** PHP 7; only a by-reference rewrite reached 6.1× |
| Calls | `DirectCallNode` cached at the first call | no redefinition and no polymorphic call sites; functions only |
| Benchmarks | Benchmarks Game programs rewritten without strings or printf | the 3–4× results over PHP 8's JIT are for this subset |

**Missing entirely:** strings, objects, string-keyed or ordered arrays, exceptions, include, and almost all built-ins.

**The lesson to keep is the storage strategy, not the semantics.** Unboxed `long[]` elements are what made fannkuch and spectralnorm fast. Skipping copy-on-write is what made binarytrees slow.

## Where we stood

**Already in place, in some form:**
- typed frame slots (`FrameSlots`, one tag per slot);
- int speculation with overflow checks;
- copy-on-write arrays counted by holders;
- an ordered hash with a packed layout.

**The gap:** every packed element was a boxed `Long` in an `Object[]`. In fannkuch's profile, `PhpArray.put` took 26%, `countIn` 13% (the "is it a nested array?" holder check on every stored value), `PhpRef.deref` 8% (the reference check on every read) and `getRaw` 6%.

## The int strategy, and what actually made it fast

`PhpArray` now keeps a packed array that holds only ints in a `long[]`, and moves it to `Object[]` at the first other value. An int can't be an array or a reference, so it needs neither the holder check nor the reference check.

| Step | fannkuch | bubblesort | Why |
|---|---:|---:|---|
| `long[]` storage, nodes unchanged | 1.39× **slower** | 1.36× slower | Nodes still passed elements as `Object`: every read boxed a fresh `Long` (Graal can't remove a box that merges with other `Object` paths), every write unboxed one |
| + `DimNode.executeLong` / `writeLong` (typed element paths, also for assignment targets that aren't pure) | 1.07× | 1.07× | Still no faster: array literals and `array()` start as `ofCapacity` (`Object[]`), so no K0 array was ever in the int strategy |
| + literals start in the int strategy | ~1.0× | **0.45×** | bubblesort's array is now `long[]` |
| + **compile only the int-element case until another shows up** (`seenLongMiss`) | **0.56×** | **0.15×** | see below |

**The decisive step was the last one, and it is a general rule.** The compiled element access still contained the generic paths (`putSlow`, `hashGet`, growth) as calls and field writes, even though they never ran. To Graal, a call or a field write anywhere in a loop body can change any field. So every access reloaded `longs`, `size` and `holders` from memory instead of keeping them in registers.

With the cold paths replaced by deoptimization until they are needed:

| Micro test | Before (ms) | After (ms) | PHP JIT (ms) |
|---|---:|---:|---:|
| perms (fannkuch's permutation generator) | 5.3 | 0.60 | 1.8 |
| swaps (24M element moves) | 15.2 | 1.9 | 11.2 |

**Rule:** on a hot path, a case never seen at that site should be a deopt, not a call. This applies beyond arrays.

## K0 now

Same window, cores 14–21, 40 iterations, median of the last quarter. "Before" is from 2026-10-22 in a different window, so read the old column loosely.

| Kernel | PHP 8.5 JIT (ms) | Truffle (ms) | Speedup | Before |
|---|---:|---:|---:|---:|
| fannkuch | 15.65 | 23.61 | 0.66× | 0.56× |
| spectralnorm | 12.25 | 3.10 | 3.95× | 4.59× |
| binarytrees | 9.53 | 8.26 | 1.15× | 1.55× |
| nbody | 43.97 | 30.64 | 1.44× | 1.20× |
| fib | 1.66 | 1.00 | 1.66× | 1.97× |
| intloop | 14.08 | 9.25 | 1.52× | 1.46× |
| floatloop | 7.33 | 2.24 | 3.27× | 3.10× |
| mandelbrot | 6.37 | 7.87 | 0.81× | 0.83× |
| sieve | 5.24 | 3.50 | 1.50× | 1.38× |
| bubblesort | 14.29 | 0.74 | 19.2× | 2.69× |
| **geomean** | | | **1.96×** | 1.63× |

All checksums are identical.

**Where K0 is still slow:**
- **fannkuch (0.66×).** Each piece beats PHP in isolation: perms 3×, the flip loop on fixed permutations faster too. The whole function is 47 KB of machine code with 290 branches. Next: find what keeps generic paths alive in it (a site that missed once keeps its generic code).
- **mandelbrot (0.81×).** No arrays. In the Tier 2 code, Graal's register allocator stores and reloads one double through the stack inside the inner loop, on its dependency chain. The Tier 1 code of the same function is faster (6.6 vs 9.4 ms per call). Steady state therefore depends on which tier and which OSR compilation wins: OSR off makes mandelbrot 8.5 → 6.2 ms and intloop 17.6 → 9.7 ms, but fannkuch, spectralnorm and binarytrees slower. **This is the "bimodal" run-to-run variance seen since M5.** It's a compiler-level issue to report or work around, not a node problem.
- **sieve.** Uses a boolean array, still `Object[]`. A boolean strategy is cheap to add if it ever matters.

### K0 on 2026-10-27

`bench/gate1/compare.sh` with `DRIVER=bench/k0/run.php`, 400 iterations on cores 6–13, two rounds back to back:

| Kernel | Round 1 | Round 2 |
|---|---:|---:|
| fannkuch | 1.23× | 1.90× |
| spectralnorm | 3.49× | 4.14× |
| binarytrees | 1.51× | 1.86× |
| nbody | 1.51× | 1.64× |
| fib | 2.17× | 2.57× |
| intloop | 1.28× | 1.99× |
| floatloop | 2.62× | 3.55× |
| mandelbrot | 0.74× | 0.93× |
| sieve | 1.36× | 1.08× |
| bubblesort | 14.56× | 18.70× |
| **geomean** | **2.00×** | **2.46×** |

- The checksums match.
- Peak memory was 20 MB for PHP and 1.5–1.7 GB for Truffle PHP.
- fannkuch is no longer below PHP.
- The rounds differ by up to 1.5× on one kernel: the box is shared. Read single rounds loosely.

## graalphp's own benchmarks, re-run (`bench/graalphp/`)

`bench/graalphp/run.sh` runs the PHP variants of the Benchmarks Game programs that graalphp measured against PHP 7.4 and PHP 8.0 alpha.
- **Source:** `benchmarks/scripts-report/bench` at graalphp commit 29e22de.
- **Changes:** only `$N` and `$iter`. graalphp's sizes (fannkuch-redux 12, spectral-norm 5500, binary-trees 21) take minutes per iteration on PHP; here it is 10, 1000 and 16, with 60 iterations.
- **Timing:** each program's own hrtime around each iteration, on both engines. The steady state is the median of the last third.

Results on 2026-10-27, cores 6–13, after return by reference was merged (`e446dc0`): three rounds on the same
build. The speed-up is the median of the rounds; the range shows how much they differed.

| Program | Speedup (median) | Rounds | All 60, warm-up included (median) | Peak memory (PHP / Truffle) |
|---|---:|---|---:|---:|
| fannkuchredux.php-1 | 1.72× | 1.57–1.94× | 1.22× | 11–12 / 397–422 MB |
| spectralnorm.php-2-unmodified | 2.62× | 2.47–3.06× | 2.58× | 11–12 / 933–1002 MB |
| spectralnorm.php-2-val | 4.81× | 3.79–4.85× | 3.24× | 12 / 862–890 MB |
| spectralnorm.php-2-ref | 5.14× | 4.16–5.65× | 3.59× | 11–12 / 789–844 MB |
| binarytrees.php-3-val | 1.72× | 1.66–1.81× | 1.63× | 39–40 / 1343–1370 MB |
| binarytrees.php-3-ref | 1.91× | 1.38–1.94× | 1.59× | 43–44 / 1354–1554 MB |

- **Outputs:** they match PHP's in every round, without the timing lines.
- **binary-trees by value:** graalphp ran it at 0.16× of PHP 7, because it deep-copied every array passed by value.
  With copy-on-write it runs at 1.7× of PHP 8.5's JIT.
- **binary-trees by reference:** it uses `function &bottomUpTree()` and `$t = & bottomUpTree(...)`. It failed until
  return by reference landed (`e446dc0`; before it, `returnsRef` was parsed but never used).
- **The first iteration** is 2–6× slower on Truffle PHP. Over all 60 iterations Truffle PHP still comes out ahead on
  every program.
- **Noise:** PHP's own times moved the most between rounds, e.g. spectralnorm-ref 194–299 ms. A first run before the
  merge, on the same cores, gave 1.29–3.54× for the five programs that ran then.

## PHP correctness beyond WordPress: php-src's own tests

`tests/phpt/run.sh`:
- extracts the `.phpt` tests of PHP 8.5.12;
- runs each on PHP (the oracle) and on Truffle PHP, in one JVM with a fresh context per test (`BatchMain`);
- compares stdout and whether the script died.

**Scope:** Zend, tests/lang, and ext/standard's array, strings, math, general_functions, serialize and class_object tests, plus pcre, json and ctype. That is 7,458 runnable cases; 863 are excluded because they need `--INI--`, extensions, stdin or a web SAPI, and 117 skip on PHP itself.

**Baseline (2026-10-23): 2,043 of 7,341 pass (27.8%).**

| Area | Pass |
|---|---:|
| ext/pcre | 61% |
| tests/lang | 49% |
| ext/json | 42% |
| ext/standard | 37% |
| Zend | 22% |

| Failure | Cases | Main causes |
|---|---:|---|
| unsupported construct | 1,590 | traits 306, namespaces 226, generators 203, enums 105, anonymous classes 96, eval 68, named arguments 50 |
| one side died, the other didn't | 1,398 | we die where PHP doesn't (762): missing classes, Reflection ~290, Fiber 40, WeakMap 24, ArrayObject/ArrayIterator 36. PHP dies where we don't (636): compile-time and type errors we don't detect |
| missing function | 1,095 | fopen 150, (un)serialize 112, spl_autoload_register 58, array_chunk 32, array_multisort 28 |
| different output | 967 | |
| file doesn't parse | 206 | |
| internal error | 33 | |
| timeout | 9 | |

**Used as a safety net:** the int-strategy change was run through it and changed nothing (2,043 before and after).

**Fixed on the way:** `wordwrap()` with an empty break string looped forever. A loop inside a built-in has no safepoint, so even `close(true)` couldn't stop it. `BatchMain` now records such a script as hung, exits 3, and `run.sh` resumes after it.

## Measurement lessons from this pass
- **PHP's JIT skips files modified in the last 2 seconds.** `opcache.file_update_protection=2` is the default, so a file run right after it was written runs interpreted. All the scratch comparisons were wrong until this was found: PHP looked 5–30× slower than it is. `bench/php/jit.ini` now sets the protection to 0. The Gate 1 and K0 sources were old files, so their numbers stand.
- **JFR distorts code that uses exceptions for control flow.** Its exception statistics instrument `Throwable`'s constructor, which makes every `break`/`return` exception escape and get allocated: 1.5 GB of `ControlFlow$Break` in one fannkuch profile. Profile break-heavy code with JFR's exception events off, or don't trust its allocation and CPU attribution.
- **JFR's line attribution inside compiled Truffle code is approximate.** The `put → putSlow` frames it showed for fannkuch were never executed; a trace in `toHash`/`putLong` showed that.
