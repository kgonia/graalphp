# 007: A goto over a try at file level escapes as a Java exception

**State:** Open

**Symptom:** php-src's `Zend/tests/try/gh22280`: `goto d;` at file level, over a `try`/`catch`, to `d: try { ... } finally { ... }`. A Java `GotoNodes$Jump` escapes instead of PHP printing `try`, `finally`, `done`. `Zend/tests/jump/jump15` and `jump17` end the same way.

**Cause:** to find.

**Fix:** to do.
