# 076: `var_export` of a circular structure doesn't warn

**State:** Open. Found 2026-11-09 while fixing the implicit state machines (055-071); confirmed on PHP 8.5 and on issues-state-machines 5fa1ee2.

**Symptom:** `$a = [1]; $a[] = &$a; var_export($a);`: PHP warns "var_export does not handle circular references"
where it writes `NULL`. Here it writes the `NULL` without the warning.

**Cause:** `Dump.varExport` writes `NULL` for a container that already carries the mark, and a comment notes
php-src's warning without raising it.

**Fix:** to do. Raise the E_WARNING there, as `php_var_export_ex` does, for arrays and objects.

**Test:** none yet.
