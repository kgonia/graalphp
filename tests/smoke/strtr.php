<?php
// strtr with an array: one-byte keys, longest-match multi-byte keys, no rescanning, numeric keys, no matches.
$cases = [
    ['a<b>"c"&\'d\'', ['&' => '&amp;', '<' => '&lt;', '>' => '&gt;', "'" => '&apos;', '"' => '&quot;']],
    ['Hi all, I said hello', ['Hi' => 'Hello', 'Hello' => 'Hi', 'hello' => 'hi']],
    ['abcabc', ['a' => 'ab', 'ab' => 'x', 'abc' => 'y']],
    ['1 2 3 12', [1 => 'one', 12 => 'twelve']],
    ['nothing to do', ['xyz' => 'q', 'Q' => 'z']],
    ['', ['a' => 'b']],
    ['aaa', ['aa' => 'a']],
];
foreach ($cases as [$subject, $pairs]) {
    echo json_encode(strtr($subject, $pairs)), "\n";
}
echo strtr('abc', 'ab', 'xyz'), "\n";
