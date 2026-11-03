# 022: A read of a missing array key, string offset or scalar's offset doesn't warn

**State:** Fixed; see "Still open" for the tree.

**Symptom:**
```php
$a = []; $s = 'abc'; $n = null;
echo $a['k'], $a[3], $s[5], $s['1x'], $n['k'];
// PHP: Warning: Undefined array key "k" / Undefined array key 3 / Uninitialized string offset 5 /
//      Illegal string offset "1x" / Trying to access array offset on null; Truffle PHP: nothing
$a['c'] .= 'x'; $a['i']++; [$p, $q] = [1];   // PHP: Undefined array key "c" / "i" / 1
echo $s['x'];   // PHP: TypeError: Cannot access offset of type string on string; Truffle PHP: "a"
$o = new stdClass; $o['k'];   // PHP: Error: Cannot use object of type stdClass as array; Truffle PHP: null
[$i] = 5;   // PHP: Warning: Cannot use int as array
```

**Cause:** `ElementNodes.ElementReadNode` served every element read alike, isset()'s too: a missing element was
PhpNull.NULL, a string offset was converted with `Convert.toLong` and out of range was "", anything else null. The
translators also normalized every constant key (`ArrayKey.of`), so `$s[1.5]`, `$s[true]` and `$s[null]` reached a
string as ints, without php-src's "String offset cast occurred".

**Fix:** `zend_fetch_dimension_address_read` and `zend_fetch_dimension_address_inner`, by fetch type:
- `ElementReadNode` is the BP_VAR_R read (FETCH_DIM_R, FETCH_LIST_R, arguments by value, the last element of a
  read-modify-write): each warning on the branch that already returned null. A string key's miss warns in the call
  SlotCacheNode makes for a miss anyway (a read's lookup, `createRead`), so its inline code is the same as before; a
  deoptimization at each such site instead added 3% to 4% IR nodes to CompilationTest's large methods (over their
  budgets). An int key's and a string offset's warnings are behind one shared branch profile.
- `ElementIfSetNode` is the quiet read (BP_VAR_IS, BP_VAR_UNSET, the containers of writes): isset(), `??`,
  `ElementIfSet`, `IssetElement`, unset's chain, `DimNode.isSet/readIfSet/unset`, and ElementChain's fallback.
- A read-modify-write's containers (`$a[k1][k2] .= v`, FETCH_DIM_RW: `ElementChain.forUpdate`) warn for a missing
  element before it is created; a handler's exception leaves it uncreated, as `zend_undefined_index_write`.
- `ListElement` and the tree's `ListAssignNode` warn for a missing key and "Cannot use %s as array" for a scalar.
- Constant keys are normalized at translation only when they are strings or ints, as php-src's compiler does
  (zend_handle_numeric_dim).
- Test: `tests/smoke/undefined-key.php`.

**Still open:** on the tree (file level), a nested read-modify-write (`$a['x']['y'] .= 's'`) reads its containers as
plain reads ("Trying to access array offset on null" instead of a second "Undefined array key"). The key conversions'
own deprecations (`$a[null]`, `$a[1.5]`) are issue 025.
