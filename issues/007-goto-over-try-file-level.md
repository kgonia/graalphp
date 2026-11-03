# 007: A goto over a try at file level escapes as a Java exception

**State:** Fixed for labels at the top level; a goto into an if, a try or a catch there is open

**Symptom:** php-src's `Zend/tests/try/gh22280`: `goto d;` at file level, over a `try`/`catch`, to `d: try { ... } finally { ... }`. A Java `GotoNodes$Jump` escapes instead of PHP printing `try`, `finally`, `done`. `Zend/tests/jump/jump15` and `jump17` end the same way.

**Cause:** a file's top-level labels were never collected: `Translator.fileStatements` translated `AST_LABEL` to a
no-op and the file's statements to a plain block, so any goto at the top level, not only one over a try, unwound past
the file as `GotoNodes$Jump`.

**Fix:** the top level's labels (braced namespaces and blocks in it included, which php-src compiles as top-level
statements) make the file's statements a `GotoNodes.LabeledBlockNode`, as a statement list's do. gh22280 and jump15
pass. Test: `tests/smoke/goto-top-level.php` (forward, backward, over a try, out of a foreach and a switch, out of a
try body through its finally, in a block).

**Still open:** a goto at the top level into an if, a try or a catch, which php-src allows (`jump17` jumps into a
catch). The tree's goto only unwinds to a label in a statement list around it; the bytecode jumps into an if or a
block, not into a try either. It is now the error "Unsupported goto into a statement at a file's top level" instead
of a Java exception. It goes with moving top-level code to bytecode, and the bytecode translator entering a try or a
catch.
