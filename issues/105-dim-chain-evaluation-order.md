# 105: a key's expression in `$a[k1][k2]` does not see the base it changes: the base is fetched before the keys are evaluated

**State:** Open. Found 2026-11-10 while fusing `isset($a[$k])` with the read after it
([wiki/ceiling.md](../wiki/ceiling.md)); older than that work (the build of `f9ae538` answers the same).

**What happens:** php-src compiles the key expressions of an element access before it emits any fetch
(`zend_delayed_compile_dim`, `zend_delayed_compile_var`): the keys run first, left to right, and only then is the
base read and walked. So a key's expression that assigns the base, or a variable that is an earlier key, changes
what the access finds. The engine reads the base (and walks the earlier keys) first, then evaluates the key.

The cases are [105-dim-chain-order.php](105-dim-chain-order.php) (`g()` assigns the global `$a` a new array and
returns `'x'`; `gk()` the same and returns `'k'`):

| case | PHP 8.5.12 | the engine |
|---|---|---|
| `$a[gk()]` | the new array's element | the old array's |
| `$a['k'][g()]` | `"new"` | `"old"` |
| `$k = 'k'; $a[$k][g()]` | `"new"` | `"old"` |
| `$a['k']['y'][g()]` | `"new deep"` | `"old deep"` |
| `$a['k'][g()] ?? 'default'` | `"new"` | `"old"` |
| `isset($a['k'][gNew()])` after `unset($a['k']['x'])` | `true` | `false` |
| `empty($a['k'][gNew()])` after `$a['k']['x'] = ''` | `false` | `true` |
| `$i = 'k'; $a[$i][$i = 'x']` | `null` (reads `$a['x']['x']`: `$i` is read when the fetch runs) | `"old"` |
| `$i = 0; $b[$i][$i++]` | `"one-zero"` (`$b[1][0]`) | `"zero-zero"` |
| `$i = 0; $b[$i++][$i]` | `"zero-one"` | `"zero-one"` (same) |
| `$o->p['k'][gp()]` (`gp()` assigns `$o->p`) | `"new prop"` | `"old prop"` |
| `Box::$s['k'][gs()]` (`gs()` assigns `Box::$s`) | `"new static"` | `"old static"` |
| `$o->p['k'][go()]` (`go()` assigns the global `$o`) | `"new object"` | `"old prop"` |
| `$b['k'][(function () use (&$r) { $r = […]; return 'x'; })()]`, `$r` a reference to `$b` | `"new"` | `"old"` |
| `strtoupper($a['k'][g()])` | `"NEW"` | `"OLD"` |
| `"{$a['k'][g()]}"` | `"new"` | `"old"` |
| `$a['k'][g()][] = 'appended'` | `Error: [] operator not supported for strings` | `Error: Cannot use a scalar value as an array` |
| `$a['k'][g()] = …`, `$a[gk()] = …`, `$a['k'][g()] .= …`, `unset($a['k'][g()])` | | same as PHP |

16 of the 21 cases differ: every read, `isset`, `empty` and `??`, and the error of one append. The plain writes,
the compound assignment and `unset` agree.

**How often:** a key expression with a side effect on its own base is rare in WordPress's code; no test of the
suites (`tests/smoke`, PHPT, WordPress's PHPUnit) is known to fail on it. It is an observable difference all the
same.

**Fix (a sketch):** for a read, `isset`, `empty` or `??` of a chain whose keys are not all constants or plain
variables that no later key assigns: evaluate the keys into temporaries left to right, then fetch the base and
walk. A chain whose keys cannot run code (the common one) keeps today's shape, so no page pays.
A plain variable used as a key and assigned by a later key (`$a[$i][$i = 'x']`, `$b[$i][$i++]`) is read at the
fetch, not at its place in the source: php-src's operand is the variable itself.

**Check:** the cases' file as a smoke test (`tests/smoke/dim-chain-order.php`) against PHP's output;
`tests/smoke/isset-then-read.php` for the fused forms, which descend only through keys that run no code.
