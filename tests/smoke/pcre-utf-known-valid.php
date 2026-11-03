<?php
// Issue 011: once a /u match from offset 0 has found a subject valid UTF-8, later calls on it skip PCRE2's check
// (php-src's is_known_valid_utf8), unless the offset is inside a character; results and errors stay the same.

// php-src's bug72685, on a subject four times as large: linear, not quadratic, in the subject's length
$str = str_repeat('a', 256 * 1024);
$t = microtime(true);
$pos = 0;
while (preg_match('/\G\w/u', $str, $m, 0, $pos)) {
    ++$pos;
}
$elapsed = microtime(true) - $t;
var_dump($pos, $elapsed < 2.0 ? 'fast' : "slow: $elapsed s");

// a valid subject: offsets inside a character are still errors, after it is known valid too
$valid = str_repeat("x\u{e9}", 3000);   // 9000 bytes: a cached subject
var_dump(preg_match('/\x{e9}/u', $valid));
foreach ([0, 1, 2, 3, 4, 5, 8999, 9000, 9001] as $offset) {
    var_dump($offset, preg_match('/./u', $valid, $m, 0, $offset), preg_last_error_msg(), $m);
}
var_dump(preg_match_all('/\x{e9}/u', $valid, $m, 0, 2), preg_last_error_msg());
var_dump(preg_match_all('/\x{e9}/u', $valid, $m, 0, 3), preg_last_error_msg());
var_dump(strlen(preg_replace('/x/u', '', $valid)), preg_last_error_msg());
var_dump(count(preg_split('/x/u', $valid)), preg_last_error_msg());

// an invalid subject never becomes known valid: every call reports it, also from offsets past the bad byte
$invalid = str_repeat('a', 5000) . "\xff" . str_repeat('b', 5000);
foreach ([0, 0, 4999, 5000, 5001, 0] as $offset) {
    var_dump($offset, preg_match('/b/u', $invalid, $m, 0, $offset), preg_last_error_msg());
}
var_dump(preg_replace('/a/u', '', $invalid), preg_last_error_msg());
var_dump(preg_split('/a/u', $invalid), preg_last_error_msg());
var_dump(preg_grep('/a/u', [$invalid, $valid]), preg_last_error_msg());

// a first call from a later offset checks only from there: the subject isn't known valid by it
$late = "\xff" . str_repeat('c', 6000);
var_dump(preg_match('/c/u', $late, $m, 0, 1), preg_last_error_msg());
var_dump(preg_match('/c/u', $late, $m, 0, 2), preg_last_error_msg());
var_dump(preg_match('/c/u', $late), preg_last_error_msg());

// small subjects
$small = "\u{e9}t\u{e9}";
foreach ([0, 1, 2, 3, 0, 5] as $offset) {
    var_dump($offset, preg_match('/t/u', $small, $m, 0, $offset), preg_last_error_msg());
}
