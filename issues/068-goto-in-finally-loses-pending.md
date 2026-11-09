# 068: A goto inside a finally loses the pending goto out of its try

**State:** Fixed. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

**Symptom:** `try { goto L; } finally { goto M; ... M: echo "M "; }` with `L: echo "L ";` after the try
(`goto-inside-finally`):

| | Output |
|---|---|
| PHP 8.5 | `M L` |
| Truffle PHP | `M`: the jump to `L` is lost |

**Cause:** a goto that leaves a try through its finally keeps its target in the local `entering`
(`BytecodeTranslator.java:379-380`, `1079`, `1264`). A goto inside the finally writes the same local, so when the
finally ends, the pending target is gone. php-src keeps the pending jump in the finally's own `fast_call` variable,
and a jump within the finally doesn't touch it.

**Fix:** a finally whose body has a goto that sets `entering` keeps the pending target in a local of its own. It
puts the target back when the body ends, as php-src's finally keeps the pending jump in its `fast_call` variable.
Nested finally blocks each keep their own.

**Test:** `tests/smoke/goto-inside-finally.php`.
