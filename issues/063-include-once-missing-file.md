# 063: `include_once` of a missing file throws an Error instead of warning and returning false

**State:** Fixed. Found 2026-11-08 by the audit of implicit state machines ([implicit-state-machines.md](../wiki/implicit-state-machines.md)); confirmed on PHP 8.5 and on main 438231d.

**Symptom:** `@include_once '/nonexistent/x.php'`, twice (`include-once-missing`):

| | Output |
|---|---|
| PHP 8.5 | `bool(false)`, twice |
| Truffle PHP | `Fatal error: Uncaught Error: Failed opening ... for inclusion` |

**Cause:** `IncludeNode` (`IncludeNode.java:37-44`) has two problems:
- It marks the path as included before loading it, so a second `include_once` of a file that failed would be
  skipped.
- `loadFile` reports a missing file as an `Error`.

php-src's `include`/`include_once` warns "Failed to open stream" and "Failed opening ... for inclusion" and returns
false. Only `require` is fatal. The file counts as included only once it has opened.

**Fix:** `IncludeNode.resolve` opens the file first (`openFailure`), as `zend_include_or_eval` does. On failure:
- every kind warns "KIND(PATH): Failed to open stream: No such file or directory" (or "Permission denied", or for a
  directory "Inappropriate ioctl for device");
- include and include_once then warn "Failed opening 'PATH' for inclusion (include_path='...')" and give false;
- require and require_once throw the Error "Failed opening required 'PATH' (include_path='...')".

A path counts as included only once it has opened. An empty path is a ValueError. `include_path` is an ini
directive now, with the default of a PHP built with the default prefix (`.:/usr/local/lib/php`). Only its message
uses it; lookup is still in "." and the including file's directory (074).

**Test:** `tests/smoke/include-once-missing.php`.
