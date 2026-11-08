# 034: mbstring functions refuse the 8bit encoding, so comments can't be posted

**State:** Open (found 2026-11-06 during the refcount census run on Gate 2's WordPress)

**Symptom:** posting a comment (`wp-comments-post.php`) is a 500 "critical error":
```
PHP Fatal error:  Uncaught Error: mbstring encoding 8BIT is not supported in wp-includes/comment.php:1338
#0 wp-includes/comment.php(1338): mb_strlen('Tester', '8bit')
#1 wp-includes/comment.php(4217): wp_check_comment_data_max_lengths(Array)
#2 wp-comments-post.php(25): wp_handle_comment_submission(Array)
```
`wp_check_comment_data_max_lengths` measures each field in bytes with `mb_strlen($value, '8bit')`. WordPress 6.9
also passes `'8bit'` in `PHPMailer` and in sodium_compat's `Core/Util.php` (7 calls under `wp-includes`).

**Cause:** `MbFunctions.encoding` knows only `UTF-8`/`UTF8` and `ASCII`/`US-ASCII`; any other name throws
`Error`. Unsupported names are also reported wrongly: PHP throws `ValueError` "mb_strlen(): Argument #2
($encoding) must be a valid encoding, "X" given", not `Error`.

**Fix:** to do. Add `8bit` (one byte is one character, so length, substr and strpos work on bytes) and its
aliases (`binary`). For names PHP knows but the runtime doesn't, keep failing, but list them. For names PHP
doesn't know either, throw PHP's `ValueError`.

**2026-11-08:** the WordPress PHPUnit suite (`tests/wp-phpunit`) has 116 tests that fail on it, for example
`WP_Test_REST_Comments_Controller` (66), `Tests_Comment_wpHandleCommentSubmission` (26) and `Tests_Compat_mbSubstr` (12).
