# 029: Attributes can't be read: no ReflectionAttribute

**State:** Open (out of the named arguments' scope, PR #45)

**Symptom:** `ReflectionClass::getAttributes()` (and the same method of the other reflectors) doesn't exist, so
`#[MyAttribute('A', c: 'C')]` can't be read or instantiated:
```php
#[Attribute] class MyAttribute { public function __construct(public $a = 'a', public $b = 'b', public $c = 'c') {} }
#[MyAttribute('A', c: 'C')] class Test1 {}
$attr = (new ReflectionClass(Test1::class))->getAttributes()[0];
var_dump($attr->getArguments());   // PHP: [0 => 'A', 'c' => 'C']
var_dump($attr->newInstance());    // PHP: MyAttribute {a: 'A', b: 'b', c: 'C'}
```

**Cause:** the prelude's Reflection classes have no attribute support, and the translator keeps no attribute
arguments. Their compile errors are php-src's ("Duplicate named parameter $a", "Cannot use positional argument
after named argument", "Cannot use unpacking in attribute argument list"), which `named-args-compile-attribute`
tests.

**Fix:** `getAttributes()` on the reflectors (filtered by name and `ReflectionAttribute::IS_INSTANCEOF`), and a
`ReflectionAttribute` whose `getArguments()` evaluates each argument as a constant expression in the declaring
scope (positional then names, php-src's `zend_get_attribute_value`), and whose `newInstance()` checks the target
and repetition (`zend_get_attribute_object`) and calls the constructor with the names as named arguments (the
named-arguments binder, `wiki/named-arguments.md` 4.7). Tests: php-src's `Zend/tests/attributes/` and
`Zend/tests/named_params/attributes*.phpt`.
