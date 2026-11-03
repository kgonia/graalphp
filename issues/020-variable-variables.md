# 020: Variable variables (`$$name`, `${expr}`) aren't supported

**State:** Fixed (COMMIT)

**Symptom:** any function, method, closure or generator using `$$name` failed at its first call, and a file using it
at its top level failed when included:
```php
function f($key, $value) { $$key = $value; return $x; }
f('x', 1);   // PHP: 1; Truffle PHP: Fatal error: Unsupported variable variable (AST_VAR at ...)
```
Real code hit by it: five WooCommerce functions, Sabberworm's `expandDimensionsShorthand`, `CLIRunner::backfill`,
`Jetpack_Signature::sign_request`, `BaseInstaller::templatePath` (wiki/bytecode-dsl.md, "What is left").

**Cause:** `Translator.place` raised "Unsupported variable variable" for an `AST_VAR` whose name isn't a constant;
`BytecodeTranslator` had no place for it either, so a function using it fell back to the tree and failed there.

**php-src's rules** (zend_compile_simple_var_no_cv, zend_fetch_var_address_helper, ZEND_UNSET_VAR,
ZEND_ISSET_ISEMPTY_VAR, zend_fetch_this_var, zend_compile_global_var), which the fix follows:
- The name is converted when the variable is fetched (zval_try_get_tmp_string): an array with "Array to string
  conversion", an object by `__toString()` or the Error "Object of class X could not be converted to string".
- The variable is the running code's symbol table's (ZEND_FETCH_LOCAL): a function's variables, those extract(),
  an include or `$$name` made; the globals at a file's top level. A superglobal's or `GLOBALS`'s name in a function
  is a local's name; at a file's top level `$$n` with n = `_GET` is the superglobal, `GLOBALS` is undefined.
- The fetch type decides: R warns "Undefined variable $x" and gives null; RW (`op=`, `++`, the container of
  `$$n[k] op= v`) warns and makes it; W (assignment, `[]`, a reference, a by-reference argument, foreach by
  reference, the object of a property written) makes it silently; IS (`??`, the container in isset()) doesn't warn;
  UNSET (`unset($$n[k])`, `unset($$n->p)`) doesn't make it. `isset($$n)`/`empty($$n)` look the name up only.
- "this" is never in a symbol table: read (R, IS) it is `$this` (or "Undefined variable $this" without one);
  fetched for W or RW it is the Error "Cannot re-assign $this" (also `$$n->p = v`, `$r = &$$n`), for UNSET "Cannot
  unset $this"; `isset($$n)` is false and `unset($$n)` does nothing.
- Order: a name that is a variable is read when the place is fetched, after an assigned value (`$$n = ($n = 'b')`
  writes `$b`); any other name expression is evaluated before the value.
- `global $$n` fetches the global by the name, then binds the local by the name (each converting it).

**Fix:** on bytecode, `$$name` is a fourth kind of `RefPlace` (next to static properties, superglobals and
`$GLOBALS[key]`), so every place form that takes one (assignment, compound assignment, `++`, `??=`, nested
elements, properties of it, references, by-reference arguments, foreach targets, list() targets) takes it. Its
operations (`VariableValue`, `VariableRef`, `UnsetVariable`, `BindVariable`, `GlobalVariable`) reach the call's
variables by name through its `BytecodeScope`, made at the first such fetch, as an include or extract() makes it:
a name the function has a local for is that local (by `Locals`' rules), any other one lives in the scope's table,
where compact(), extract() and included files see it. A file's top-level `$$name` is the tree's
`VariableVariableNode` (the including call's scope, else the globals); both run its static methods, which hold the
rules above. A write through `$$name` makes the local a reference held only by it (as a global is), which
behaves as a value and costs only that function; functions without `$$name` don't change.

**Not covered:** `global $$name` at a file's top level stays unsupported (the tree's "dynamic global variable
name"); a reference in list() (`[&$$a] = $v`) keeps a function on the tree whatever the target (bytecode doesn't
cover references in list() yet), where `$$name` fails as before.

Test: `tests/smoke/variable-variables.php` (with `variable-variables.inc`, `variable-variables-this.inc`).
