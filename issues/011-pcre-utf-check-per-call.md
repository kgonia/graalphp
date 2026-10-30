# 011: preg_match with /u checks the subject's UTF-8 again on every call

**State:** Open

**Symptom:** a loop of matches at growing offsets on one subject is quadratic. php-src's `ext/pcre/tests/bug72685` (65536 calls of `preg_match('/\G\w/u', $str, $m, 0, $pos)` on 64 KB, which must take under 0.5 s) fails or passes by the machine's load. Measured 2026-10-30 (`target/scratch/pcre-g.php`, one run each):

| subject | PHP 8.5 | Truffle PHP |
|---:|---:|---:|
| 16 KB | 0.002 s | 0.113 s |
| 32 KB | 0.002 s | 0.162 s |
| 64 KB | 0.004 s | 0.506 s |

WordPress's block parser and HTML tag processor match at growing offsets on a whole post the same way.

**Cause (likely, not confirmed):** `PcreFunctions`' first match of each call on a /u pattern lets PCRE2 validate UTF-8 (`firstOptions()` gives 0, not `NO_UTF_CHECK`), and PCRE2 checks the subject from the offset to its end. The native copy of the subject is already cached by the byte array's identity (`SubjectCache`); its validity isn't. php-src keeps a valid-UTF-8 flag on the `zend_string` and passes `PCRE2_NO_UTF_CHECK` once a subject is known valid.

**Fix:** to do: confirm with a profile, then remember a subject's validity with its `SubjectCache` entry (or on the string), as php-src does, with error results unchanged for invalid subjects.
