# 024: Concatenation converts its operands in an order the reference doesn't

**State:** Fixed (branch `fix-warnings-conversions`); one opcache quirk is left, below.

**Symptom:** with g() echoing `[g]` and returning an object whose `__toString` echoes `[toString]`:
```php
echo g() . false . g();    // PHP (opcache on): [g][g][toString][toString]; Truffle PHP: [g][toString][g][toString]
echo (string) g() . g();   // PHP: [g][g][toString][toString]; Truffle PHP: [g][toString][g][toString]
echo g() . "$o";           // PHP: [g][toString o][toString g]; Truffle PHP: [toString o] before [g]'s conversion
echo "{$a->f()}{$b->f()}"; // PHP: both calls, then both conversions (FAST_CONCAT); Truffle PHP: each in turn
```
Without opcache, php-src gives Truffle PHP's order for the first three. The reference runs with
`opcache.enable_cli=1`, and opcache's block pass (Zend/Optimizer/block_pass.c) rewrites concatenations:
`CONCAT(X, '')` and `CONCAT('', X)` become `CAST(STRING, X)`, and `CONCAT(CAST(X), Y)` / `CONCAT(Y, CAST(X))`
take X itself, so its conversion moves into the concatenation, after Y is evaluated. php-src's compiler makes a
two-part interpolation a FAST_CONCAT and a one-part one a CAST (zend_compile_rope_finalize); only three parts and
more are a rope, which converts each part as it is evaluated.

A `.` chain itself was right: php-src compiles `a . b . c` as nested ZEND_CONCATs, each converting its two operands
when it runs, so a and b are converted after b is evaluated and before c is: ConcatChainNode's order.

**Fix:** both translators build a concatenation from its operands as the block pass leaves them
(`Translator.concatParts`, `BytecodeTranslator.concatParts`): empty literals ('', null, false) dropped, an operand
cast to a string ((string) x, "$x", a nested concatenation left with one operand) taken as the value itself; then
none is "", one is a `(string)` cast, two are a ConcatNode, more a ConcatChainNode. An interpolation of one part is a
cast, and of two expressions a concatenation joined as a chain is (both evaluated, then converted), as php-src
compiles them; with a literal part, the rope's order is FAST_CONCAT's, and it stays a rope (a ConcatNode's inlined
string concatenation was larger code). Adjacent literals are joined at translation (the tree
did so before for pairs). Warnings take their place in that order (an "Array to string conversion" or "Undefined
variable" between the conversions).

Test: `tests/smoke/concat-order.php` (matches the reference with opcache, as configured; it differs from
`-d opcache.enable_cli=0` in the cases above).

**Not followed:** when the cast's operand is a variable assigned later in the same expression, the block pass reads
the variable at the concatenation (`(string) $o . ($o = new T('z'))` converts the new object); Truffle PHP converts
the value read where the cast was written.
