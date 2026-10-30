# 009: "Call to a member function" names the type and the method wrongly

**State:** Open

**Symptom:**
```php
$none = null;
$none->Method();   // PHP: Error: Call to a member function Method() on null; Truffle PHP: ... method() on NULL
```

**Cause:** `MethodCallNode` builds the message from `Convert.typeName` (gettype's names: `NULL`, `integer`) and the lowercased method name. php-src uses `zend_zval_value_name` (`null`, `int`, `true`) and the name as written.

**Fix:** to do, together with `CallableConvertNodes`' copy of the message (`Convert.debugType`), which should be checked against the same rule.
