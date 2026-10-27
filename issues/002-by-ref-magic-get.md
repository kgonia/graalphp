# 002: `&__get` and `&offsetGet` aren't written through

**State:** Open

**Symptom:** a write into what a by-reference `__get` or `offsetGet` returns doesn't reach the object:
```php
class D {
    private $hidden = ['h' => 1];
    function &__get($n) { return $this->hidden; }
}
$d = new D;
$d->magic['new'] = 2;   // PHP: $hidden is ['h' => 1, 'new' => 2]; Truffle PHP: creates a property 'magic'
```
With a by-value `__get` PHP raises "Indirect modification of overloaded property" and changes nothing; Truffle PHP raises nothing (and for a missing property creates it).

**Cause:** functions now return references (`ff344d6`), but `PropNode`'s write paths (`makeRef`, `arrayForWrite`) call `__get` through `PhpObject.magicProperty`, which goes through `PhpFunction.invoke` and dereferences the result; for a missing property they don't call `__get` at all. `DimNode` does the same with `offsetGet`. php-src's `zend_std_get_property_ptr_ptr` returns NULL when the class has `__get`, so the write fetch calls `read_property(BP_VAR_W)` and writes through the reference it returns.

**Fix:** a write fetch of a property the scope can't access or that doesn't exist, on a class with `__get`, calls it keeping the reference (a `PhpFunction` call that doesn't dereference) and writes through it, or raises the notice and writes to a temporary; the same for `offsetGet` on an `ArrayAccess` element. WordPress core declares no `&__get`.
