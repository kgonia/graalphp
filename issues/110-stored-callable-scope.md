# 110: A private autoloader or shutdown function is checked again from the code that triggers it

**State:** Fixed (branch `fix-stored-callable-scope`)

**Symptom:** Elementor fails on every request of a WordPress site with it active:
```
PHP Fatal error:  Uncaught Error: cannot access private method Elementor\Autoloader::autoload()
  in wp-content/plugins/elementor/includes/plugin.php:820
```
`Elementor\Autoloader::run()` registers its private static method from inside the class,
`spl_autoload_register([__CLASS__, 'autoload'])`. PHP 8.5 accepts that and calls it for any later autoload.
```php
class Loader {
    static function register() { spl_autoload_register([__CLASS__, 'autoload']); }
    private static function autoload($name) { eval("class $name {}"); }
}
Loader::register();
new Foo();   // PHP: Loader::autoload("Foo") runs; Truffle PHP: Error "cannot access private method"
```
Found by Probe A2 (`bench/b1-precheck/probe-a2/run.sh`), the first run of the bootshare plugins site on Truffle PHP
since issue 013's visibility checks.

**Cause:** the registration checked the callable from the registering scope, but `PhpContext` kept only the
callable value and resolved it again at every autoload (`Callables.call`), from the scope of whatever code needed the
class. Since issue 013 that resolution checks visibility, and a private method is inaccessible from there.
`register_shutdown_function` had the same shape: shutdown functions run from no scope.
php-src resolves both once, when they are registered, and keeps the result: `autoload_func_info` holds the function,
object, class and closure (`spl_autoload_register`), and a shutdown entry holds the `zend_fcall_info_cache`
(`register_shutdown_function`).

**Fix:** `spl_autoload_register` and `register_shutdown_function` resolve the callable with `Callables.parameter`
(the registering scope) and `PhpContext` keeps that `Callables.Target` beside the callable (records `Autoloader` and
`ShutdownFunction`). The autoload and the shutdown call the kept target. Duplicates and `spl_autoload_unregister`
compare targets, as `autoload_func_info_equals` does. `spl_autoload_unregister` now checks its callable as php-src's
parameter parsing does, so a private method from outside its class is a TypeError there too.
`spl_autoload_functions` still returns the callables as given.

Test: `tests/smoke/stored-callable-scope.php` (autoloaders static, instance and `'Cls::m'`, triggered from global
code, another class and `class_exists`; unregistering from inside and outside; private shutdown functions).

Not covered: `set_error_handler`, `set_exception_handler` and `ob_start` still resolve their callables at each call.
php-src calls error and exception handlers without a cache too, so their scope rules may differ; unchecked.
