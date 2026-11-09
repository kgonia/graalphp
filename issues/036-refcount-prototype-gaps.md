# 036: Known gaps of the object refcount prototype (destructors)

**State:** Open. This is work in progress for issue 005, on the `destructors-merged` branch (PR #57, 1183d27):
prototype A plus fixes, with B's profiled releases and `truffle_refcount` ported. The sections below were
measured on 2026-11-06 and 2026-11-07. The status on 2026-11-08 is in [destructors.md](../wiki/destructors.md).

**Left on 2026-11-08 (1183d27).** Fixtures match PHP 8.5, the fuzzer matches on 999 of 1000 programs, and the
WordPress audit has no group. Each of the following is still required (D17):
- call spread `f(...[new D])`: the argument list doesn't count the elements it unpacks (php-src's SEND_UNPACK
  copies them), so releasing the array there destroys D early. For now it is released at the statement's end.
- the yielded value (YIELD moves it);
- `call_user_func_array`'s arguments;
- a temporary's property sent to a parameter that may be by reference;
- a ternary's value passed on (QM_ASSIGN);
- a fresh object that offsetGet or __get returns outside a built-in (item 2b);
- a TypeError's trace keeps its call's arguments (item 2d);
- fuzzer program p1741 (item 3);
- the cycle collector, the shutdown order (3a/3b), `WeakReference`/`WeakMap` cleared at zero (item 6);
- the cost (item 9).

**Where it stands:**
- `-Dphp.rc.destructors=true` runs `__destruct` when an object's count reaches zero, then releases its
  properties (php-src's `zend_objects_store_del`). The flag is off by default.
- `-Dphp.rc.verify=true` is a census check. When an object's count reaches zero, it checks whether the object is
  still reachable from PHP-visible roots (frames' locals, built-ins' arguments, globals, statics, constants,
  static properties). A reachable object is reported and not destroyed. `php.rc.verify.every=n` samples every
  n-th zero; objects whose class has `__destruct` are always checked.
- Gate 2's WordPress, 2026-11-06, with destructors on and a third of zeros checked:
  - pages: 10 front-end pages, 16 wp-admin pages logged in, and REST post create and update;
  - the census checked 6,356 zeros (front-end run) and found 0 still reachable. That rules out early zeros
    among the checked ones only. A count that is too high never reaches zero, so the census never sees it: a
    leaked object's `__destruct` runs at the end of the script or not at all, and the census stays clean (gap 8).
  - Before the last two fixes it found 5, at 3 sites: properties that built-ins set (`json_decode`) weren't
    counted, and `unset($a[k])` released the element before unlinking it.
- Differential fuzzer (`tests/fuzz`, seed 1000, 1000 programs, compared before the end of the script):
  - all features: 915 of 1000 programs match PHP;
  - without closures: 984 of 1000.

## Gaps

1. **Closures: counted since 2026-11-06 (EARLY work, after 112d5f4).** A `PhpClosure` has its own count at every
   store an object has; at zero it releases its statics, captures and `$this` (zend_closure_free_storage), and it
   holds its `$this` from creation. Closure literals and first-class callables (`f(...)`) are owning temporaries,
   as `new` is. `Closure::call`'s one-call stand-in isn't counted. Fuzzer, seed 1000, all features: 996 of 1000
   programs match PHP (915 before); the 4 left are item 3's `$x = &$x` group.
2. **Done 2026-11-07: temporary arrays release their elements.** An array literal, an array a call returns and
   an array intrinsic's result (array_slice, array_keys, explode, str_replace) are owning temporaries: kept in a
   hidden local, or released at once when the statement discards them; at the statement's end one nothing took
   gives its elements up (`PhpArray.releaseTemporary`), its unbound references too. Before: An array with no holders still counts its elements
   (`holdsCounted`): a literal, or a returned array. Used without being stored, it never gives those counts up:
   ```php
   $k = (object)['d' => new D];  unset($k);   // PHP: ~D here; prototype: at the end of the script
   count([new D]);                             // the same
   ```
   Objects are freed late, never early, so the census can't see this. Fix: extend `StoreTemp`/`ReleaseTemp` to
   arrays with no holders, releasing them at the end of the statement unless something stored them.
2b. **Done 2026-11-07: results of PHP code that runtime code calls.** They own a count (a call's result does).
   `Refcount.pending` keeps it in the context's pending releases; `BuiltinRootNode` gives up what was added while
   it ran when it returns, after its own result took a count (php-src's internal code frees a retval once used).
   A result runtime code only tests, converts or discards goes at once (`Refcount.done`: array_walk's, the
   comparators', array_filter's, array_reduce's previous carry, handlers, autoloaders, offsetSet, offsetExists,
   __set, __isset, __unset); one PHP code reads as a value and something else holds too is borrowed at once
   (`Refcount.borrowed`: offsetGet, __get, get hooks, `yield from` an Iterator). Callers that give the count up
   themselves use `invokeOwned`/`callMethodOwned` (foreach's steps, getIterator, __destruct).
   **Left:** a fresh object offsetGet or __get returns outside a built-in (nothing else holds it) stays pending
   until an enclosing built-in returns or the request ends (php-src: the statement's end); the exact fix needs an
   operation around every such read (a cost to measure first). `tests/rcaudit/callbacks.php` shows it.
2c. **Fixed 2026-11-07 on the way (all LATE on WordPress):** `$obj->$name()` kept a count of `$obj` (its lookup's
   `[$obj, name]` array); call_user_func and call_user_func_array compiled in place, and the namespaced fallbacks,
   didn't mark their result as owned (counted twice); an element overwritten with the array it holds (`$m[0] =
   $r` when `$m[0]` is `$r`, as WP_Hook's `$args[0] = $value`) gained a holder; ReflectionClass::newInstance and
   date_create held no count while the constructor ran (a destructor could run inside it).
2d. **Done 2026-11-07 (3bde5ae): arguments move into calls; operands are freed once used.** Both were LATE and
   invisible to the audit, which samples statement ends only.
   - An argument's owning temporary gives its count to the call: the callee's parameter holds it, so it dies in
     the callee's epilogue before the callee's own locals (`~arg ~l`, not `~l ~arg`); a built-in's call counts
     what moved into it and releases it when it returns (`Refcount.takeMoved`); a frame takes the extra arguments
     moved in (a variadic's too) and releases them after its variables (`TakeExtraArguments`); one no call took
     (an exception in a later argument) is freed with its temporary (`Refcount.moved`).
   - An operation that uses an owning temporary releases it after it (`ReleaseAfter`): operators, a call's
     receiver or callee, built-ins compiled in place, casts, isset/empty, conditions (an `if (new D)` kept its
     object past the body before). A property's or element's read whose value is an object, array or reference
     leaves the container to the enclosing operation, since the value is borrowed here (php-src copies it).
   - `tests/rcaudit/moves.php`, `operands.php`: PHP 8.5's output, matched but for moves case 7.
   **Left:** with `zend.exception_ignore_args=0` (the CLI default) an exception's trace keeps the arguments
   (`args`), so a TypeError's arguments live while the exception does; here they are freed before the catch.
3. **Remaining fuzz groups** (16 programs without closures; `target/fuzz/run7-noclos`, grouped by
   `tests/fuzz/triage.py`):

   | Programs | Kind | Where | Example |
   |---|---|---|---|
   | 8 | early zero | reference to itself (`$x = &$x`) | function | p1912 (2026-11-06, all features: 4 left, p1021, p1112, p1668, p1741) |
   | 2 | early zero | `array_shift` | top level | p1043 |
   | 1 | wrong order | `array_shift` | function | p1653 |
   | 2 | wrong order | call | top level | p1326 |
   | 2 | early zero | call | top level | p1741 |
   | 1 | early zero | array copy | top level | p1597 |

4. **PHP-visible holders kept in Java state may not count, and the census can't see them.** Some PHP values are
   stored in Java structures rather than in variables, elements or properties, and PHP code gets them back:
   - SPL storage: `ArrayObject`, `SplObjectStorage` and the like. `attach($o)` holds `$o`, and a `foreach`
     hands it back.
   - Registered callbacks: `set_error_handler([$obj, 'm'])`, `spl_autoload_register`,
     `register_shutdown_function`. They keep `$obj` and call it later; `set_error_handler()` also returns the
     previous handler.
   - Suspended generators' frames: their locals come back when the generator resumes.

   In php-src each of these holds a count. If our version of one doesn't, the object reaches zero while it is
   still held there. Its `__destruct` runs early, and its properties' release can take other objects to zero
   too; later PHP code then uses an object that was destroyed.

   The census reports an early zero only if one of its roots reaches the object, and these holders aren't roots.
   They are the stores most likely to be uncounted, so the check is blind exactly where a miss is most likely.

   Fix: count every such store as `setProperty` does (a count taken on store, given up on removal or when the
   holder dies, as with `detach` or a destroyed generator), then add these holders to the census roots to verify
   it. `WeakMap`'s keys don't hold a count in php-src; it should stay that way.

   **2026-11-06:** SPL storage is PHP code (the prelude), so its properties count. Autoloaders, shutdown functions
   and their arguments, output handlers, error and exception handlers now hold a count (`Refcount.hold`), and are
   audit roots. A suspended generator's frame is released when the generator is destroyed (at zero or at the
   end), and the audit walks it. Not yet counted: curl and mysqli handles' callbacks, `Closure::bind`'s scope,
   lazy objects' initializers.

   Truly internal state is not part of this gap: caches, compiled code, class metadata and interned strings
   don't affect PHP code. The refcount never frees memory (the JVM's GC does), so only PHP-visible holders matter.
5. **Not covered by runs yet:** WooCommerce and plugin pages, which Gate 2's local database doesn't have; posting a
   comment, which issue 034 blocks; a destructor that modifies the array being released (php-src unlinks first,
   which `PhpArray.remove`/`putRef` now do too, but no test covers it); destructor tests for resurrection and for
   an exception thrown from `__destruct`.
6. **Not built yet:** the cycle collector (possible roots are only marked) and the shutdown order (issue 005, 3a/3b).
8. **Leaks (counts too high): the count audit is built (065bf8f), and finds many on WordPress.**
   - **What it does:** `-Dphp.rc.audit=true` tracks every object from creation and, at the script's end (or `exit`),
     compares each count with the holders php-src counts (variables, temporaries, references, elements of unfrozen
     arrays, properties, closures' `$this`/captures/statics, suspended generators, the values built-ins keep). With
     `-Dphp.rc.audit.every=n` it also audits at every n-th statement end, with the running frames as roots, so a
     destructor that is late but still runs before the end shows up too.
   - **Kinds:** LATE (php-src would have destroyed it already), EXTRA (count above php-src's while still held:
     not observable yet), EARLY (count below its holders), FREED while held.
   - **Limits:** counts a running call holds outside slots (a counted receiver, values on a caller's operand stack)
     are tolerated, so an extra count on such a value can hide at a point. Built-ins' Java locals (`array_map`'s
     result while it is built) aren't roots. A clean audit means only that the sampled points saw no difference.
   - Fixtures with the expected findings: `tests/rcaudit/known-gaps.php` (gaps 1 and 2) and `calls.php`. WordPress:
     `bench/destructors/audit.sh` (pages on CPUs 6-13, then `audit_groups.py`).
   - **Gate 2's WordPress, 2026-11-06** (18 requests: 10 front-end pages, the login, 7 wp-admin pages; every 20,000
     statement ends): 140 groups. 61 LATE, 62 EXTRA, 14 EARLY, 3 FREED while held. The largest:

     | Kind | Objects | Class, made in | Likely cause |
     |---|---|---|---|
     | EARLY | 36+24 | `Generator` in `WP_HTML_Tag_Processor::class_list`, `WP_HTML_Open_Elements::walk_up/walk_down` | a generator's count reaches -1: released once more than counted (also `tests/rcaudit/calls.php`) |
     | EARLY, FREED | 4+4 | `WP_HTML_Token`, `WP_HTML_Processor` (`create_fragment`, block bindings) | gap 1 (the processor's closure over `$this`); tokens' `on_destroy` releases bookmarks early, issue 005's one site-visible difference |
     | EARLY | 6+6 | `WP_REST_*_Controller` (seen at `exit`) | not diagnosed |
     | LATE | 132 | `WP_Post` from `get_post` via `array_map` | a built-in's result array (gap 2's kind) |
     | LATE | 112+24+12 | `WP_Block` from `WP_Block_List::offsetGet` | not diagnosed (an `ArrayAccess` result) |
     | LATE | ~120 | `WP_Query` made in a function's local (`render_block_core_template_part`, `get_block_templates`, the dashboard widgets...) | not diagnosed: closures' captures (gap 1) or a temporary array |
     | LATE | 34 | `DateTimeImmutable` from `clone` in `DateTimeImmutable::with` | a built-in's clone |
     | LATE | 30 | `WP_User_Meta_Session_Tokens` | not diagnosed |

   - **After the EARLY fixes (112d5f4, then closures counted), same pages:** no EARLY and no FREED while held left;
     59 LATE and 66 EXTRA groups. Fixed on the way, each with a fixture in `tests/rcaudit` that now prints what PHP
     prints: a generator's call gave no count (it reached -1); foreach over an object held none (FE_RESET); a
     generator at zero wasn't destroyed (finally blocks, frame); a returned array lost its elements while the
     epilogue ran (through a referenced variable, references to elements, or an object freed in the epilogue);
     closures (item 1) and Java-held callables (item 4).
   - **After the LATE fixes (2026-11-07), same pages:** no EARLY, no FREED while held, no LATE; 13 EXTRA groups
     (counts above php-src's while still held, seen at mid-run points: not observable). The per-class trace
     (`-Dphp.rc.trace.class=Name`, `RC_OPTS` of audit.sh) found the last ones: it lists every +1 and -1 of a LATE
     example with the PHP calls.
   - **A temporary argument is moved in php-src (SEND_VAL), kept here.** `$a->m(new D('arg'))->m(1)`: php-src
     destroys `arg` in the first `m`'s epilogue, before its `$l` (`~arg ~l`); here the caller's hidden local keeps it
     to the statement's end (`~l ~l ~arg`). The audit reports it as LATE at points inside the second call.
   - Found on the way, not a refcount issue: `SplObjectStorage::offsetSet($object)` with one argument throws
     ArgumentCountError (the prelude's `$info` has no default; PHP's is null).
9. **Cost:** `CompilationTest`'s IR budgets fail on prototype A's design and also on the merged branch:
   `Query::sql` 53,798 against 52,000, and 24,302 against 24,000. The A/B of 2026-11-07 exceeded every threshold:
   Gate 1 was 1.03–1.65× slower, and Gate 2's warm CPU per visit 4.7×, because of the compiler (destructors.md,
   work order item 7). 9edce73 shrank the compiled code since; the re-measure is pending on a quiet machine.
