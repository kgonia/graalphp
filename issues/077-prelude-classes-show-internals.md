# 077: Prelude SPL classes show their implementation in dumps and reflection

**State:** Open. Found 2026-11-09 while fixing the implicit state machines (055-071); confirmed on PHP 8.5 and on issues-state-machines 5fa1ee2.

**Symptom:** `var_dump(new ArrayIterator([1]))`:

| | Properties shown |
|---|---|
| PHP 8.5 | `storage` only |
| Truffle PHP | `storage`, `flags`, `slots`, `slotOf`, `position` |

The same holds for `print_r` and `var_export`, and for the other SPL classes written in PHP
(`src/main/php/prelude/spl.php`): their private state is ordinary properties, and their helpers are ordinary methods
that reflection lists (`SplFileInfo::entryPath`, `DirectoryIterator`'s `$entries` and `$position`). Two known
shortcuts of the same prelude:
- an `ArrayIterator` over an object copies its properties (`get_object_vars`) instead of working on them;
- `ARRAY_AS_PROPS` isn't implemented.

**Cause:** php-src's SPL classes keep their state in the C object and show what their `get_debug_info` and
`get_properties_for` handlers give. The prelude has no such hook.

**Fix:** to do. Mark prelude state and helpers as internal: hidden from the property table views (dumps, casts,
`get_object_vars`, `get_class_methods`, reflection), the way an internal class's are, and give each class php-src's
debug-info view.

**Test:** none yet.
