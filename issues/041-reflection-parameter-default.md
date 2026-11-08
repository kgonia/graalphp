# 041: `ReflectionParameter::getDefaultValue()` and its family are missing

**State:** Open. Found 2026-11-08 by the WordPress PHPUnit suite. This is its largest group: **404 tests** fail on
it.

| Test class | Tests |
|---|---:|
| `Tests_AI_Client_PromptBuilder` | 194 |
| `Tests_Admin_WpUpgrader` | 98 |
| `Tests_AI_Client_EmbeddingBuilder` | 44 |
| `Tests_Pluggable_Signatures` | 36 |
| `Tests_Image_ImageEditApplyChanges` | 21 |
| `Tests_Admin_PluginUpgraderCheckPackage` | 11 |

**Symptom:**
```php
$p = new ReflectionParameter(function ($a = 5, $b = PHP_INT_MAX) {}, 1);
var_dump($p->isDefaultValueAvailable(), $p->getDefaultValue(), $p->isDefaultValueConstant(),
         $p->getDefaultValueConstantName());
// PHP 8.5: bool(true) int(9223372036854775807) bool(true) string(11) "PHP_INT_MAX"
// Truffle PHP: Error: Call to undefined method ReflectionParameter::getDefaultValue()
```

**Fix:** to do. Add the methods with php-src's behaviour:
- `getDefaultValue()` evaluates the default's constant expression in the function's scope (constants, `self::`,
  `new` in initializers);
- when there is no default, it throws `ReflectionException` "Internal error: Failed to retrieve the default value";
- `isDefaultValueConstant()` and `getDefaultValueConstantName()` give a namespaced name as PHP resolves it;
- built-in functions' defaults, which php-src takes from their arginfo.
