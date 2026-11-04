# 032: #[\SensitiveParameter] doesn't redact arguments in traces

**State:** Open

**Symptom:** php-src (8.2+) replaces the argument of a parameter marked `#[\SensitiveParameter]` with a
`SensitiveParameterValue` object in every backtrace (`debug_backtrace()`, `Exception::getTrace()`,
`getTraceAsString()`'s `Object(SensitiveParameterValue)`); on a variadic parameter, every argument it collects,
the named extras too (`zend_fetch_debug_backtrace`'s `zend_get_parameter_attribute_str(..., num_args)`):
```php
function login($user, #[\SensitiveParameter] $password, #[\SensitiveParameter] ...$rest) {
    throw new Exception('x');
}
login('u', 'secret', token: 't');   // trace: login('u', Object(SensitiveParameterValue), token: Object(SensitiveParameterValue))
```
Truffle PHP shows the values, positional and named alike.

**Cause:** no `SensitiveParameter` attribute class, no `SensitiveParameterValue`, and parameter attributes aren't
kept by the translator (the attributes of issue 029).

**Fix:** keep each parameter's attribute names on `PhpFunction.Param`; `Backtrace`'s argument lists (the bytecode
root's `describe`, `NamedExtras.describe`, `BuiltinRootNode.describe`) wrap a marked parameter's argument, and every
argument of a marked variadic, in a `SensitiveParameterValue` (`getValue()`, `__debugInfo()` empty, not
serializable); the classes `SensitiveParameter` (an `Attribute::TARGET_PARAMETER`) and `SensitiveParameterValue`
in the prelude. Tests: php-src's `Zend/tests/function_arguments/sensitive_parameter*.phpt`.
