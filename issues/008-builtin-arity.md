# 008: Built-in functions don't check their argument count

**State:** Fixed (`fdcadf3`; the table's generator in the next commit)

**Symptom:** a built-in called with too few (or too many) arguments runs with the missing ones as null instead of throwing:
```php
strlen();   // PHP: ArgumentCountError: strlen() expects exactly 1 argument, 0 given; Truffle PHP: int(0)
```

**Cause:** built-ins read their arguments through `Args` (`Args.str(a, 0)` gives "" for a missing one); only `Args.required` checks, and few built-ins use it. php-src checks every internal call against the function's arginfo (`zend_wrong_parameters_count_error`: "expects exactly", "at least" or "at most").

**Fix:** each built-in's counts come from php-src's arginfo and are checked on the call.
- `src/main/resources/pl/codeloom/php/builtins/arity.txt`, made by `tools/builtin-arity.php` from the reference PHP's Reflection (`bootshare-php:8.5`, the reference with mysqli): every function and method (inherited ones too, with the class declaring them) of the extensions the runtime implements. `Registry.arity` reads it.
- `BuiltinRootNode` holds the counts as final fields and throws php-src's ArgumentCountError in the built-in's frame (traces list it as #0), after the arguments were evaluated. Every way to call a built-in goes through its call target: call_user_func, array_map, `$f()`, `f(...)`, unpacking (counted after unpacking). A method is named after the class declaring it (`Exception::__construct` for a `RuntimeException`).
- The built-ins compiled in place (`Intrinsics`, the bytecode's `Count`, `BuiltinNodes.callFull`) are only taken for counts the built-in accepts. `compact()` and `extract()` with a count they don't accept are calls of the built-ins, now declared (called by a name in a variable they raise php-src's "Cannot call compact() dynamically").
- The prelude's functions and methods (built-in classes written in PHP) check with the bytecode's `CheckArity`: too many arguments are an error, and too few give the internal function's message instead of the user function's "Too few arguments to function".
- `mt_rand()` and `rand()` take no arguments or both bounds, as their own parameter parsing says (the arginfo allows one).
- Test: `tests/smoke/builtin-arity.php`.

**Not checked:** `log2`, which the reference PHP doesn't have (it isn't a PHP function); the prelude's private helpers; `ReflectionFunctionAbstract::__toString`, which php-src declares in `ReflectionFunction` and `ReflectionMethod` instead.

**Still open, not about counts:** named arguments aren't supported at all, so `strlen(string: 'a', x: 1)` isn't tested. `compact(...$names)` calls the built-in, which then says it can't be called dynamically (before, it was an undefined function).
