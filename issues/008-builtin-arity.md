# 008: Built-in functions don't check their argument count

**State:** Open

**Symptom:** a built-in called with too few (or too many) arguments runs with the missing ones as null instead of throwing:
```php
strlen();   // PHP: ArgumentCountError: strlen() expects exactly 1 argument, 0 given; Truffle PHP: int(0)
```

**Cause:** built-ins read their arguments through `Args` (`Args.str(a, 0)` gives "" for a missing one); only `Args.required` checks, and few built-ins use it. php-src checks every internal call against the function's arginfo (`zend_wrong_parameters_count_error`: "expects exactly", "at least" or "at most").

**Fix:** to do: each built-in's minimum and maximum argument count, checked on the call, with php-src's message.
