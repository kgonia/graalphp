# 092: A reference to a typed property takes any value

**State:** Open. Found 2026-11-09 while writing issue 090's smoke test (tests/smoke/typed-property-site.php), on
`fastcgi` (edadb35); confirmed on PHP 8.5.12.

**Symptom:**

```php
class IntBox { public int $v = 0; }
$box = new IntBox();
$ref = 5;
$box->v = &$ref;
$ref = '7';                  // PHP: int(7)                Truffle PHP: string(1) "7"
$ref = 'no';                 // PHP: TypeError "Cannot assign string to reference held by property IntBox::$v of
                             //      type int", $box->v stays int(7)      Truffle PHP: string(2) "no"
function f(&$r) { $r = 2.5; }
f($box->v);                  // PHP: Deprecated (float 2.5 to int), int(2)   Truffle PHP: float(2.5)
```

The property's type no longer holds: later reads of `$box->v` see a string or a float.

**Cause:** php-src keeps a reference's type sources (`ZEND_REF_HAS_TYPE_SOURCES`, `zend_property_info_source_list`):
the typed properties a reference is bound to. Every assignment through a reference with sources goes through
`zend_verify_ref_assignable_zval` (coerced in the assigning code's mode against all the sources, a TypeError
naming the first failing one). Here `PhpRef` has no sources; only the property write (PropNode.writeOn) checks
the type, and a write through the reference goes past it.

**Fix:** to do. `PhpRef` gets its sources (null for almost all references, so the check is one field test on the
references' write paths: RefWrites, by-reference arguments, foreach by reference, list() by reference, ++/--,
compound assignments); binding a typed property to a reference (`=&`, `&$o->p` arguments, foreach) adds the
property's declaration, and unbinding (unset, a reassigned reference) removes it. The checks with the sources'
types, as zend_verify_ref_assignable_zval: strict if the assigning code is, the TypeError's message with the
first source.

**Test:** a smoke test with the cases above, plus a reference held by two typed properties (int and ?int, float and
int: php-src's Zend/tests/type_declarations/typed_properties_*ref* cases), compared with PHP 8.5.
