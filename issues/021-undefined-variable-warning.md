# 021: A read of an unset variable doesn't warn "Undefined variable"

**State:** Fixed (branch `fix-warnings-conversions`), on bytecode and at file level; see "Still open" for the tree.

**Symptom:**
```php
function f() { echo $x; $y .= 'a'; $z++; return compact('w'); }
f();   // PHP: Warning: Undefined variable $x / $y / $z, compact(): Undefined variable $w; Truffle PHP: nothing
var_dump($GLOBALS['nope'], $_SESSION);   // PHP: Warning: Undefined global variable $nope / $_SESSION
```
Every read php-src makes with BP_VAR_R or BP_VAR_RW (an operand, an argument by value, a list() or foreach
subject, `.=`, `++`, `$x[k] .=`, a closure's `use`) warns; isset, empty, `??`, `??=`, by-reference uses, unset, writes
(also `$x[] =`, `$x[k] =`) and an arrow function's implicit capture don't. The reads went on silently with null.
Found by grep: no "Undefined variable" in src/main but compact()'s on bytecode.

**Cause:** a bytecode local that holds nothing (`Locals.stored` null) and a missing global read as null, with no
warning (ReadVar, the RW operations, SendVar, `GlobalValue`; FileVarNode and GlobalVarNode on the tree).

**Fix:** php-src's zval_undefined_cv and ZEND_FETCH_R's "Undefined %svariable" on the uncommon branch:
- `ReadVar`'s generic read warns when the local is unset, behind a branch profile (a site that never met an unset
  variable compiles a deoptimization there); its int and float specializations are unchanged (an unset local already
  left them). `ReadVarIfSet` is isset()'s, `??`'s and `??=`'s quiet read; `ReadVarIfDefined` an arrow function's
  capture, which leaves the arrow function's variable unset so its own read warns (BIND_LEXICAL's IMPLICIT flag).
- The read-modify-writes (`IncDecVar`, `CompoundVar`, `CompoundNested` and `IncDecNested` on an unset variable)
  warn, then go on with null and store their result before an exception the error handler threw leaves, as php-src
  does (the exception is pending while the opcode finishes); `$x[k] .=` makes `$x` the array first.
- `SendVar`, `SendElement`, `SendNested`, `SpreadVar` and `ForeachRefVar` warn for an unset variable passed by value.
- `GlobalValue` and `GlobalsElementValue` warn "Undefined global variable" unless quiet (isset); `GlobalRef` and
  `GlobalsElementRef` for a read-modify-write (`$GLOBALS['x'] .= `) warn before making the global.
- The object of a written or unset property (`$undefined->p = 1`, `$a['k']->p = 1`, BP_VAR_W and BP_VAR_UNSET) is
  read as isset() reads it (`BytecodeTranslator.delayedProp`'s writeFetched, `PropNode.writtenObject`); a
  read-modify-write's (`$a['k']->p .= 1`) warns.
- `global $x` at file level defines the global (ZEND_BIND_GLOBAL makes it null), so a later read doesn't warn; it
  was a no-op, which the warning showed (WordPress's plugin.php: `global $wp_filter; if ($wp_filter)`).
- File level: `FileVarNode.execute` ("Undefined variable"), `GlobalVarNode.execute` and `GlobalsDimNode.execute`
  ("Undefined global variable"), compact() (`CompactNode`, `DynamicCompactNode`), and the arrow function's quiet
  capture (`ClosureNode`). `PropNode.read` adds php-src's "Attempt to read property "p" on null" for a property
  read of a value that isn't an object (the reads of `$undefined->p`).
- Test: `tests/smoke/undefined-variable.php` (functions, methods, closures, arrow functions, generators, file level,
  a handler that throws).

**Still open:** on the tree, a read-modify-write of an unset variable at file level (`$x .= 'a'`) stops at a
throwing handler's exception instead of storing first, and a function the bytecode translator doesn't cover
(LocalVarNode) doesn't warn.
