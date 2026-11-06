# 033: A variadic built-in refuses extra names before its fixed parameters' type errors

**State:** Open (a corner of named arguments, PR #45)

**Symptom:** a variadic built-in that doesn't take extra names (`sprintf`, `array_merge`, `max`...) throws
`ArgumentCountError` "f() does not accept unknown named parameters". php-src throws it where zpp reaches the
variadic parameter (`Z_PARAM_VARIADIC`'s `ZPP_ERROR_UNEXPECTED_EXTRA_NAMED`), so a fixed parameter before it that
fails zpp wins:
```php
sprintf([], x: 1);   // PHP: TypeError "sprintf(): Argument #1 ($format) must be of type string, array given"
                     // Truffle PHP: ArgumentCountError "sprintf() does not accept unknown named parameters"
```

**Cause:** `BuiltinRootNode` throws the error after the argument count check, before the Java body runs: the
bodies parse their parameters themselves (`Args`), with no common point where the variadic one starts.

**Fix:** hand the extras to the body (they are in the frame's closure slot, `NamedExtras`) and throw from `Args`'
accessors of a variadic parameter (`Args.rest` and the like), or check the fixed parameters' arginfo types
(`named-params.txt` could carry them) before throwing.
