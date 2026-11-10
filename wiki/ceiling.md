# The ceiling experiment: is the gap to PHP ours, or the price of PHP's semantics? (2026-11-10)

**Question.** [beat-php.md](beat-php.md) measured how far the engine is from native PHP on WordPress (a hot render
of the front page at 1.9× PHP's time) and did not answer whether it can be closed. This experiment takes the
engine's slow code apart, down to machine instructions, and sorts every cost found into one of three:

- **ours**: a defect of our implementation or of the shape of the code we hand Graal; fixable;
- **semantics**: what exact PHP behaviour costs (reference counts, copy-on-write, destructors, PHP's errors), which
  php-src pays too or avoids only by being C;
- **architecture**: what a Truffle interpreter compiled by Graal cannot do, whatever we write.

A kernel that reaches PHP's time with exact semantics shows the first kind was all there was. A kernel that stops
above it, with a cost of the second or third kind named, shows a floor.

**Result.** The kernels reach PHP and pass it with the semantics exact: `_wp_array_get` from 1.94× PHP's time to
1.13×, the 47 elementary operations from a geometric mean of 0.78 to 0.66, 31 of them faster than PHP, and no cost
found that PHP's semantics force on the engine and spare php-src. The page did not follow: 1.89× → 1.86×, inside
the noise between runs. A render has no kernel: half of it is compiled code and half the runtime that code calls,
no part of either above 5.3%, and its time answers to how much machine code it runs through, not to what an
operation costs alone. By configuration alone (huge pages, a quarter of the inlining budget) the page is at 1.65×;
with the named defects of the runtime about 1.5×, by estimate. Whether the engine can beat PHP on WordPress is not
shown in either direction: the language is not the barrier, and there is no single barrier to remove
([Verdict](#verdict)).

## Method

| what | how |
|---|---|
| elementary operations | `tests/gate2/kernels/ops.php`: 47 kernels, one per operation WordPress's code is made of (array reads, tests and writes, calls, strings, properties) and `_wp_array_get` with its parts (`aget`: four paths; `aget_hit`: one; `aget_inline`: written out; `path3`). ns per operation |
| WordPress's functions alone | `tests/gate2/kernels/hot.php`: 11 kernels on WordPress's own files (`apply_filters` on many hooks, `get_option`, `_wp_array_get`, `WP_HTML_Tag_Processor`, the escapers, `wp_parse_args`, `new WP_Post`, `utf8_uri_encode`) |
| a run | `HOT_SCRIPT=ops.php [HOT_CLASSES=target/classes-TAG] [JAVA_OPTS=…] tests/gate2/kernels/hotbench.sh NAME ROUNDS "k1,k2" "php one"`: PHP 8.5.12 (OPcache, tracing JIT) and the engine (one context, GraalVM 25.4, `-Xmx1g`) on CPUs 14-21 under the CPU lock; checksums compared; `memory.peak` of each |
| a kernel's value | `opstable.py`: the first decile of the later half of the rounds. **3000 rounds.** At 600 a kernel's value depended on the kernels before it and on the time since the start (`aget` drifted for minutes): the compile queue of a 47-kernel process drains late |
| a kernel's machine code | `tests/gate2/kernels/perfasm.sh NAME ROUNDS KERNELS`, `asmdump.py`: HotSpot's printed compilation with perf's samples per instruction, no hsdis. This VM (WSL2) has no hardware counters, so perf samples `cpu-clock`: time, not instructions or misses. Source positions exist only at instructions that can deoptimize or call, and a sample lands on the instruction after the slow one |
| a statement's code size | `bench/code-size/gen.py OUTDIR N [dyn]` writes a method of N statements of one pattern; `irsize.sh`, `irrow.sh`, `csrow.sh` print the method's compilation per tier (IR nodes after partial evaluation and in the end, machine code bytes, time); `calls.sh`, `calls.py`, `marks.py` list the calls and the source positions of the machine code in address order |
| the render | `tests/gate2/k8-cli.sh one NAME N /`: the front page rendered N times in one context ([beat-php.md](beat-php.md) section 2), with `-Dpolyglot.engine.LastTierCompilationThreshold=1000 -Xmx2g -Xms2g -Xmn1200m -XX:+UseParallelGC` and `-Dpolyglot.engine.TraceCompilation=true`; `k8_cli_report.py` (steady time, tenths of the loop, `memory.peak`), `k8_complog.py` (the compile queue over time), `k8_compcmp.py` (traces side by side: compilations, time and code per tier, the inlined share, the functions that grew) |
| where a render's time is | `tests/gate2/k8-jfr.sh NAME one N / DELAY_S`: the same loop under a JFR recording that starts after the compile queue has drained, with `-XX:+DebugNonSafepoints`; `java tests/gate2/JfrSplit.java REC.jfr 0 60 main src/main/java` sorts the PHP thread's execution samples by the machine code that ran (a PHP function's compiled code, the runtime it calls, the interpreter) and names a built-in function from its registration in the sources |

Every engine build compared ran `tests/gate2/kernels/keys_stress.php` (each form of a site against arrays of every
layout) to PHP's output, and the smoke tests named below.

## `_wp_array_get` taken apart

`_wp_array_get($array, $path, $default)` walks `$path` with `foreach`, and per step tests
`is_array`, `isset($array[$key])` (then `array_key_exists` for a null) and reads `$array[$key]`. In `aget` the
engine took 30.3 ns a call against PHP's 15.7: about 720 machine instructions a call against PHP's 330 (from the
two compilations' listings). What the excess was:

| cost | where | kind | what was done |
|---|---|---|---|
| a string key was normalized (is it an integer in string form?) before the site's remembered slot was tried | every element access with a string key | ours | the remembered slot first, by identity: a stored key is normalized, so a key that is the stored instance is too |
| a site whose remembered slot misses called `PhpArray.slotOf` at every access (`$array[$key]` with a varying key, arrays of different layouts, `isset` of an absent key) | the three accesses of the loop | ours | a **probe** inline: the key's hash to its home in the index, then the identity of the key there |
| a small hash was searched by scanning its slots' hashes | every lookup that is not the remembered slot | ours | a one-word index: sixteen four-bit cells in a `long` for a store of up to eight slots |
| the key check and the value were two loads with two bounds checks | the remembered slot | ours | `rawIfKeyAt`: one check for both |
| a write to a local asked the value's kind test by test (long? double? array? object? closure? reference?), and so did the reads after it | `foreach`'s `$key`, every assignment | ours | the site remembers the one class it wrote (`Locals.Writes.valueClass`): one exact check, and the compiler knows the kind from there |
| `isset($a[$k])` and then `$a[$k]`: two lookups | the loop body | php-src pays it too | fused: the test keeps the element it found for the read that follows (below) |
| counting the array's holders as it moves between `$array`, the parameter and the result | per step and per call | semantics, and php-src pays the same refcounts | not changed; about a quarter of what remains |
| `foreach`'s per-step checks (`holdsObjects()`, the iterator's position) | per step | ours | open |
| the key's hash is read through `TruffleString.HashCodeNode` (about 11 instructions: `hashCode()` itself is behind a boundary) | the probe | architecture (TruffleString's API), small | open |

Nothing in the list is a cost that PHP's semantics force on us and spare php-src. The exact behaviour stayed:
reference counts, copy-on-write, destructors, the warnings and errors of every access.

### The fixes, in order

1. **The remembered slot before normalization; the probe** (`8475ed8c`). `SlotCacheNode` (in `ElementNodes.java`)
   serves reads, `isset`, `??`, `array_key_exists` and writes of a string key. A site starts with the remembered
   slot. One that keeps missing becomes a probe: it changes form while it still runs in the interpreter, so no
   compiled code is invalidated for it. A write to an existing string key replaces the slot's value in place.
2. **The one-word index, `rawIfKeyAt`, the written value's class** (`17f3db1c`). The class profile alone took
   `aget_hit` from 26.1 to 15.7 ns in a nine-kernel process.
3. **A budget of rich forms per function; a write through a reference** (`7a42b7ee`): below, under code size.
4. **`isset` and the read after it, fused** (`3eb6dacf`). `if (isset($a[$k])) { $v = $a[$k]; … }`,
   `{ return $a[$k]; }`, `isset($a[$k]) ? $a[$k] : …`, each also as the last operand of an `&&`: the test keeps the
   element it found in a hidden local (`IssetElementKeep`, `HasKept`, `TakeKept` in `PhpBytecodeRootNode`;
   `BytecodeTranslator.keepFor`) and the read takes it. Only where the two cannot differ: `$a` and `$k` are
   variables of the function or constants, and a nested `$a[$i][$k]` descends only through keys that run no code.
   `tests/smoke/isset-then-read.php` and 73 cases of keys and containers compared with PHP 8.5.12 found `isset`
   of a non-`ArrayAccess` object's element and of a string's element under a non-integer key wrong, older than
   this work: fixed in `DimNode.isSetIn` ([102](../issues/102-array-offset-diagnostics.md)).
5. **The probe's first step only** (`3eb6dacf`): below.

### What did not work

| tried | result |
|---|---|
| a class profile of the value a local write replaces, beside the one of the value written | alone it took `foreach_list` from 1.36 to 3.28 ns; with both profiles `foreach_list` was 2.41 ns in the full set (1.01 before) and `aget_hit` 18.75 against 15.66 with the written value's alone. Dropped |
| the index's walk as a loop that ends at a free cell, not a counted one | `isset` of an absent key 0.3–0.7 ns of 3.5 slower. Dropped |
| branch profiles on the site's paths, so an unseen path deoptimizes | rejected before measuring: a site that meets its second case late would throw away the function's compiled code, the failure [103](../issues/103-exit-deopt-cycle.md) is made of |
| the whole walk of the index inline (php-src's `zend_hash_find_known_hash`) | best kernels, slower WordPress: next section |

Two cautions on the kernels themselves. In `aget_hit` the three keys each sit at slot 0 of their arrays, so the one
remembered slot of the shared site hits at every level: alone the kernel ran at 0.99× PHP, after `aget` in the same
process (the site then a probe) at about 1.5×. And a kernel's function has a handful of element sites; what a form
costs in code shows only on a page.

## The kernels, before and after

ns per operation, 3000 rounds; in brackets the ratio to PHP's (below 1: faster than PHP).
`f9ae538` is the engine before this work.

| kernel | PHP 8.5.12 | before (`f9ae538`) | `7a42b7e` | whole walk inline | **first step inline (kept)** | the same, no rich sites |
|---|---:|---:|---:|---:|---:|---:|
| loop | 0.69 | 0.43 (0.63) | 0.43 (0.62) | 0.52 (0.75) | 0.43 (0.63) | 0.44 (0.64) |
| aget | 15.65 | 30.32 (1.94) | 22.62 (1.45) | 17.39 (1.11) | 17.64 (1.13) | 17.13 (1.09) |
| aget_hit | 18.92 | 31.41 (1.66) | 29.34 (1.55) | 22.52 (1.19) | 19.57 (1.03) | 17.49 (0.92) |
| aget_inline | 12.66 | 12.38 (0.98) | 9.88 (0.78) | 6.73 (0.53) | 6.78 (0.54) | 6.67 (0.53) |
| path3 | 5.12 | 4.03 (0.79) | 4.05 (0.79) | 4.03 (0.79) | 4.18 (0.82) | 4.05 (0.79) |
| packed_read | 1.03 | 0.41 (0.40) | 0.41 (0.40) | 0.41 (0.39) | 0.41 (0.40) | 0.41 (0.39) |
| hash_read_const | 2.18 | 2.18 (1.00) | 2.19 (1.00) | 2.34 (1.08) | 2.23 (1.03) | 2.18 (1.00) |
| hash_read_var | 7.98 | 7.51 (0.94) | 5.15 (0.65) | 5.18 (0.65) | 5.95 (0.75) | 7.23 (0.91) |
| isset_const | 4.16 | 7.54 (1.81) | 3.52 (0.85) | 3.52 (0.85) | 3.37 (0.81) | 4.88 (1.17) |
| isset_var | 3.18 | 6.97 (2.19) | 3.64 (1.15) | 3.47 (1.09) | 4.12 (1.30) | 6.05 (1.91) |
| key_exists | 4.16 | 4.96 (1.19) | 3.03 (0.73) | 3.07 (0.74) | 3.39 (0.81) | 5.16 (1.24) |
| array_to_local | 3.30 | 4.59 (1.39) | 3.50 (1.06) | 3.76 (1.14) | 3.51 (1.06) | 3.55 (1.08) |
| foreach_list | 3.50 | 1.02 (0.29) | 1.02 (0.29) | 1.24 (0.35) | 1.02 (0.29) | 1.02 (0.29) |
| foreach_map | 3.60 | 3.21 (0.89) | 2.43 (0.67) | 2.83 (0.79) | 2.43 (0.67) | 2.43 (0.67) |
| foreach_literal | 3.62 | 2.87 (0.79) | 2.21 (0.61) | 2.27 (0.63) | 2.21 (0.61) | 2.07 (0.57) |
| in_array | 4.17 | 6.01 (1.44) | 6.09 (1.46) | 6.01 (1.44) | 6.00 (1.44) | 5.98 (1.44) |
| hash_write_const | 3.11 | 5.92 (1.90) | 3.43 (1.10) | 3.42 (1.10) | 3.40 (1.09) | 6.24 (2.01) |
| hash_write_var | 3.60 | 7.93 (2.21) | 5.26 (1.46) | 5.26 (1.46) | 7.47 (2.08) | 7.86 (2.19) |
| append | 3.65 | 2.46 (0.67) | 2.43 (0.67) | 2.44 (0.67) | 2.52 (0.69) | 2.51 (0.69) |
| literal | 21.09 | 14.82 (0.70) | 12.92 (0.61) | 13.16 (0.62) | 14.99 (0.71) | 14.26 (0.68) |
| literal_arg | 24.43 | 13.89 (0.57) | 12.59 (0.52) | 12.73 (0.52) | 13.93 (0.57) | 13.83 (0.57) |
| separate | 22.93 | 30.78 (1.34) | 27.92 (1.22) | 27.66 (1.21) | 30.21 (1.32) | 30.07 (1.31) |
| merge | 31.57 | 75.16 (2.38) | 69.38 (2.20) | 70.27 (2.23) | 65.15 (2.06) | 78.48 (2.49) |
| call | 2.41 | 0.31 (0.13) | 0.31 (0.13) | 0.31 (0.13) | 0.31 (0.13) | 0.31 (0.13) |
| call_defaults | 5.47 | 1.18 (0.22) | 0.97 (0.18) | 0.97 (0.18) | 0.97 (0.18) | 0.97 (0.18) |
| call_arrays | 4.38 | 1.84 (0.42) | 1.84 (0.42) | 1.84 (0.42) | 1.84 (0.42) | 1.84 (0.42) |
| call_method | 3.65 | 1.78 (0.49) | 1.78 (0.49) | 1.78 (0.49) | 1.78 (0.49) | 1.78 (0.49) |
| call_static | 2.20 | 0.30 (0.14) | 0.30 (0.14) | 0.30 (0.14) | 0.30 (0.14) | 0.30 (0.14) |
| call_closure | 3.64 | 2.81 (0.77) | 2.78 (0.76) | 2.71 (0.74) | 2.94 (0.81) | 2.79 (0.77) |
| call_user_func | 3.41 | 0.30 (0.09) | 0.30 (0.09) | 0.30 (0.09) | 0.30 (0.09) | 0.30 (0.09) |
| concat | 21.73 | 21.02 (0.97) | 21.02 (0.97) | 20.98 (0.97) | 21.07 (0.97) | 21.02 (0.97) |
| interpolate | 18.04 | 23.95 (1.33) | 23.92 (1.33) | 23.95 (1.33) | 23.99 (1.33) | 23.96 (1.33) |
| build | 6.26 | 9.26 (1.48) | 8.96 (1.43) | 9.10 (1.45) | 9.16 (1.46) | 9.14 (1.46) |
| compare | 3.68 | 2.23 (0.61) | 1.73 (0.47) | 1.89 (0.51) | 1.73 (0.47) | 1.73 (0.47) |
| string_functions | 21.76 | 12.29 (0.56) | 10.29 (0.47) | 10.25 (0.47) | 10.22 (0.47) | 10.25 (0.47) |
| replace | 17.33 | 24.79 (1.43) | 25.09 (1.45) | 24.41 (1.41) | 23.88 (1.38) | 25.01 (1.44) |
| sprintf | 16.42 | 36.13 (2.20) | 34.79 (2.12) | 34.45 (2.10) | 36.71 (2.24) | 33.26 (2.03) |
| string_index | 4.81 | 3.44 (0.71) | 3.44 (0.71) | 3.44 (0.71) | 3.44 (0.71) | 3.43 (0.71) |
| prop_read | 1.50 | 0.87 (0.58) | 0.87 (0.58) | 0.87 (0.58) | 0.87 (0.58) | 0.87 (0.58) |
| prop_write | 3.11 | 0.32 (0.10) | 0.31 (0.10) | 0.31 (0.10) | 0.31 (0.10) | 0.31 (0.10) |
| prop_dynamic | 3.26 | 0.87 (0.27) | 0.87 (0.27) | 0.87 (0.27) | 0.87 (0.27) | 0.87 (0.27) |
| new | 22.25 | 8.90 (0.40) | 8.70 (0.39) | 8.71 (0.39) | 8.88 (0.40) | 8.93 (0.40) |
| instanceof | 9.10 | 3.38 (0.37) | 3.39 (0.37) | 3.37 (0.37) | 3.37 (0.37) | 3.38 (0.37) |
| ref_write | 4.44 | 6.64 (1.50) | 4.44 (1.00) | 4.48 (1.01) | 4.47 (1.01) | 6.36 (1.43) |
| object_write | 21.93 | 28.25 (1.29) | 18.32 (0.84) | 18.47 (0.84) | 18.46 (0.84) | 22.05 (1.01) |
| isset_read | 4.69 | 13.15 (2.80) | - | 5.41 (1.15) | 5.97 (1.27) | 8.04 (1.71) |
| isset_ternary | 4.60 | 10.34 (2.25) | - | 4.18 (0.91) | 4.00 (0.87) | 5.64 (1.23) |
| geomean | - | 0.78 | 0.64 | 0.65 | 0.66 | 0.70 |

Peak memory of the 47-kernel process: PHP 27.5 MB; the engine 914 MB before, 998 MB with the whole walk, 891 MB
with the first step (912 MB without rich sites). PHP's own values repeat between two sessions within 4% on 32 of the 47
kernels and within 22% on the others (the call and property kernels, `isset_read`, `string_index`): a ratio near 1
on those is not a result. The sources as committed (the trial switches of the columns removed) repeat the kept
column: `aget` 1.13×, the geometric mean 0.65, 908 MB against PHP's 28 MB.

- `_wp_array_get`: **1.94× → 1.13×** (`aget_hit` 1.66× → 1.03×; written out by hand 0.98× → 0.54×).
- The 47 kernels' geometric mean: **0.78 → 0.66**. The engine is faster than PHP on 31 of them.
- Still slower than PHP: `sprintf` 2.24×, `hash_write_var` 2.08×, `merge` 2.06×, `build` 1.46×, `in_array` 1.44×,
  `replace` 1.38×, `interpolate` 1.33×, `separate` 1.32×, `isset_var` 1.30×, `isset_read` 1.27×.

## What the kernels' gain cost WordPress, and how it was taken back

The commits up to `7a42b7ee` made the kernels faster and the front page slower:

| front page, one context | steady render | × PHP | settles after | last-tier code compiled | last-tier compile time | memory.peak |
|---|---:|---:|---:|---:|---:|---:|
| PHP 8.5.12, twelve runs | 9.75 ms (9.65–10.29) | 1 | | | | 84–87 MB (110 and 114 in two) |
| the engine before (`f9ae538`), four runs | 18.00–18.73 ms | 1.89 | about 5,500 renders | 33.4–33.7 MB | 334–342 s | 2283–2646 MB |
| the whole walk inline (`7a42b7ee` and the fusion), 16,000 renders | 20.13 ms | 2.07 | about 10,000 renders | 43.0 MB | 590 s | 2773 MB |
| the first step inline (kept), six runs | 17.95–18.38 ms | 1.86 | about 5,500 renders | 31.9–32.4 MB | 326–341 s | 2316–2558 MB |

8,000 renders a run but for the third row; identical runs spread ±3%. With a write's store by a call in place of
the inline one the page was the same (17.96 ms, one run). The other configurations are under
"The page: what moves a render".

**The cause is machine code per site.** `Query::sql` of a `gen.py` fixture with eight sites of one pattern, tier 1,
bytes:

| pattern (8 sites) | before | whole walk | first step | first step, no rich sites |
|---|---:|---:|---:|---:|
| `array_key_exists` of an absent key | 3,979 | 24,526 | 7,332 | 12,620 |
| `isset` of an absent key | 6,415 | 24,985 | 8,403 | 6,415 |
| `??` of an absent key | 6,415 | 24,985 | 8,403 | 6,415 |
| `isset` under a varying key | 6,440 | 29,203 | 7,838 | 6,440 |
| `isset`, then the read | 12,385 | 29,656 | 8,510 | 7,127 |
| a read under a varying key | 8,780 | 24,376 | 8,914 | 7,065 |
| a write | 5,896 | 10,607 | 10,607 | 4,508 |

The whole walk was 2.3–2.8 KB a site (Graal unrolls the sixteen cells and copies the bucket loop); its first step
is about 0.2 KB over the remembered slot, a write's inline store about 0.6 KB over its call. On the page that was a
quarter more compiled code, nearly twice the compile time and twice the renders before the queue drains, and a
steady render 6% slower (20.13 ms against 18.99 for the engine before over the same 16,000 renders) although every
element access in it had become cheaper in isolation.

**Why the first check missed it.** `7a42b7ee` bounded the rich forms to eight sites a function
(`LargeCode.RICH`) and checked code size on fixtures of 100 statements, where 8 sites of 100 are rich and the mean
per statement looks small. WordPress's functions mostly have fewer than eight element sites: there every site that
asked was rich. Code size is checked on functions of eight sites now.

**Graal copies what follows a merge.** A write through a reference had three paths inline (the reference's own
array, a shared one, the rest) that met before the store; Graal at times duplicated the store's call for each:
100 statements of `$q['x'] = $s` were 98 KB with six inline write sites and 134 KB with seven. One inline path and
one call for the rest (`Places.refArrayForWrite`) made it linear, about 0.5 KB a statement.

**What is kept.** `PhpArray.slotAtHome`: the key's hash to its home (a small store's cell, a larger one's bucket
head); the slot if the key there is this instance, absent if the home is free, and the call for the rest (a key
displaced from its home, an equal string of another instance). Rich sites stay at eight a function: against none
they are 30–45% on `isset` and `array_key_exists` of varying and absent keys and on overwrites
(`isset_const` 4.88 → 3.37 ns, `hash_write_const` 6.24 → 3.40, `ref_write` 6.36 → 4.47, `isset_read` 8.04 → 5.97),
and WordPress's functions alone are 5% faster with them (`hot.php`'s geometric mean 1.55 against 1.62). The page
is 2.8% slower with them (three runs against six, below): they stay until the page's defaults are decided together
([107](../issues/107-machine-code-per-site.md)). The price against the whole walk is one kernel:
`hash_write_var`, 5.26 → 7.47 ns, whose keys are often displaced.

**The inlined share did not move.** Of the calls in the last-tier code installed at the end, 60% are inlined in
every build (4702 of 7779 before; 4681 of 7800 with the first step; 4617 of 7678 with the whole walk): the slower
render of the whole-walk build is not calls that stopped being inlined.

A trap in comparing traces: a split's number (`apply_filters <split-1234>`) names another call site in another run.
`k8_compcmp.py` sums a function's splits under its name.

## WordPress's functions alone

`hot.php` calls WordPress's own functions on data of a site's shape, one function a kernel, so each has the
compiler to itself and its call sites see one caller. ns per kernel call, 3000 rounds:

| kernel | what it runs | PHP 8.5.12 | before (`f9ae538`) | first step inline |
|---|---|---:|---:|---:|
| mega | `apply_filters` on 40 hooks of 5 callbacks each (a function's name, `[$object, 'm']`, `['Class', 'm']`, a closure) | 22,774 | 41,210 (1.81) | 41,522 (1.82) |
| none | `apply_filters` on hooks nobody added | 1,827 | 2,685 (1.47) | 2,485 (1.36) |
| one | `apply_filters` with one callback | 10,873 | 15,343 (1.41) | 15,102 (1.39) |
| hasf | `has_filter` | 8,094 | 9,454 (1.17) | 8,384 (1.04) |
| opt | `get_option` | 62,760 | 84,523 (1.35) | 73,956 (1.18) |
| aget | `_wp_array_get` on a theme's settings | 2,319 | 5,310 (2.29) | 2,866 (1.24) |
| uri | `utf8_uri_encode` | 42,343 | 50,405 (1.19) | 50,672 (1.20) |
| post | `new WP_Post` of a row of 24 fields | 9,366 | 70,553 (7.53) | 71,142 (7.60) |
| tags | `WP_HTML_Tag_Processor` over a post's markup | 35,586 | 45,271 (1.27) | 44,781 (1.26) |
| esc | `esc_attr`, `esc_html`, `esc_url` | 77,781 | 106,737 (1.37) | 98,009 (1.26) |
| args | `wp_parse_args` | 8,947 | 15,496 (1.73) | 15,037 (1.68) |
| geomean | | - | 1.71 | 1.55 |

`memory.peak` of the 11-kernel process: PHP 33 MB, the engine 970 MB before and 1,022 MB now.

- The element work moved what is made of element accesses: `_wp_array_get` 2.29× → 1.24×, `get_option` 1.35× →
  1.18×, `has_filter` 1.17× → 1.04×, the escapers 1.37× → 1.26×.
- `post` is one defect, [099](../issues/099-object-vars-and-dynamic-property-writes.md): `foreach ($row as $k => $v) $this->$k = $v`
  resolves the name behind a boundary and misses a four-entry cache with 24 names. Without it the geometric mean
  is 1.33.
- No function is at PHP's time. A function of WordPress is 1.2–1.4× PHP alone, although the mean elementary
  operation is 0.66×: its time is in what the operations' mean hides (calls through a callable, `array_merge`,
  string built-ins, property and static lookups), not in the array accesses the kernels were about.

## The page: where a render's time is

`tests/gate2/k8-jfr.sh` runs the loop under a JFR recording; `tests/gate2/JfrSplit.java` sorts the PHP thread's
execution samples by the machine code that was running. A sample's program counter names its compiled method
exactly, so the split has none of the bias of a sampler that waits for safepoints (the guest sampler puts a
function's time on its loop back-edges and calls). The kept build, 16,000 renders, the recording from second 200
(the compile queue had drained): 14,098 samples in 168 s, garbage collection pauses 0.1% of the window.

| what ran | share of the samples | per render |
|---|---:|---:|
| a PHP function's compiled code: its operations and what partial evaluation inlined into them | 47.9% | 8.7 ms |
| runtime methods that compiled code calls (behind a `@TruffleBoundary`, or not inlined) | 49.8% | 9.0 ms |
| built-in functions inlined into a PHP function's code, or compiled alone | 1.2% | |
| the interpreter and what it calls | 0.9% | |

PHP renders the whole page in 9.75 ms: each half of the engine's render is nearly that.

**Runtime the compiled code calls**, by the method called (inclusive):

| method | share | what it is |
|---|---:|---|
| `BuiltinRootNode.call` | 26.5% | built-in functions: next table |
| `SlotCacheNode.find` | 5.3% | an element lookup by a string key at a site that is not rich, or whose key is not at its home |
| `Str.join` | 1.7% | a string built from parts (`interpolate`, 1.33× PHP as a kernel) |
| `Variables.separated`, `SlotCacheNode.put`, `PhpArray.putSlow` | 3.4% | a write to an array: its copy when shared, the insert of a new key |
| `Callables.resolveOrThrow`, `profileIndirectArguments` | 1.9% | a call through a callable at a site of more than six ([106](../issues/106-megamorphic-callable-dispatch.md)) |
| `Refcount.zero` | 1.4% | the last holder of an array or object gone: its members released |
| `ArrayFunctions.search` | 1.2% | `in_array`, `array_search` (1.44× PHP as a kernel) |
| about 60 more | each under 0.6% | |

**Built-in functions** (inclusive: the function and what it calls; `JfrSplit` with the sources names a frame's
function from its `r.fn("name", …)` registration):

| function | share | | function | share |
|---|---:|---|---|---:|
| `clone` | 3.5% | | `preg_match` | 1.1% |
| `json_encode` | 2.9% | | `preg_replace` | 1.1% |
| `array_intersect` | 2.2% | | `sprintf` | 0.7% |
| `md5` | 1.8% | | `preg_split` | 0.6% |
| `array_diff_key` | 1.4% | | `usort` | 0.6% |
| `serialize` | 1.2% | | `implode` | 0.5% |
| `ksort` | 1.2% | | about 50 more | each under 0.5% |

**The compiled code's own time**, by the operation's handler: `IssetElement` 4.0%, `ReadElement` 2.4%,
`WriteVarStatement` 2.3%, `Call` 2.2%, `BindParameter` 2.1%, `ReturnValue` 2.0%, `ReadProp` 2.0%, `ForeachNext`
1.9%, `IncDecNested` 1.8%, `ElementIfSet` 1.7%, `ArrayKeys` 1.5%, `CallMethod` 1.3%, `Global` 1.3%, 70 more under
1.3% each. By the frame on top: `PhpArray.addHolder` 4.2%, `removeHolder` 0.9% and `countIn` 1.7% (counting an
array's holders: what php-src does with one increment), `PhpArray.slotAtHome` 3.4% and `SlotCacheNode.lookup` 3.0%
(the element lookup inline), the calls themselves (`OptimizedCallTarget.doInvoke`, `profiledPERoot`) 3.1%.

So the render has no hot spot. No operation is above 4% of it, no built-in above 3.5%, and the element accesses
this experiment made 1.3–2.2 times faster as kernels are the four element handlers (11%) and the call of
`SlotCacheNode.find` (5.3%).

**Defects of ours the recording names** (each to be measured on the page when fixed;
[108](../issues/108-page-runtime-costs.md)):

| what | share | the defect |
|---|---:|---|
| `SlotCacheNode.find` | 5.3% | the call that a site without a rich form makes: a frame, the key's hash through `TruffleString`'s node, the walk |
| `clone` | 3.5% | `RuntimeFunctions.cloneObject` makes an empty object and defines the properties one by one, each through the generic `DynamicObject` path (a shape transition looked up per property); the object's storage could be copied with its shape in one step |
| `json_encode` | 2.9% | `Encoder.string` (1.6% as the frame on top) writes a byte at a time, each with the buffer's capacity check |
| `array_intersect`, `array_diff` | 2.5% | `ArrayFunctions.diffOrIntersect` builds a `java.util.HashSet` of every other array's values as strings at each call (`HashMap.resize` alone is 1.1% of the render), and asks a stream per element |
| a callable's resolution | 1.9% | [106](../issues/106-megamorphic-callable-dispatch.md) |
| `md5` | 1.8% | `MessageDigest.getInstance` per call (0.2%); the digest itself is the work |

Together 17.9% of the render is in these rows; what a fix returns is some part of each, not measured: by the
shares, 7–11% of the render.

**Exceptions are not a cost.** A render throws 2.3 `NoSuchFileException`s with their stack traces (`file_exists`
and the other file tests of a missing file) and 2.0 stackless `JsonError`s (`json_decode` of text that is not
JSON); the file tests are under 0.1% of the samples, `json_decode` 0.4%.

**The collector decides whether `WP_Query::get_posts` is compiled.** In the night's recording (the engine before
this work, G1 and 1 GB as the servers run) the interpreter and what it called were 7.6% of the samples:
`get_posts` was not installed ("Code installation failed: code is too large", HotSpot's `JVMCINMethodSizeLimit`,
640 KB) and ran in the interpreter for the whole recording. The kept build with the best case's options and G1 in
place of ParallelGC does the same. With the limit raised (`-XX:+UnlockExperimentalVMOptions
-XX:JVMCINMethodSizeLimit=4194304`) the function installs at 765 KB, against 492 KB with ParallelGC: G1's barriers
add 55% to it, 52% to `WP_Term_Query::get_terms` (338 KB against 222 KB) and 7% to the first last-tier
compilations of the 415 functions both runs compiled (2% at the median). So "never compiles … in every run" of
[beat-php.md](beat-php.md) section 3 was true of the runs with G1 (the servers') and not of the best case, where
`get_posts` was compiled all along (512–518 KB in the engine before), and its "1% with the limit raised" compared
two runs that both had the function compiled.

On the page (two runs a side, 8,000 renders, the best case's other options) G1 renders in 20.23 and 20.22 ms,
11.5% over ParallelGC's 18.15, and G1 with the limit raised in 19.30 and 19.58 ms, 7.1% over. `get_posts` in the
interpreter is 3.9% of a render under G1. The other 7.1% is not split here: G1's barriers in the compiled code,
the larger code they make, its collections.

## The page: what moves a render

Front page, one context, 8,000 renders a run, the options of Method. PHP: twelve runs in the same sessions, median
9.75 ms (9.65–10.29), 84–87 MB (110 and 114 MB in two). "Kept" is the build of this page; a change is against the
mean of its six runs (18.15 ms), × PHP against PHP's median.

| configuration | steady render, ms (each run) | change | × PHP | last-tier code compiled | last-tier compile time | calls inlined | settles in the loop's | memory.peak MB |
|---|---|---:|---:|---:|---:|---:|---|---:|
| the engine before (`f9ae538`) | 18.00, 18.67, 18.36, 18.73 | +1.6% | 1.89 | 33.4–33.7 MB | 334–342 s | 61% | 8th tenth | 2283–2646 |
| **kept** (eight rich sites a function, the default budgets) | 17.95, 18.07, 18.38, 18.12, 18.17, 18.19 | | 1.86 | 31.9–32.4 MB | 326–341 s | 60% | 8th tenth | 2316–2558 |
| no rich sites (`LargeCode.RICH` 0) | 17.44, 17.67, 17.80 | −2.8% | 1.81 | 31.0–31.4 MB | 328–334 s | 61% | 8th tenth | 2340–2415 |
| transparent huge pages (`-XX:+UseTransparentHugePages`) | 16.83, 17.05 | −6.6% | 1.74 | 32.1–32.3 MB | 349–355 s | 60% | 9th tenth | 2331–2461 |
| inlining budgets doubled (24,000) | 23.26, not settled | +28% | 2.39 | 42.7 MB | 531 s | 66% | not in 8,000 renders | 2829 |
| inlining budgets halved (6,000) | 17.52, 17.60 | −3.2% | 1.80 | 24.4–25.0 MB | 247–252 s | 51% | 6th–7th tenth | 2382–2598 |
| inlining budgets a quarter (3,000) | 16.92 | −6.8% | 1.74 | 18.7 MB | 168 s | 37% | 5th–6th tenth | 2342 |
| inlining budgets an eighth (1,500) | 17.22 | −5.1% | 1.77 | 16.7 MB | 152 s | 22% | 5th tenth | 2386 |
| huge pages, budgets halved, no rich sites | 16.11, 16.16 | −11.1% | 1.66 | 24.1–24.2 MB | 239 s | 52% | 7th tenth | 2262–2385 |
| huge pages, budgets a quarter | 16.14, 15.97 | −11.5% | 1.65 | 19.3–19.5 MB | 189–193 s | 37% | 6th tenth | 2428–2470 |
| the whole walk inline (16,000 renders) | 20.13 | +11% | 2.07 | 43.0 MB | 590 s | 60% | about 10,000 renders | 2773 |
| G1 in place of ParallelGC (`WP_Query::get_posts` in the interpreter) | 20.23, 20.22 | +11.5% | 2.08 | not traced | | | 7th–8th tenth | 2416–2512 |
| G1, `JVMCINMethodSizeLimit` raised (`get_posts` compiled) | 19.30, 19.58 | +7.1% | 1.99 | not traced | | | 8th tenth | 2553–2614 |
| PHP with huge pages for its heap and its JIT's code (`USE_ZEND_ALLOC_HUGE_PAGES=1`, `opcache.huge_code_pages=1`) | 9.56 against 9.65 beside it | −1% | | | | | | 89 |

The inlining budgets are `polyglot.compiler.InliningExpansionBudget` and `InliningInliningBudget` (12,000 each by
default). Identical runs spread ±3%, so a single run's difference under that is no result. The rebuilt sources of
the commit are the last two runs of "kept" (18.17, 18.19) beside the four of the trial build (17.95–18.38).

- **The render follows the amount of last-tier code, down to a point.** 43 MB: 20.1 and 23.3 ms. 33 MB: 18.0–18.7.
  31–32 MB: 17.4–18.4. 24–25 MB: 17.5–17.6. 18.7 MB: 16.9. Every element access of the whole-walk build was cheaper
  as a kernel and its page was 11% slower; the build with a quarter of the inlining budget inlines 37% of its calls
  where the default inlines 60%, and its page is 7% faster and compiled in half the time. An eighth of the budget
  (16.7 MB, 22% of the calls inlined) gains nothing more: 17.2 ms, one run, 1.8% over the quarter's. The gain ends
  between a quarter and an eighth: the calls left out cost what the code saved.
- **Huge pages are worth 6–7% to the engine and 1% to PHP**: the engine's render walks 32 MB of code and a 2 GB
  heap; PHP's whole process peaks at 85 MB.
- **Rich sites lose on the page what they win elsewhere.** Without them WordPress's functions alone are slower
  (`hot.php`'s geometric mean 1.62 against 1.55; `_wp_array_get` 1.36× against 1.24×, `get_option` 1.28× against
  1.18×) and the page is 2.8% faster in three runs.
- **The changes nearly add**: huge pages −6.6% and a quarter of the budgets −6.8%, together −11.5%; huge pages,
  half the budgets and no rich sites −6.6%, −3.2% and −2.8%, together −11.1%.
- **The collector is worth as much as either**: G1, which the servers run, is 11.5% slower than ParallelGC on
  this page, 7.1% with HotSpot's code-size limit raised so that `get_posts` is compiled (above).

This VM has no hardware counters, so that the cause is instruction-cache and TLB misses is an inference from the
two responses (to less code, to larger pages), not a measurement.

**Where the code is.** The largest last-tier functions of a kept run, machine code bytes: `WP_Query::get_posts`
492,993 (70,000 IR nodes, none of its 95 calls inlined, 6 s to compile in each tier), `WP_Theme_JSON::sanitize`
462,018, `WP_Theme_JSON::get_block_nodes` 335,487 and its loop's own compilation 350,318,
`WP_Term_Query::get_terms` 222,049, `wp_get_layout_style` 185,421, `wp_render_layout_support_flag` 175,334,
`traverse_and_serialize_blocks` 161,742, `WP_Interactivity_API::_process_directives` 149,500 and 145,862,
`WP_Block_Parser::parse` 145,618, `wptexturize` 142,807. A site of an element read is 0.5–1 KB of machine code, an
append 3.7 KB: Graal compiles every site of every operation for itself, where php-src has one handler per opcode
and its JIT, which adds per-site code, gains 12% on this page (11.0 ms without it,
[beat-php.md](beat-php.md)). [107](../issues/107-machine-code-per-site.md).

## Verdict

**Is the gap PHP's semantics?** No. With reference counts, copy-on-write, destructors and PHP's errors exact,
`_wp_array_get` went from 1.94× PHP to 1.13×, and the 47 elementary operations are at a geometric mean of 0.66×,
31 of them faster than PHP. No cost was found that the semantics force on the engine and spare php-src. The
hypothesis that exact PHP behaviour sets a floor above PHP is rejected for the operations examined.

**Does the page follow the kernels?** No, and that is the experiment's second result. The plan read: if the kernel
reaches PHP, the page can. The kernel did; the page moved from 1.89× to 1.86×, inside its noise. A render is not
a kernel many times over:

| level | against PHP | what the gap is |
|---|---:|---|
| an elementary operation (47 kernels) | 0.66× (0.09–2.24) | nothing of principle; ten kernels slower than PHP, each a named defect |
| a WordPress function alone (11) | 1.04–1.82×, `new WP_Post` 7.6× | named defects of ours: [099](../issues/099-object-vars-and-dynamic-property-writes.md), [106](../issues/106-megamorphic-callable-dispatch.md), `array_merge`, the string built-ins |
| the front page, as built | 1.86× | two halves, each nearly PHP's whole render: compiled code 8.7 ms, runtime calls 9.0 ms; no part of either above 5.3% |
| the front page, by configuration alone | 1.65× | less code and larger pages |

**What the gap on the page is made of**, by kind:

- **ours, named**: the configuration (11%, measured), the runtime defects of the table above (7–11% of the render
  by their shares, not measured), [099](../issues/099-object-vars-and-dynamic-property-writes.md). Together they
  would put the page near 1.5×.
- **ours, not named**: the rest of the 49.8% that is runtime calls, some sixty methods and fifty built-ins under
  0.6% each. PHP does the same work in C; how much less it spends there is not known (below).
- **architecture**: machine code per site. It is what the page's time answers to, and the lever has an end:
  from 32 MB to 18.7 MB returned 7% and a smaller budget no more, where parity needs 46%. A site that is not
  compiled for itself calls the runtime, and that call is the 5.3% of `SlotCacheNode.find`.
- **semantics**: nothing measured. Holder counting is 6.8% of the render, which php-src pays too.

**So: can the engine beat PHP on WordPress?** Not shown, in either direction.

- What is shown: the barrier is not the language. Nothing found forbids parity.
- What is also shown: there is no single barrier to remove. From 1.65× the page needs both halves of its render
  at about half their time, and neither half has a part above 5.3%. The method of this experiment (find the hot
  kernel, take it apart) has run out of kernels: the page has none.
- A reachable aim on what is known: 1.4–1.5× of PHP's time on a kept-alive context, by the named defects and the
  configuration.
- Parity would take a change of kind, not of degree: far less machine code for code that is warm but not hot
  (most of a WordPress render runs once per request), and a runtime whose built-ins are within PHP's C. Neither
  was tried here.

**Not proven here:**

- PHP's own split. The php binary of the image is stripped; where PHP spends its 9.75 ms (its built-ins against
  its handlers) is not known, so the engine's 26.5% in built-ins has no PHP number beside it.
- The cause behind the code-volume response (no hardware counters in this VM).
- Any of the named defects' return: none is fixed yet.
- The configuration as a default: huge pages and the smaller budgets were measured on this page in one kept-alive
  context only; the kernels, the memory of several contexts and a served run are not checked with them.
- Served pages: every number here is one context kept alive, the engine's best case. Served through FastCGI the
  night's ratio was 2.12× ([beat-php.md](beat-php.md)), with G1 and 1 GB. What ParallelGC or the raised limit
  return there (several contexts, a smaller heap) is not measured.

## Open

| item | where |
|---|---|
| `array_key_exists` through the shared lookup is 0.9 KB a site (0.5 KB before); `append` about 3.7 KB a statement; a string-key write about two `put` calls a statement | `bench/code-size` |
| `hash_write_var`: a write under a varying key whose key is displaced from its home goes to the call (7.5 ns against PHP's 3.6) | a second step for writes, or a cheaper call |
| what remains of `_wp_array_get` (1.13×): holder counting about a quarter, `foreach` about a fifth, the hash's retrieval | `Locals`, `ForeachNext` |
| `literal`, `literal_arg`, `separate`: at `7a42b7e` 12.9, 12.6, 27.9 ns; with the first step 15.0, 13.9, 30.2, the values before this work. Not examined | |
| the kernels slower than PHP, listed above | |
| a call through a callable at a site of more than six: `mega` 1.82×, 1.9% of a render | [106](../issues/106-megamorphic-callable-dispatch.md) |
| the launcher's defaults (huge pages, the inlining budgets), rich sites on the page, the size of a site and of the 150–500 KB functions | [107](../issues/107-machine-code-per-site.md) |
| the runtime half of a render: `SlotCacheNode.find`, `clone`, `json_encode`, `array_intersect`, `md5` | [108](../issues/108-page-runtime-costs.md) |
| `new WP_Post` at 7.6× PHP | [099](../issues/099-object-vars-and-dynamic-property-writes.md) |
| PHP's own split of its 9.75 ms: needs a php binary with symbols | |
| the collector of the servers: G1 is 11.5% slower than ParallelGC on the page in one context, and `WP_Query::get_posts` (765 KB with G1's barriers) is over HotSpot's limit and stays in the interpreter (3.9%) | [100](../issues/100-warm-up.md), [107](../issues/107-machine-code-per-site.md) |
| the key expressions of `$a[k1][k2]` are evaluated after `$a[k1]` is fetched; php-src evaluates them all first | [105](../issues/105-dim-chain-evaluation-order.md) |
| `CompilationTest`'s budgets: large-function 58,448 IR nodes against 52,000, query-builder 26,551 against 24,000 at `7a42b7e` | [094](../issues/094-l0-red-baseline.md) |

## Reproduce

```bash
export WPN_NAME=fc WPN_NET=172.31.153 WPN_DB_PORT=3453 WPN_AUX_CPUS=6-13 WPN_DB_CPUS=22-23 WPN_THREADS=8
tests/gate2/native.sh volume                                    # the build into the site's volume
HOT_SCRIPT=ops.php tests/gate2/kernels/hotbench.sh NAME 3000 "" "php one"
python -I tests/gate2/kernels/opstable.py target/fc/hot NAME.php NAME.one
tests/gate2/kernels/hotbench.sh NAME 3000 "" "php one"          # hot.php: WordPress's functions
best="-Dpolyglot.engine.LastTierCompilationThreshold=1000 -Xmx2g -Xms2g -Xmn1200m -XX:+UseParallelGC"
tests/gate2/k8-cli.sh php P 3000 /
JAVA_OPTS="$best -Dpolyglot.engine.TraceCompilation=true" tests/gate2/k8-cli.sh one A 8000 /
JAVA_OPTS="$best -Dpolyglot.engine.TraceCompilation=true" K8_CLASSES=target/classes-OTHER tests/gate2/k8-cli.sh one B 8000 /
python -I tests/gate2/k8_cli_report.py target/fc/k8cli OUT.md P A B
python -I tests/gate2/k8_compcmp.py target/fc/k8cli/B.1.err target/fc/k8cli/A.1.err
JAVA_OPTS="$best" tests/gate2/k8-jfr.sh J one 16000 / 200       # a recording from second 200
java tests/gate2/JfrSplit.java target/fc/jfrcli/J/rec.jfr 0 60 main src/main/java > split.md
python bench/code-size/gen.py target/code-size/n8 8 dyn
bash bench/code-size/csrow.sh LABEL target/classes "" target/code-size/n8/lissetd.php
```
