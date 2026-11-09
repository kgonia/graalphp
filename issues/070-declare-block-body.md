# 070: The body of a `declare(...) { }` block never runs

**State:** Fixed. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

**State note:** not a state machine, found while reading the translator for the audit.

**Symptom:** `declare(ticks=1) { echo "hi\n"; }` (`declare-ticks-block`): PHP 8.5 prints `hi`, Truffle PHP prints
nothing for it.

**Cause:** `BytecodeTranslator.java:1006-1012` handles `AST_DECLARE` as a directive only (the `strict_types`
position check). The node's statement list is never translated. Related: a goto into such a block throws
`Unsupported` (`BytecodeTranslator.java:1094`).

**Fix:** a declare block's statements are translated in place, as `zend_compile_declare` compiles them as a statement.
A function declared in the block is declared when the block runs. A goto finds labels inside the block (`labelsInside`),
as it does inside an if.

**Test:** `tests/smoke/declare-ticks-block.php`.
