# 002: `&__get` and `&offsetGet` aren't written through

**State:** Fixed (branch `fix-objects-calls`, commit "&__get and &offsetGet written through...")

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

**Fix:** as planned: a write fetch (`PropNode.makeRefOn`, `containerForWriteOn`, which the bytecode's nested writes now take whole instead of reading the property again) of a property that doesn't exist or the scope can't access, on a class with `__get`, calls it keeping the reference (`PhpObject.magicGetRef`, `PhpFunction.invokeRef`): a reference is written through, an object used as it is, any other value raises "Indirect modification of overloaded property C::$p has no effect" and is a temporary. `offsetGet` on an `ArrayAccess` element the same (`DimNode.overloadedForWrite`, `overloadedRef`; `ElementChain`, `SendElement`, `elementRef` on bytecode): php-src's notice "Indirect modification of overloaded element of C has no effect" where Truffle PHP threw an Error "Indirect modification of overloaded element". WordPress core declares no `&__get`. Test: `tests/smoke/by-ref-magic-get.php`.

Simplified (`ponytail:`): on the tree, a nested write into an ArrayAccess element whose offsetGet gives an object or a temporary (`$o->p['k']['x'] = 1` with `$o->p` an ArrayAccess) isn't taken by the caller.
