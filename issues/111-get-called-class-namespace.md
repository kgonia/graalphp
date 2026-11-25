# 111: `get_called_class()` in a namespace is an undefined function

**State:** Fixed (branch `elementor-compat`)

**Symptom:** with issue 110 fixed, Elementor still fails every request:
```
PHP Fatal error:  Uncaught Error: Call to undefined function Elementor\Core\Base\get_called_class()
  in wp-content/plugins/elementor/core/base/module.php:111
```
`Elementor\Core\Base\Module::instance()` calls `get_called_class()` unqualified, inside a namespace.

**Cause:** the translator compiles `get_called_class()` in place (`BytecodeTranslator.frameFunction`). In a
namespace, an unqualified name resolves at run time, so the call goes in place only when the global function
exists (`FallsBack`). No built-in `get_called_class` was registered, so the call went to the namespace's
function, which doesn't exist. The same applies to a dynamic call, `call_user_func('get_called_class')`.

**Fix:** `get_called_class` is a built-in (`CoreFunctions`). It returns the called class of the calling PHP code
(php-src's `zend_get_called_scope`). Outside a class it raises "get_called_class() must be called from within a
class".

Test: `tests/smoke/get-called-class-namespace.php`.
