# 011: preg_match with /u checks the subject's UTF-8 again on every call

**State:** Fixed (2026-11-03, branch `fix-lifetime-generators`)

**Symptom:** a loop of matches at growing offsets on one subject is quadratic. php-src's `ext/pcre/tests/bug72685` (65536 calls of `preg_match('/\G\w/u', $str, $m, 0, $pos)` on 64 KB, which must take under 0.5 s) fails or passes by the machine's load. Measured 2026-10-30 (`target/scratch/pcre-g.php`, one run each):

| subject | PHP 8.5 | Truffle PHP |
|---:|---:|---:|
| 16 KB | 0.002 s | 0.113 s |
| 32 KB | 0.002 s | 0.162 s |
| 64 KB | 0.004 s | 0.506 s |

WordPress's block parser and HTML tag processor match at growing offsets on a whole post the same way.

**Cause (confirmed):** `PcreFunctions`' first match of each call on a /u pattern lets PCRE2 validate UTF-8 (`firstOptions()` gives 0, not `NO_UTF_CHECK`), and PCRE2 checks the subject from the offset to its end. The native copy of the subject is already cached by the byte array's identity (`SubjectCache`); its validity isn't. php-src keeps a valid-UTF-8 flag on the `zend_string` and passes `PCRE2_NO_UTF_CHECK` once a subject is known valid.

**Fix:** the `SubjectCache` entry of a subject (by its byte array's identity; subjects of 4 KB and more) keeps
whether a /u match from offset 0 checked it and found no UTF error: PCRE2 checks from the offset to the end, so
only offset 0 proves the whole subject valid, as php-src sets `IS_STR_VALID_UTF8` only after a call from offset 0.
A call's first match then passes `NO_UTF_CHECK` when the subject is known valid and the offset isn't inside a
character (`Matcher.firstOptions`, php-src's `is_known_valid_utf8`); an offset inside a character is checked, and
fails, as before. An invalid subject is never marked, so its errors are unchanged. Small subjects are copied per
call anyway, so their check doesn't change the cost's order.

Measured after the fix (same loop, one run each, the first includes warm-up): 16 KB 0.21 s, 32 KB 0.033 s,
64 KB 0.038 s, 256 KB 0.090 s (before: 256 KB 38 s); PHP 8.5 0.002-0.018 s.

**Test:** `tests/smoke/pcre-utf-known-valid.php`: the bug72685 loop on 256 KB (under 2 s; 38 s before), offsets
inside a character after the subject is known valid, preg_match_all/preg_replace/preg_split/preg_grep on valid and
invalid subjects, a first call from a later offset, small subjects.
