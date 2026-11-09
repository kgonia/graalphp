# 074: `include_path` is used only in error messages

**State:** Open. Found 2026-11-09 while fixing the implicit state machines (055-071); confirmed on PHP 8.5 and on issues-state-machines 5fa1ee2.

**Symptom:** `include 'x.php'` looks in the working directory and the including file's directory only.
`set_include_path`, `get_include_path` and `stream_resolve_include_path` don't exist (049 lists them for the WordPress
PHPUnit suite). `ini_set('include_path', ...)` changes nothing but the text of 063's "Failed opening" messages.

**Cause:** 063 added the `include_path` ini directive (default `.:/usr/local/lib/php`, a default-prefix build's) so
that the warnings and the require Error quote it as PHP does. `IncludeNode.located` still implements
`php_resolve_path` with a fixed path of ".".

**Fix:** to do. Resolve relative names as `php_resolve_path` does, along the directive's entries, then the including
file's directory. Add the three functions over the directive.

**Test:** none yet.
