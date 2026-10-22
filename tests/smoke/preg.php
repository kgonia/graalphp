<?php
// preg_replace templates and escapes, preg_split and preg_match_all edge cases
$s = "a1b22c333 x-y";
echo preg_replace('/(\d+)/', '<$1>', $s), "\n";
echo preg_replace('/(\d+)/', '<${1}0>', $s), "\n";
echo preg_replace('/(\d+)/', '<\1|\\1|\$1|$11>', $s), "\n";
echo preg_replace('/([a-z])(\d)/', '$2$1', $s, 2, $count), " $count\n";
echo preg_replace(['/a/', '/b/'], ['b', 'c'], 'ab'), "\n";
echo preg_replace('/x*/', '-', 'abc'), "\n";
print_r(preg_split('/(-)/', 'a-b-c', -1, PREG_SPLIT_DELIM_CAPTURE));
print_r(preg_split('/\s*/', 'a b  c', -1, PREG_SPLIT_NO_EMPTY | PREG_SPLIT_OFFSET_CAPTURE));
var_dump(preg_match_all('/(a)(b)?/', 'ab a ab', $m, PREG_SET_ORDER), $m);
var_dump(preg_match('/(?<x>z)?q/', 'q', $m, PREG_UNMATCHED_AS_NULL), $m);
echo preg_replace_callback('/\b(\w)(\w*)\b/', fn($m) => strtoupper($m[1]) . $m[2], 'hello big world'), "\n";
var_dump(preg_match('/./u', "\xff"), preg_last_error(), preg_last_error_msg());
echo preg_quote('a.b*c?d[e]#f/g', '/'), "\n";
