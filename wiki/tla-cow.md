# TLA+ model: copy-on-write arrays and references

Started 2026-10-27. The model is `models/cow/CowRefs.tla`; TLC checks it.

## Why
PHP arrays are values: `$b = $a` copies, and a write through one variable never shows through the other. php-src gets that from refcounts: an array is copied (`SEPARATE_ARRAY`, `zend_array_dup`) before a write when its refcount is above 1. References (`&`) are shared boxes, and a reference stored in an array stays shared when the array is copied as long as its own refcount is above 1; with refcount 1 the copy gets its value (`zend_array_dup_value`). That last rule makes what a copy does depend on exact refcounts.

Truffle PHP has no refcounts. It approximates them with **holder counting** (`PhpArray.addHolder`/`removeHolder`, `countIn`/`countOut`, `copy`) and **bindings** on references (`PhpRef.bind`/`unbind`, `copyElement`), kept by the nodes that assign, pass arguments, return, capture into closures, iterate, and bind statics and globals. This area kept producing bugs (`tests/smoke/refcopy.php`), each found by hand. The model checks the approximation against php-src's semantics over every short sequence of statements.

## The model
Two heaps run the same PHP statements side by side:
- **I, the runtime**: each operator follows a Java method. `AddHolder`/`RemoveHolder` are `PhpArray`'s with `countReferences`; `Bind`/`Unbind`/`IRefSet`/`IRefArrayForWrite` are `PhpRef`'s; `ICopy` is `PhpArray.copy` with `PhpRef.copyElement`; `IWriteVar`, `IMakeRefVar`, `IBindRefVar`, `IArrayForWriteVar`, `IRelease` are `LocalVarNode`'s; `IPut`, `IPutRef`, `IRefAt`, `IRefAtPosition`, `IArrayForWriteKey`, `IRemove` are `PhpArray`'s; `WriteElemVar` follows `DimNode.write` (it holds the value while it makes the container writable); `CallVal`/`CallRef`/`CallClosure`/`Return` are `FunctionRootNode`'s `bindParameters`, `bindCaptures` and `releaseLocals`; `MakeClosure` is `ClosureNode`; `Static` is `StaticVarNode`; the foreach operators are `ForeachNode` and `RefIteration`.
- **S, php-src**: exact refcounts, `SEPARATE_ARRAY` on every write path, `zend_array_dup` with its reference rule, `ZVAL_MAKE_REF`, `ASSIGN_REF`'s order (the element is fetched before the reference is made), and `FE_RESET_RW`/`FE_FETCH_RW` with the hash iterator and its copies (`zend_hash_iterator_pos_ex`, `zend_array_dup_ht_iterators`). Refcounts are not stored: `SRC` counts the slots pointing at a value from the roots and from the live arrays and references, which is php-src's refcount while no garbage cycle points into live data.

The S side was written from php-src 8.5's source (`Zend/zend_vm_def.h` and `zend_hash.c` from the `gate1-php:8.5` image), and every trace below was run on PHP 8.5.12.

**Statements** (in `main`, over the main variables `$a`, `$b`; keys 0 and 1; written ints 1 and 2):
`$x = n`, `$x = [0, 0]`, `$x = $y`, `$x = &$y`, `$x[k] = n`, `$x[k] = $y`, `$x = &$y[k]`, `$x[k] = &$y`, `unset($x)`, `unset($x[k])`, `$x[k][k2] = n`, `$x = $y[k]`. Calls `f($y)` and `f(&$y)`, whose body runs the same statements over its locals `$p` (the parameter) and `$q`, plus `static $q`, and ends with `return $p;` (or `$q`) into a main variable, or `return;`. A closure `$f = function () use ($y)` or `use (&$y)`, `$f()` (its body as a function's) and `unset($f)`. `foreach ($x as $y)` and `foreach ($x as &$y)` with any statements in the body, the next iteration, and `break`.

**Abstractions:**
- Arrays have the keys 0 and 1, in that order; every array is created as `[0, 0]`, and an element is written only while its key exists, so insertion order never changes; `unset($x[k])` removes a key for good. String keys, appends and hashes are left out: they move positions, not sharing.
- Values are the ints 0–2, null and arrays. No objects, so no properties (`PropNode` follows `LocalVarNode`'s rules) and no object handles.
- The main variables are function locals (`LocalVarNode`). Top-level code uses `GlobalVarNode`, where every variable is a reference the globals table binds once; `global $x`, `$GLOBALS` and the include scope (`IncludeScope`) bind and unbind the same way and are not modelled separately. Every trace below was also run at the top level.
- Calls are one level deep; there is one closure and one static variable; loops are not nested and run in `main`.
- `mayHoldRefs` is taken as always set (it only skips scans of arrays that never held a reference), and constant arrays are not frozen (a frozen array behaves as one with two holders).
- Pools are bounded (`NA` arrays, `NR` references per heap); a state that would need more is cut (`Overflow`), as is one with a counter above `MaxCount`. Garbage is reset after each statement, so states differ only in what can still be observed. Too small a pool hides counterexamples: with `NR = 2`, F2's trace needed a third reference and was cut.

**The property** (`Agree`): after every statement, each variable reads the same deep value on both sides (references dereferenced), as do the closure's capture and the static variable; a foreach visits the same key on both sides and ends on both at once. A write through a value copy that shows through another variable, or a write through a reference that one of its aliases misses, breaks it at that write or at a later one, which the search also tries. The `&` marks of `var_dump` are not compared.

## Runs
`models/cow/run.sh [config]`: TLC 2.19, breadth-first, 4 workers, 2 GB heap, `MaxCount = 4`, with `Fixed = {"F2", "F5"}` (the runtime after this work) and `Excluded = {"F1", "F3", "F4"}` (the open findings, so the search goes on past them):

| Config | What | Distinct states | Depth | Time | Result |
|---|---|---|---|---|---|
| `CowRefs.cfg` | 2 variables, `NA = 3`, `NR = 3`, 6 statements: values, references, element references, nested writes, calls by value and by reference, statics | 699,299 | 7 | 8 min | no error |
| `loops.cfg` | the same bounds: references, closures, foreach by value and by reference | 336,580 | 7 | 3 min | no error |
| `sim.cfg` | random traces (`-simulate`): 3 variables, `NA = 4`, `NR = 4`, up to 14 statements, every statement kind | 18.7 million states checked (not distinct) | 15 | 30 min (stopped) | no error |

Earlier configurations on the way (2 variables, `NR = 2`, 5–6 statements, fewer statement kinds: 4,093 to 560,129 distinct states) passed too, but with `NR = 2` they could not reach F2; they are not kept.

## Findings
Each counterexample was run on PHP 8.5 and on Truffle PHP, inside a function and at the top level.

**F1, open: an array stored into itself through a reference alias.** `f1.cfg`, 3 statements (TLC's parallel search may print an equivalent order):
```php
$a = [0, 0];
$b = &$a;
$a[0] = $b;   // php-src: $a[0] is $a's own array (var_dump prints *RECURSION*); Truffle PHP: a copy of it
```
php-src copies the value first only when the compiler sees an assignment to itself (`$a[0] = $a`, `zend_is_assign_to_self`); through an alias it reads `$b` after separating `$a` (refcount 1, so no copy) and stores the array in itself. `DimNode.write` holds every array value while it makes the container writable, which copies. Matching php-src needs recursive arrays (a hold only for syntactic self-assignment, and recursion guards in `var_dump`, `print_r`, `var_export`, `json_encode`, `serialize` and comparisons). No WordPress code is known to depend on it; left open.

**F2, fixed (f3057a6): foreach by reference iterated a stale array.** `f2.cfg` (with `Fixed = {}`), 4 statements (some runs print a 5-statement variant):
```php
$a = [0, 0];
foreach ($a as $k => &$b) {
    $a = 1;   // php-src: "foreach() argument must be of type array|object, int given", the loop ends; Truffle PHP: went on to key 1
}
```
`ForeachNode` made the variable's array writable once and walked that array. php-src's loop holds the variable's *reference* and at each step walks whatever array it holds: a copy of the iterated array made during the loop (by a write after `$b = $a`, or into another variable and assigned back) continues at the position it was copied at, because `zend_array_dup` copies the hash iterators of the array it duplicates; any other array starts at its internal pointer (`$a = [5, 6, 7, 8]` in the body restarts at key 0); a scalar ends the loop with a warning. The loop's temporary also counts in the reference's refcount while it runs. `RefIteration` now does all of that; `PhpArray.copy` and `grow` (which compacts deleted slots) report to it, as php-src moves its iterators. A non-iterable subject now warns as php-src does instead of becoming `[]` (by reference) or being skipped silently (by value). Test: `tests/smoke/foreach-ref-follows.php`.

**F3, open: a freed closure keeps what it captured.** `f3.cfg`, 7 statements:
```php
$b = [0, 0];
$a = &$b[0];                      // $b[0] is a reference
$f = function () use ($b) { };    // the closure holds $b's array
$a = &$b[1];                      // $b separates; both arrays share the reference in [0]
$a = $b;
unset($f);                        // php-src frees the closure and its array: the reference in [0] has refcount 1
$a[0] = 1;                        // php-src: $b[0] stays 0; Truffle PHP: $b[0] is 1 too
```
Captures by reference leak the same way (`$f = function () use (&$x) {}; unset($f);` leaves `$x`'s reference with one binding too many). Objects, closures among them, are not refcounted in Truffle PHP, so the runtime can't see the closure die. Open until objects get refcounts.

**F4, open: references in arrays nothing reaches.** Beyond the breadth-first bounds (3 variables, 7 statements); found by hand while reading the code the model follows, and caught by `OverBound`:
```php
$y = 1; $p = [&$y]; $q = [&$y];
$o = [$q]; unset($q);
unset($o);                 // php-src frees $o and the array in it: the reference has refcount 2 ($y, $p[0])
unset($y);                 // refcount 1
$r = $p; $r[0] = 9;        // php-src: $p[0] stays 1; Truffle PHP: 9
```
An array's holders count the slots of other arrays from the moment it is stored, whether or not those arrays are still alive, so an array nested in a freed array keeps its holder and its references keep their bindings. php-src frees nested arrays recursively. An exact fix makes a nested array count its container only while the container has holders, so that a container losing its last holder releases its nested arrays (recursively) and regains them when held again; every array of arrays then walks its elements when it passes between holders, as a function's returned array does twice, where php-src moves one pointer. That costs on hot paths; left open for a measured decision.

**F5, fixed (f3057a6): a reference nobody was bound to still held its array.** Found when the F2 fix made `refcopy` and `spread-refs` fail: `foreach ($args as &$group)` now makes `$args` a reference, and when the function returned, the unbound reference kept `$args`'s array as a holder, so the references inside it stayed bound and a copy elsewhere kept sharing them. It was there before: `$alias = &$arr`, or passing `$arr` by reference, in a function did the same:
```php
function aliased() {
    $x = 1;
    $arr = [&$x];
    $alias = &$arr;    // $arr's array is held by a reference
    $keep = [&$x];
    return $keep;      // php-src frees $arr's reference and array: $x's reference has refcount 1
}
$k = aliased(); $c = $k; $c[0] = 5;   // php-src: $k is [1]; Truffle PHP: [5]
```
`PhpRef.unbind` now releases the reference's array when its last binding goes, as php-src frees the reference, and `bind` takes it back. In the model, `rel` with `"F5" \in Fixed`; without it the leak shows as an over-bound reference (`OverBound`). Test: `tests/smoke/ref-release.php` (3 of its 5 cases failed before).

**Model errors** (outputs agreed on both engines; the model was fixed):
- M1: null was the same value as an absent element, so a reference to null copied by value looked like a removed key and the loop's keys differed. Null is now its own value (`NUL`).
- M2: the first foreach operators constrained the loop variables twice, so no loop was ever entered and the loop configurations passed vacuously; a deliberately false invariant (`mode # "loop"`) showed it. Worth repeating for any new statement: check it is reachable before trusting a clean run.

**Also seen in the code**, not reached by the model: `GeneratorNodes.ForeachNode` adds a holder to each element value before `write` adds its own, and never removes the holder it took on the iterated array. Both only overcount (extra copies, never a wrong value).

## How to rerun
```
models/cow/run.sh                    # CowRefs.cfg
models/cow/run.sh loops.cfg
models/cow/run.sh f2.cfg             # F2's trace on the runtime as it was
models/cow/run.sh f1.cfg             # F1's trace
models/cow/run.sh f3.cfg             # F3's trace
timeout 1800 models/cow/run.sh sim.cfg -simulate -depth 15   # runs until stopped
```
`XMX` and `WORKERS` set TLC's heap and workers. `Fixed` selects the runtime side: without `"F2"` the foreach operators are the code before f3057a6, without `"F5"` references keep their arrays when unbound. `Excluded` lists the open findings; their triggers are not explored (`Allowed`, and `OverBound` in `Bounds` for F4, which also covers F3 and F5's leaks), so a check goes on past them. A counterexample's statements are its states' `op` values (`grep 'op = '`) and read as the PHP to run.
