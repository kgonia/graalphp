# 078: `ob_start`'s chunk size is ignored

**State:** Open. Found 2026-11-09 while fixing the implicit state machines (055-071); confirmed on PHP 8.5 and on issues-state-machines 5fa1ee2.

**Symptom:** `ob_start(fn($b) => "[$b]", 4); echo "abcdef"; echo "gh"; ob_end_flush();`:

| | Output |
|---|---|
| PHP 8.5 | `[abcdefgh][]` |
| Truffle PHP | `[abcdefgh]` |

**Cause:** `ob_start` drops its second argument. php-src's `php_output_handler_append` runs the handler once a
buffer holds `chunk_size` bytes (`handler->size`), and not while a handler runs. 055's output stack has no such size.

**Fix:** to do. Keep the chunk size on the `OutputBuffer` and run the handler as `php_output_handler_append` and
`php_output_handler_op` do, then compare call by call with PHP. The probe's output has a second handler call (`[]`)
that the first reading of `main/output.c` doesn't explain.

**Test:** none yet.
