# 102: An array offset of an unusual type: PHP's deprecations and warnings are missing, a resource offset throws

**State:** Open. Found 2026-11-10 by a check written for [wiki/beat-php.md](../wiki/beat-php.md): the fast path
for a string key of [097](097-builtins-behind-a-boundary.md) touched the key conversion, so 39 cases of offsets of
every type were compared with PHP 8.5.12 (`target/scratch/offdep.php`, not tracked). 18 cases are identical, 21
differ. The differences are older than that night's changes (`git log -S"Cannot access offset of type"`: the
message has been in `ArrayKey` since the first commits), and none is in a returned value except where we throw.

**What happens:** the value a key converts to is right everywhere (5.9 → 5, null → `""`, true → 1, NAN → 0 as
PHP 8.5). What PHP says while converting is missing:

| offset | where (cases) | PHP 8.5.12 | ours |
|---|---|---|---|
| a float with a fraction (5.9, 6.9, 1.7) | `$a[5.9]` read, `isset`, `empty`, `??`, write, `unset`, an array literal's key, `isset($string[1.7])`, `array_column`'s index (9) | `E_DEPRECATED` "Implicit conversion from float 5.9 to int loses precision", then the value | the value, silently |
| null | read, `isset`, `??`, write, an array literal's key, `array_column`'s index (6) | `E_DEPRECATED` "Using null as an array offset is deprecated, use an empty string instead" (new in 8.5), then the value | the value, silently |
| a resource | read, write, `array_key_exists` (3) | `E_WARNING` "Resource ID#5 used as offset, casting to integer (5)", then the element under 5 | `TypeError: Cannot access offset of type resource on array` |
| an array in `isset` | `isset($a[[]])` (1) | `TypeError: Cannot access offset of type array in isset or empty` | `TypeError: Cannot access offset of type array on array` |
| NAN, INF, 1e30 | read (3) | `E_WARNING` "The float NAN is not representable as an int, cast occurred" (new in 8.5; for NAN also the "loses precision" deprecation), then the value | the value, silently |

`array_key_exists` already raises the first two (`ArrayFunctions.keyExists`), and `array_flip`/`array_combine`'s
keys the resource warning (`ArrayFunctions.zvalKey`): the element access is what lacks them.

**Where:** `runtime/ArrayKey.of` (the conversion, and the `TypeError` for every other type: it has no context to
raise through and no branch for a resource); `nodes/vars/ElementNodes` (line 334: the same `TypeError` for
`isset`); the array literal and `unset` go through `ArrayKey.of` too.

**Why it matters:** [the owner's rule](../CLAUDE.md) is that observable behaviour is PHP's. A site with
`E_DEPRECATED` in its error log or `WP_DEBUG` on prints PHP's lines and not ours; a resource offset (rare: a
`curl` handle or a stream used as a key before PHP 8's objects replaced most resources) ends the request here and
not in PHP. WordPress's front page raises none of the five (its 10,000 renders are byte-identical with PHP's).

**Fix:** the slow branches of the key conversion (a double that is not integral, null, a resource) are already off
the fast path (`ArrayKey.of` tests `Long` and `TruffleString` first); they need the context to raise through and
the access kind for the `TypeError`'s wording ("on array", "in isset or empty", "in unset"). Tests: the 39 cases as
a `.phpt` with PHP 8.5.12's output as the expectation.
