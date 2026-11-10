# 099: `get_object_vars` and a write to `$this->$key` look every property up in the shape, uncached

**State:** Open. Found 2026-11-10 by the kernels of [wiki/beat-php.md](../wiki/beat-php.md): the `post` kernel
(`new WP_Post($row)`, 24 properties) is the one furthest from PHP, 8–13× in the container runs.

**What happens:** `WP_Post::__construct` (and `WP_Term`, `WP_User`, `WP_Comment`, `WP_Site`: every row class of
WordPress) is

```php
foreach ( get_object_vars( $post ) as $key => $value ) {
    $this->$key = $value;
}
```

Host JVM (P-cores), 40 objects per op, 117 µs per op = 2.9 µs per object = 122 ns per property. Where the samples
are (`target/scratch/kjfr.sh post:1500`, 2816 samples):

| instruction | share | what it does there |
|---|---|---|
| `ObjectVars` | 59.1% | per property a lookup in the shape's property map (`TrieNode$BitmapNode.find` 9.0, `TriePropertyMap.hash` 2.4) and a `PhpArray$Hash.add` that searches the table before it appends (`Hash.find` 15.2, `Hash.add` 8.8), though an object's property names are distinct by construction |
| `WritePropNamed` | 24.9% | the site sees 24 names × 24 shapes (the object grows by one property each write), so the library is in its generic state: `BitmapNode.find` 8.6, `TriePropertyMap.hash` 4.0, `DynamicObjectLibraryImpl$Generic.put` 1.7 |
| `PropName` | 10.2% | the name of `$this->$key` is interned on each write (`Str.key`: `ConcurrentHashMap.get` 2.1, `TruffleString.equals` 3.0 + 1.7), though the key came out of an array that holds it interned |

PHP does one hash lookup per property on each side (`zend_std_get_properties` hands the table over;
`zend_std_write_property` finds the slot by name in the class's `properties_info`).

**Cost in WordPress:** small. A hot render of the front page constructs 37 `WP_Post`, 8 `WP_Term`, 2 `WP_User`:
≈ 0.13 ms of ≈ 25 ms. It is the largest ratio among the kernels, not the largest cost; the item is here so that the
`post` kernel's number is explained.

**What a fix looks like:**

- `ObjectVars`: walk the shape's property list once (keys and locations together) instead of a lookup per key, and
  append to the new array without the search (the keys are distinct).
- `WritePropNamed` with a name that is not a constant: a cache on (interned name, shape) → location is useless here
  (24 × 24 states); what works is what PHP does, the class's declared property → location by one lookup on the
  class (a declared property keeps its location in every shape of the class).
- `PropName`: a key that is already interned (the array's keys are) needs no lookup; a one-entry identity cache is
  enough for a loop over one array.

**Test:** none yet (no change). The behaviour is covered by `tests/smoke/property-visibility.php`,
`property-names.php` and `variable-variables.php`; the cost by the `post` kernel of `tests/gate2/kernels/hot.php`.
