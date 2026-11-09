# 090: A typed property's write costs 19 ns compiled, an untyped one's 0.3 ns

**State:** Fixed 2026-11-09 (below). Found 2026-11-09 by bench/micro while measuring call costs (wiki/pe-round4-diagnosis.md's open
targets), on `fastcgi` (bytecode, JVM, Oracle GraalVM 25.4.4.1.1, CPUs 14-21).

**What happens:** the same loop, compiled at tier 2, one class with `public int $x = 0; public $y = 0;`:

| loop body | ns per iteration | PHP 8.5 (opcache JIT) |
|---|---|---|
| `$p->x = $p->x + 1` (typed `int`) | 19 | 6.5 (micro.php's `m_prop`) |
| `$p->y = $p->y + 1` (untyped) | 0.33 | |

`micro.php`'s `m_method` (`$this->x++` in a method) measures the same typed write, not the call: a method call not
inlined costs 8-9 ns (target/micro/calls.php).

**Where to look:** the typed write's path in `nodes/vars/PropNode.java` (`typedDecl`, the value's conversion in the
writing code's mode) and its bytecode operation: a lookup of the declaration by name, or the conversion, behind a
boundary on every write. php-src checks the property's type info from the slot (`ZEND_ASSIGN_OBJ` with
`ZEND_TYPE_IS_SET`), and its JIT inlines the `int` check.

**Why it matters:** WooCommerce and newer plugins declare typed properties; WordPress core 6.x a few. Not measured on
the Gates yet.

**Fix:** PropNode.checked() keeps, for a site with a constant name, the type of its first object's class by
`ClassKey` (as `accessibleKey`: the AST is shared by contexts, the key isn't per context) in one
`@CompilationFinal` field. For an object of that key, compiled code tests the value with `PhpType.fast` (the value
taken as it is, or int widened to float: the same in strict and coercive mode) and stores it; a conversion, a
TypeError, another class or a dynamic name go the old way (`checkedSlow`, behind the boundary). Memory: one field per
property site and one small object per typed site written.

Measured (target/micro/prop.php, CPUs 14-21, steady state, 2 rounds interleaved): `m_prop` 7.0-8.1 ns before,
0.9-1.4 ns after (PHP 8.5 6.5 ns); `m_propu` unchanged (0.22-0.24). CompilationTest's query-builder (typed `array`
and `string` properties) compiles to 27232 IR nodes against 27248 before.

**Test:** tests/smoke/typed-property-site.php (one site, six classes: int, float, ?string, a redeclaring subclass,
untyped, ?class; coercions and TypeErrors; one context and three) against PHP 8.5; the smoke typed-property tests
unchanged. A reference bound to a typed property was never checked: issue 092.
