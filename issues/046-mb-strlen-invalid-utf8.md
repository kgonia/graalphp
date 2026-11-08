# 046: `mb_strlen` counts invalid UTF-8 differently from PHP 8.3+

**State:** Open. Found 2026-11-08 by the WordPress PHPUnit suite. 6 tests fail on it
(`Tests_Compat_wpUtf8CodePointCount`, "Miscounted code points").

**Symptom:** PHP 8.3 removed mbstring's fast path (php-src cca4ca6d), so invalid bytes count as PHP's UTF-8
decoder splits them:

| Input | PHP 8.5 | Truffle PHP |
|---|---:|---:|
| `"\xF0\x9F\x98"` | 1 | 1 |
| `"\xE2\x82"` | 1 | 1 |
| `"a\x80b"` | 3 | 2 |
| `"\xF0\x9F\x98\xF0\x9F"` | 2 | 2 |
| `"\xC0\xAF"` | 2 | 1 |
| `"\xED\xA0\x80"` (a surrogate) | 3 | 1 |

**Fix:** to do. Count as php-src's `mb_utf8_to_wchar` decodes: each maximal invalid subsequence is one error
character. A lone continuation byte, an overlong lead byte (`C0`, `C1`) and each byte of an encoded surrogate count
separately. The same rule applies to `mb_substr`, `mb_str_split` and `mb_convert_encoding`'s substitution.
