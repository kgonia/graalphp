# 096: `self`, `parent` and `static` in a callable never raise PHP 8.2's deprecation

**State:** Open. Found 2026-11-10 by `tests/smoke/callable-caller.php` (the test of [095](095-callable-resolution-walks-stack.md)):
its values matched PHP's, PHP's output had 60 lines more.

**What happens:** since PHP 8.2 a callable that names its class relatively is deprecated. PHP says so once per
resolution, in the line of the call that resolves:

```php
class A {
    public static function who() { return static::class; }
    public function run() {
        call_user_func(['self', 'who']);        // Deprecated: Use of "self" in callables is deprecated
        call_user_func('static::who');          // Deprecated: Use of "static" in callables is deprecated
        array_map(['parent', 'who'], [1, 2]);   // Deprecated: Use of "parent" in callables is deprecated (once)
        call_user_func([$this, 'parent::who']); // Deprecated: Callables of the form ["A", "parent::who"] are deprecated
        is_callable(['self', 'who']);           // nothing: is_callable() asks with IS_CALLABLE_SUPPRESS_DEPRECATIONS
    }
}
```

We print none of them: `grep -r "in callables is deprecated" src/main/java` finds nothing. The values are right
(the called class, the visibility), only the message is missing, so a site with `E_DEPRECATED` reported and such a
callable logs less than under PHP. WordPress core passes none (a grep of `wp-includes` and `wp-admin`); older
plugins do.

**PHP's rule** (`Zend/zend_API.c`): `zend_is_callable_check_class` raises "Use of "%s" in callables is deprecated"
for `self`, `parent` and `static`; `zend_is_callable_check_func` raises "Callables of the form ["%s", "%s"] are
deprecated" for an array whose method part has a class (`[$obj, 'parent::m']`, `['B', 'A::m']`). Both are skipped
when the caller passed `IS_CALLABLE_SUPPRESS_DEPRECATIONS`: `is_callable()` and the engine's own repeated checks.
A `callable` parameter type and `Closure::fromCallable()` raise it.

**What a fix needs:**

- a "says deprecations" flag through `Callables.resolve` → `classPart` and the method-part parse; `is_callable`
  resolves without it;
- every cache that keeps a resolved callable has to raise it again on a hit: `Callables.LastCall` (a built-in's last
  callable), `CallableDispatchNode`'s kept targets. The message belongs to the resolution, the kept target to the
  callable's value;
- `tests/smoke/callable-deprecated.php` is the test: red until then, one more known failure of the smoke run.
