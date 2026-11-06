# Issues

One file per finding or problem, named `NNN-short-name.md`. Each records the symptom, the cause, the fix (commit), and the state: Open, Fixed, Won't fix or Decided.

| # | Title | State |
|---|---|---|
| [001](001-dynamic-call-by-ref-args.md) | Dynamic calls don't pass arguments by reference | Fixed |
| [002](002-by-ref-magic-get.md) | `&__get` and `&offsetGet` aren't written through | Fixed |
| [003](003-class-early-binding.md) | Top-level classes aren't bound early | Fixed |
| [004](004-generator-resume-reevaluates.md) | A resumed generator evaluates the yielding statement again | Fixed (every generator on bytecode since #44) |
| [005](005-destructors.md) | Destructors never run | Open: design written, the owner decides (option A recommended) |
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
