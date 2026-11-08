# 062: `DirectoryIterator`'s `SplFileInfo` methods describe the first entry

**State:** Open. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

**Symptom:** a directory with a file `f.txt` and a subdirectory `sub` (`directoryiterator-entry-type`):

| | Output |
|---|---|
| PHP 8.5 | `{"f.txt":"file file txt","sub":"dir dir "}` |
| Truffle PHP | `getType()` for `f.txt` gives `dir` |

**Cause:** `DirectoryIterator` in `spl.php` (lines 856-976) sets the `SplFileInfo` path at construction, from entry
0. Some inherited methods (`getType`, `getPerms`, and the others that read the stored path) don't follow the current
entry.

**Fix:** to do. Every `SplFileInfo` method of a `DirectoryIterator` reads the current entry's path, as php-src's
`spl_filesystem_object_get_file_name` does.

**Test:** `tests/smoke/directoryiterator-entry-type.php`.
