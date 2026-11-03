# 025: Converting a null, float or resource array key doesn't deprecate or warn

**State:** Open

**Symptom:**
```php
$a = ['' => 1, 1 => 2];
var_dump($a[null], $a[1.5]);
// PHP 8.5: Deprecated: Using null as an array offset is deprecated, use an empty string instead
//          Deprecated: Implicit conversion from float 1.5 to int loses precision
// Truffle PHP: nothing
```
php-src raises them wherever it converts a key (`slow_index_convert`, `slow_index_convert_w`, `zend_isset_dim_slow`,
array literals through `array_set_zval_key`, `array_key_exists`), for every fetch type, isset() too; a resource key
warns "Resource ID#%d used as offset, casting to integer (%d)". Found while writing issue 022's test, which leaves
these keys out.

**Cause:** `ArrayKey.of` converts null to "" and a float to an int silently, and the translators normalized constant
keys of every type at translation (since issue 022 only strings and ints are).

**Fix:** to do: the notices in `ElementNodes.ArrayKeyNode`'s fallback (null, float, resource keys: not the hot int
and string paths), and in the array literal and built-in paths that convert keys, at the site's line.
