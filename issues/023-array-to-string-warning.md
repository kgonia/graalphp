# 023: Converting an array to a string doesn't warn "Array to string conversion"

**State:** Fixed (branch `fix-warnings-conversions`)

**Symptom:**
```php
$a = [1];
echo $a, 'x' . $a, "$a", (string) $a, strval($a), implode(',', [[1]]);
// PHP: Warning: Array to string conversion, once per conversion, then "Array"; Truffle PHP: "Array" silently
```
Only serialize()'s `__sleep` names warned (Serialization.stringOf).

**Cause:** `Convert.toStr` gave "Array" for an array without php-src's warning (zval_get_string_func), and the
nodes that convert (ToStrNode, the interpolation's PartNode, ConcatNode, the `(string)` cast, echo and print, `.=` on
an element, the implode intrinsic) went through it.

**Fix:** `Convert.arrayToStr(site)` raises the warning, at site's statement, or where user code runs now (a
built-in's call) when there is no site, and gives "Array". `Convert.toStr` takes that branch for an array (the
built-ins' conversions: strval, settype, implode, printf's `%s`...); the nodes pass themselves (`Convert.toStr(site,
value)`, ToStrNode's own array specialization), so the line is the statement's on bytecode too. The branch is the one
that already returned "Array": no new work for the other types. A handler that throws stops the conversion: `$s .= []`
leaves `$s` as it was, implode() returns nothing.

**Still open:** a built-in whose string parameter php-src's zpp checks (`strlen($array)`: TypeError "strlen():
Argument #1 ($string) must be of type string, array given") but which reads it with `Args.str` (181 call sites, with
no function or parameter name) converts the array, now with the warning, instead of throwing. That is the built-ins'
parameter checking (issue 008's family), not this conversion; the test leaves it out.

Test: `tests/smoke/array-to-string.php` (echo, print, concatenation, `.=`, interpolation, casts, strval, settype,
implode, printf/sprintf, comparisons that don't convert, the TypeError of a string parameter, methods, closures,
generators, file level, a handler that throws).
