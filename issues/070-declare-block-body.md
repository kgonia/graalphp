# 070: The body of a `declare(...) { }` block never runs

**State:** Open. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

**State note:** not a state machine, found while reading the translator for the audit.

**Symptom:** `declare(ticks=1) { echo "hi\n"; }` (`declare-ticks-block`): PHP 8.5 prints `hi`, Truffle PHP prints
nothing for it.

**Cause:** `BytecodeTranslator.java:1006-1012` handles `AST_DECLARE` as a directive only (the `strict_types`
position check). The node's statement list is never translated. Related: a goto into such a block throws
`Unsupported` (`BytecodeTranslator.java:1094`).

**Fix:** to do. Translate the block's statements in place. `ticks` itself has no effect without
`register_tick_function`, which the runtime doesn't have.

**Test:** `tests/smoke/declare-ticks-block.php`.
