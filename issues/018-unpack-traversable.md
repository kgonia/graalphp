# 018: Unpacking a Traversable fails

**State:** Fixed

**Symptom:** `f(...$iterator)` and `[...$iterator]` threw `TypeError: Only arrays and Traversables can be unpacked,
ArrayIterator given` for any object; php-src walks the object's iterator (ZEND_SEND_UNPACK, ZEND_ADD_ARRAY_UNPACK).

**Cause:** `ArgumentsNode.addSpread` and `ArrayLiteralNode.spread` took only arrays.

**Fix:** both walk a Traversable as php-src's object iterator does (`ForeachNode.traverse`: getIterator() followed,
rewind, then per element valid, current, key, next; a Generator through its own steps). Arguments go by position (a
string key is issue 017); one that lands on a by-reference parameter is passed as a new reference with php-src's
warning "Cannot pass by-reference argument N of f() by unpacking a Traversable, passing by-value instead"; a key that
is neither int nor string is the Error "Keys must be of type int|string during argument unpacking" ("during array
unpacking" for an array). Array unpacking keeps string keys and renumbers int and numeric-string ones; a value that
can't be unpacked is a TypeError for an object, an Error for anything else, as php-src. Test:
`tests/smoke/unpack-traversable.php`.
