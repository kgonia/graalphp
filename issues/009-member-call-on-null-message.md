# 009: "Call to a member function" names the type and the method wrongly

**State:** Fixed (branch `fix-objects-calls`, commit "Call to a member function / undefined method: ...")

**Symptom:**
```php
$none = null;
$none->Method();   // PHP: Error: Call to a member function Method() on null; Truffle PHP: ... method() on NULL
```

**Cause:** `MethodCallNode` builds the message from `Convert.typeName` (gettype's names: `NULL`, `integer`) and the lowercased method name. php-src uses `zend_zval_value_name` (`null`, `int`, `true`) and the name as written.

The type part had been fixed since (`Args.valueName`); the method stayed lowercased, also in "Call to undefined method" (tree and bytecode, method and static calls). `CallableConvertNodes` named the value with `Convert.debugType` (`bool` for true). `$v->$name()` on a value that isn't an object went the callable path and said "First array member is not a valid class name or object", and a name that isn't a string was converted where php-src's ZEND_INIT_METHOD_CALL raises "Method name must be a string" (also for `$obj->$name(...)`).

**Fix:** the messages take the name as written (`MethodSite.name`, `MethodCallNode.name`, `MethodCallNode.undefinedMethod`), `CallableConvertNodes` uses `MethodCallNode.memberOnNonObject`, and `$v->$name()` / `$v->$name(...)` check the name and the object first (`ResolveDynamicMethod`, `ConvertMethod`, `DynamicMethodCallNode`, `CallableConvertNodes.MethodNode`). Test: `tests/smoke/member-call-message.php`.
