# 058: An included file's method call cache ignores the class scope: private methods become callable

**State:** Open. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

**Symptom:** one file, included by `A::render` and by `B::render`, returns `$obj->hidden()` for an `A` object, and
`hidden` is private in `A` (`method-cache-include-scope`):

| | Call from `A` | Call from `B` |
|---|---|---|
| PHP 8.5 | `A::hidden` | `Error: Call to private method A::hidden() from scope B` |
| Truffle PHP | `A::hidden` | `A::hidden` |

This breaks visibility, so it is a security problem as well as a correctness one: templates included from several
classes are common in WordPress plugins.

**Cause:** an included file runs in its includer's class scope, so its scope changes between includes, as a closure's
can. `MethodSite.isScoped` (`MethodSite.java:49`) treats only closures as scope-dependent. A file root's method
cache (`PhpBytecodeRootNode.java:4113`, `4324`) keeps the lookup made, with visibility checked, in the first scope.

**Fix:** to do. Treat a file root like a closure: its lookups depend on the scope.

**Test:** `tests/smoke/method-cache-include-scope.php` (with `.inc`).
