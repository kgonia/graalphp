# Issues

One file per finding or problem, named `NNN-short-name.md`. Each records the symptom, the cause, the fix (commit), and the state: Open, Fixed, Won't fix or Decided.

| # | Title | State |
|---|---|---|
| [001](001-dynamic-call-by-ref-args.md) | Dynamic calls don't pass arguments by reference | Fixed |
| [002](002-by-ref-magic-get.md) | `&__get` and `&offsetGet` aren't written through | Fixed |
| [003](003-class-early-binding.md) | Top-level classes aren't bound early | Fixed |
| [004](004-generator-resume-reevaluates.md) | A resumed generator evaluates the yielding statement again | Fixed (every generator on bytecode since #44) |
| [005](005-destructors.md) | Destructors never run | Open: decided, exact semantics (option A); prototype gaps in 036 |
| [006](006-compile-time-jump-checks.md) | Compile-time errors for jumps and try aren't raised | Fixed; the compile-time `continue` warnings open |
| [007](007-goto-over-try-file-level.md) | A goto over a try at file level escapes as a Java exception | Fixed; a goto into an if, try or catch at the top level open |
| [008](008-builtin-arity.md) | Built-in functions don't check their argument count | Fixed |
| [009](009-member-call-on-null-message.md) | "Call to a member function" names the type and the method wrongly | Fixed |
| [010](010-foreach-ref-unshift.md) | A foreach by reference loops forever after array_unshift | Fixed |
| [011](011-pcre-utf-check-per-call.md) | preg_match with /u checks the subject's UTF-8 again on every call | Fixed |
| [012](012-class-constant-visibility.md) | Class constants' visibility isn't checked | Fixed |
| [013](013-method-visibility.md) | Methods' visibility isn't checked, and a private method isn't resolved in the calling scope | Fixed |
| [014](014-namespace-fallback-functions.md) | An unqualified call in a namespace: intrinsics ignore the namespace's function, and the fallback isn't kept per request | Fixed |
| [015](015-generator-frames-in-traces.md) | A delegating generator's frame is missing from traces (gh8289) | Fixed |
| [016](016-woo-stock-lost-update.md) | A WooCommerce `_stock` decrement is lost now and then under load | Won't fix: WooCommerce/MariaDB deadlock, on php-fpm too |
| [017](017-named-args-unpacking.md) | String keys in argument unpacking aren't taken as named arguments | Fixed (built-ins' names come with part 3) |
| [018](018-unpack-traversable.md) | Unpacking a Traversable fails | Fixed |
| [019](019-extract-refs.md) | extract() with EXTR_REFS binds values | Fixed |
| [020](020-variable-variables.md) | Variable variables (`$$name`, `${expr}`) aren't supported | Fixed |
| [021](021-undefined-variable-warning.md) | A read of an unset variable doesn't warn "Undefined variable" | Fixed (file-level read-modify-write after a throwing handler open) |
| [022](022-undefined-array-key-warning.md) | A read of a missing array key, string offset or scalar's offset doesn't warn | Fixed (file-level nested read-modify-write open) |
| [023](023-array-to-string-warning.md) | Converting an array to a string doesn't warn "Array to string conversion" | Fixed (string parameters of built-ins open) |
| [024](024-concat-conversion-order.md) | Concatenation converts its operands in an order the reference (with opcache) doesn't | Fixed (a cast variable reassigned in the same expression left) |
| [025](025-array-key-conversion-notices.md) | Converting a null, float or resource array key doesn't deprecate or warn | Open |
| [026](026-error-exception-constructor.md) | ErrorException ignores its severity, filename and line arguments | Open |
| [027](027-undefined-property-warning.md) | A read of a missing property doesn't warn "Undefined property" | Open |
| [028](028-autoload-invalid-name.md) | Autoloaders run for an empty or invalid class name | Fixed |
| [029](029-reflection-attribute.md) | Attributes can't be read: no ReflectionAttribute | Open |
| [030](030-assert-compiled-out.md) | assert() is compiled out (zend.assertions = -1), the reference runs it | Open |
| [031](031-fiber.md) | No Fiber class | Open |
| [032](032-sensitive-parameter.md) | #[\SensitiveParameter] doesn't redact arguments in traces | Open |
| [033](033-unknown-named-zpp-order.md) | A variadic built-in refuses extra names before its fixed parameters' type errors | Open |
| [034](034-mb-8bit-encoding.md) | mbstring functions refuse the 8bit encoding, so comments can't be posted | Open |
| [035](035-lazy-parse-break-error.md) | The lazy parse reads `break_error_001`'s function body eagerly (`mvn test` fails on main) | Open |
| [036](036-refcount-prototype-gaps.md) | Known gaps of the object refcount prototype (destructors) | Open (work for 005) |
| [037](037-object-number-compare-notice.md) | Comparing an object with a number raises no notice | Open |
| [038](038-string-offset-write.md) | Writing a string offset (`$s[i] = 'c'`) throws "Cannot use a scalar value as an array" | Open |
| [039](039-private-property-shadowed.md) | A subclass's property hides its parent's private property in the parent's methods | Open |
| [040](040-closure-scope-from-class.md) | `Closure::call`, `bind` and `bindTo` don't change the scope of a closure made in a class | Open |
| [041](041-reflection-parameter-default.md) | `ReflectionParameter::getDefaultValue()` and its family are missing (404 WP tests) | Open |
| [042](042-var-export-recursion.md) | `var_export` doesn't warn about circular references, and hangs on a WordPress value | Open |
| [043](043-ref-to-this-in-array-args.md) | `array(&$this)` loses its reference when passed on to a by-reference callback | Open |
| [044](044-version-compare-operators.md) | `version_compare` refuses the `=` operator | Open |
| [045](045-array-unique-sort-regular.md) | `array_unique(..., SORT_REGULAR)` on arrays of arrays warns and removes the wrong elements | Open |
| [046](046-mb-strlen-invalid-utf8.md) | `mb_strlen` counts invalid UTF-8 differently from PHP 8.3+ | Open |
| [047](047-shebang-line.md) | A script's `#!` line isn't skipped | Open |
| [048](048-splobjectstorage-offsetset-arity.md) | `SplObjectStorage::offsetSet($object)` requires a second argument | Open |
| [049](049-missing-extensions-wp-suite.md) | Extensions and built-ins that WordPress's test suite needs and the runtime lacks | Open |
| [050](050-launcher-stdin-slf4j.md) | The launcher can't run a script from stdin; every JVM prints an SLF4J notice on stderr | Open |
| [051](051-wp-suite-undiagnosed.md) | WordPress PHPUnit suite: differences not diagnosed yet | Open |
| [052](052-ref-append-self.md) | `$r[] = $a`, with `$r` a reference to `$a`, appends a copy instead of the array itself | Open |
| [053](053-destructor-backtrace-returning-frame.md) | A destructor that a function's return runs sees the returning function in its backtrace | Open |
| [054](054-rc-verify-moved-temporaries.md) | `-Dphp.rc.verify` reports moved arguments as reachable from the caller's temporary | Open (check tool) |
| [055](055-output-handler-state.md) | Output buffering doesn't know a handler is running, or that one failed | Fixed |
| [056](056-callable-static-cache.md) | `'static::m'` as a callable is resolved once, not per called class | Fixed |
| [057](057-self-referencing-constant.md) | Class constants that refer to each other overflow the Java stack | Fixed |
| [058](058-method-cache-include-scope.md) | An included file's method call cache ignores the class scope, so private methods can be called | Fixed |
| [059](059-element-write-handler-shares.md) | An element write lands in an array the error handler shared during it | Fixed |
| [060](060-abandoned-generator-cleanup.md) | A generator destroyed while suspended doesn't undo its frame's state (`@`, foreach) | Fixed (observable with destructors on) |
| [061](061-arrayiterator-snapshot.md) | `ArrayIterator` walks a snapshot of its keys, so `offsetUnset` and `offsetSet` during foreach are missed | Fixed |
| [062](062-directoryiterator-entry-path.md) | `DirectoryIterator`'s `SplFileInfo` methods describe the first entry | Fixed |
| [063](063-include-once-missing-file.md) | `include_once` of a missing file throws an Error instead of warning and returning false | Fixed |
| [064](064-var-dump-recursion-guard.md) | `var_dump` from inside `__debugInfo` crashes the JVM instead of printing `*RECURSION*` | Fixed |
| [065](065-flock-dropped-handle.md) | A lock held by a handle that went out of scope is never released | Open |
| [066](066-array-walk-reassigned.md) | `array_walk` keeps walking the array it started with after the callback replaces it | Fixed |
| [067](067-finally-emitted-per-exit.md) | A finally body is translated once per exit path, so its compile-time effects repeat | Fixed |
| [068](068-goto-in-finally-loses-pending.md) | A goto inside a finally loses the pending goto out of its try | Fixed |
| [069](069-arrow-fn-yield.md) | An arrow function with `yield` isn't a generator | Fixed |
| [070](070-declare-block-body.md) | The body of a `declare(...) { }` block never runs | Fixed |
| [071](071-compile-diagnostics-at-first-call.md) | Compile-time diagnostics in a function body come at its first call, or never | Fixed |
| [072](072-state-machines-unprobed.md) | Implicit state machines with likely defects, not probed yet | Open |
| [073](073-exit-before-unwind.md) | `exit()` runs the shutdown functions before the stack unwinds | Open |
| [074](074-include-path.md) | `include_path` is used only in error messages | Open |
| [075](075-missing-functions-found-probing.md) | Built-ins missing that the state-machine probes needed | Open |
| [076](076-var-export-circular-warning.md) | `var_export` of a circular structure doesn't warn | Open |
| [077](077-prelude-classes-show-internals.md) | Prelude SPL classes show their implementation in dumps and reflection | Open |
| [078](078-ob-start-chunk-size.md) | `ob_start`'s chunk size is ignored | Open |
| [079](079-object-ids-not-reused.md) | Object ids are never reused | Open |
| [080](080-recursion-depth.md) | Recursion a few hundred calls deep overflows the Java stack | Open |
| [081](081-yield-from-aggregate-generator.md) | `yield from` an IteratorAggregate whose iterator is a generator stops after its first element | Open |
| [082](082-max-execution-time-and-starvation.md) | `max_execution_time` isn't enforced, so a few runaway requests starve `HttpMain` | Open |
| [083](083-language-state-grows-per-request.md) | Language-level state grows with every request that evaluates code or declares names | Open |
| [084](084-flock-links-and-blocking.md) | `flock`: a hard link bypasses an exclusive lock, and a wait for another process can't be cancelled | Open |
| [085](085-native-resources-outlive-request.md) | Native resources outlive their request: a dropped socket's selector, `proc_open` children | Open |
| [086](086-pcre-caches-unbounded.md) | PCRE caches never evict: one-off patterns stay for good, and a context's grow without bound | Open |
| [087](087-uncancellable-waits-and-shared-statics.md) | Waits a cancel can't reach; diagnostics' shared state isn't thread-safe | Open |
| [088](088-fpm-sapi-gaps.md) | Under the fpm SAPI, php-fpm's other functions and request handling are missing | Open |
| [089](089-native-image-build-options.md) | The native image is built without PGO, G1 or a CPU target, and starts cold | Open |
| [090](090-typed-property-write-slow.md) | A typed property's write costs 19 ns compiled, an untyped one's 0.3 ns | Fixed |
| [091](091-splitting-volume.md) | Splitting copies a third of WordPress's compiled nodes; turning its two biggest sources off is slower | Open |
| [092](092-typed-reference-unchecked.md) | A reference to a typed property takes any value | Open |
| [093](093-per-request-name-lookups.md) | A server's requests look names up by hash and declare everything again: ~12% of its CPU | Open |
| [094](094-l0-red-baseline.md) | L0 is red on main: four smoke tests fail on the harness, two CompilationTest cases on budgets | Open |
