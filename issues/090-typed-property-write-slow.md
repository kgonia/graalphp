# 090: A typed property's write costs 19 ns compiled, an untyped one's 0.3 ns

**State:** Open. Found 2026-11-09 by bench/micro while measuring call costs (wiki/pe-round4-diagnosis.md's open
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

**Fix:** to do: the declaration and its type cached per site with the object's class key, the `int`/`string`/`bool`
checks inlined, the conversion only on a mismatch.

**Test:** the micro above (typed within 2x of untyped), and the phpt typed-property suite unchanged.
