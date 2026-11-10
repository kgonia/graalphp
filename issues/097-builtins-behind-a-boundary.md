# 097: A built-in function is a call out of the compiled code, and a string key two more

**State:** Partly fixed 2026-11-10 on `fastcgi` (three functions and the key, below); the general cost is Open.
Found 2026-11-10 by the kernels of [wiki/beat-php.md](../wiki/beat-php.md): `utf8_uri_encode` (`uri`, 3–4× PHP) and
`_wp_array_get` (`aget`, 2–3× PHP) are loops of `ord()`, `chr()`, `array_key_exists()` and `$array[$key]`.

**What happens:**

1. Every built-in function that is not an operation of its own runs through `BuiltinRootNode.call`, a
   `@TruffleBoundary`: the compiled PHP function boxes the arguments into an `Object[]`, calls out of line, and the
   body starts from `Object`s again (type tests, `long` boxed and unboxed). For `ord($s[$i])` that is an array and
   a call around one byte read. In PHP `strlen`, `count`, `is_array`, `array_key_exists` and `in_array` with a
   constant array are opcodes of their own, and `ord` and `chr` a direct C call with the arguments in the VM's
   slots.
2. `$array[$key]`, `isset($array[$key])` and `array_key_exists($key, $array)` with a string key that is not a
   constant called `ArrayKey.ofString` out of line to learn whether the string is an integer (`"12"` is the key 12),
   and that one called `TruffleString`'s parser out of line: two calls for a key like `"post_type"`.

In a hot render of the front page (in-situ profile, main thread, drained): built-in bodies ≈ 22.5% of the samples,
no single one above 2.4% (`clone` 2.4, `md5` 1.7, `json_encode`'s strings 1.2, `array_search` 1.2,
`array_diff_key` 1.0, `array_map` 0.9, `preg_*` pattern cache 0.6).

**Fixed here:**

- `ord`, `chr`, `array_key_exists` are operations of the bytecode (`PhpBytecodeRootNode.Ord`, `Chr`,
  `ArrayKeyExists`; `BytecodeTranslator.INTRINSICS`): a one-byte string's byte, a code 0–255, a key of type int or
  string in an array are compiled in place; every other operand (and every error) takes the built-in's own body
  through `builtin(...)`, so the results, the conversions, the deprecations and the TypeErrors are the built-in's.
  The test found those bodies behind PHP 8.5 and brought them up to it: `ord` of a string that is not one byte
  long and `chr` of a code outside 0–255 are deprecated there, as is a `null` key for `array_key_exists`; a float
  key with a fraction raises "Implicit conversion from float"; a key of another type is the TypeError "Cannot
  access offset of type T on array"; `ord()` checks its argument count and deprecates `null`.
  A function of that name declared in a namespace, a call with named or unpacked arguments, and a call by a
  variable name are ordinary calls, as before for the other intrinsics.
- `ElementNodes.ArrayKeyNode.string`: a key that is empty, longer than 20 bytes, or starts with neither a digit nor
  `-` cannot be an integer and is the key itself, decided in the compiled code; only the rest goes to
  `ArrayKey.ofString`.

Host JVM (P-cores), `target/scratch/hot.php`, ns per op, final round (one run each; a run's noise is ±20%, see
[101](101-compiled-state-lottery.md)):

| kernel | before | after |
|---|---|---|
| `uri` (`utf8_uri_encode` over 40 titles) | 132,982 | 106,693 |
| `aget` (`_wp_array_get`, 3 levels) | 10,562 | 9,306 |
| `hasf` (`has_filter` + `apply_filters`, none attached) | 19,103 | 16,177 |

**Open:** the 22.5%. What a built-in costs beyond its body is the boundary (arguments boxed, no inlining, no
constant folding of a constant argument, no escape analysis through it). Candidates by the profile, each ≤ 2.5% of
a render: `clone` (shape and `__clone` looked up on each call), `md5` (`MessageDigest.getInstance` per call),
`in_array`/`array_search` on a small array, `array_merge` of two small arrays, `str_replace` with constant
arguments, `preg_match`'s pattern cache lookup (a `HashMap` get by the pattern string on every call: the site
could keep its last pattern). No one of them moves a render by more than 2%; together with [099](099-object-vars-and-dynamic-property-writes.md)
they are the "builtin" quarter of the gap to PHP.

**Test:** `tests/smoke/intrinsics-ord-chr-key.php`: every operand type of the three functions in a loop (so the
compiled form runs too), the integer-like string keys (`"5"`, `"05"`, `"-3"`, `"-0"`, `" 1"`,
`"9223372036854775808"`), `null`, `bool` and `float` keys, an object with `__toString`, and the two hot loops the
kernels are made of. The kernels: `uri`, `aget`, `hasf` of `target/scratch/hot.php`.
