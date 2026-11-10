# 104: `false` that becomes an array on a write: PHP's deprecation is missing

**State:** Open. Found 2026-11-10 by `tests/smoke/ref-element-writes.php`, written for the change to
`Places.refArrayForWrite` of [wiki/ceiling.md](../wiki/ceiling.md). Older than that change: the build before it
(`f9ae538`) gives the same output.

**What happens:** `$a = false; $a['k'] = 1;` (and `$a[] = 1`) makes `$a` an array in PHP and here. PHP 8.1 and
later also raise `E_DEPRECATED` "Automatic conversion of false to array is deprecated"; here nothing is raised.
null and an unset variable become an array silently in both. The test's seven cases all lack it: a by-reference
parameter, a reference shared with a copy, a plain local, a global, `$q = &$this->vars`, a property, a static
property.

**Where:** every place that turns null or false into a new array tests both in one condition and has no branch
for the diagnostic: `Variables.becomesArray` (used by `bytecode/Locals.arrayForWrite` and
`nodes/vars/LocalVarNode`), `Places.otherForWrite` (the value behind a reference; it has no site to raise
through), `PropNode` line 898 (a property). An element of an array that is false (`$a['x'] = false;
$a['x']['y'] = 1;`) and a static property were not looked at one by one: the test's static case lacks the line
too.

**Why it matters:** [the owner's rule](../CLAUDE.md): observable behaviour is PHP's. A site with `E_DEPRECATED`
logged, or an error handler that counts or throws on it (PHPUnit's `convertDeprecationsToExceptions`), sees PHP's
line and not ours. WordPress's front page does not raise it (its renders are byte-identical with PHP's).

**Fix:** false is already off the fast path at each of these places (the branch that creates the array); it needs
the diagnostic there, raised through the write's site before the array is made, as php-src does
(`zend_false_to_array_deprecated`). `Places.refArrayForWrite`'s callers pass their node. Then remove the
`set_error_handler` line from `tests/smoke/ref-element-writes.php`, whose expected output then has the seven lines
(the two from a float and a null key stay hidden until [102](102-array-offset-diagnostics.md)).
