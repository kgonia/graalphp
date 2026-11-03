# 027: A read of a missing property doesn't warn "Undefined property"

**State:** Open

**Symptom:**
```php
$o = new stdClass;
echo $o->p;   // PHP: Warning: Undefined property: stdClass::$p; Truffle PHP: nothing
```
**Cause:** `PropNode.missing` returns null when the class has no `__get`, without zend_std_read_property's warning
(BP_VAR_R and BP_VAR_RW only; isset() and `??` read with ReadPropIfSet). Issue 021 added the warning of a property
read on a value that isn't an object ("Attempt to read property "p" on null"), not this one.

**Fix:** to do: the warning in the reads that are php-src's FETCH_OBJ_R and FETCH_OBJ_RW (not the containers of
writes, unset, or isset), on the branch that already handles a missing property.
