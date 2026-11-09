<?php
// The string built-ins' fast paths (implode's exact-size join, str_replace's in-place miss and counted
// replacement, strpos with an offset in place): each case as PHP gives it, edge cases included.
set_error_handler(function ($no, $message) { echo "[warning: $message]\n"; return true; });
class S {
    function __construct(public $v) {}
    function __toString(): string { echo "[toString {$this->v}]"; return "s{$this->v}"; }
}
$r = 7;
$list = [1, 2.5, null, true, false, 'x', new S('a'), -3, PHP_INT_MIN];
$list[] = &$r;
var_dump(implode(',', $list));
var_dump(implode('', []), implode('-', ['only']), implode(['a', 'b']), implode(', ', [[1], 'z']));
var_dump(implode(str_repeat('ab', 3), range(1, 5)));

$subject = 'The quick brown fox';
var_dump(str_replace(['cat', 'dog'], ['C', 'D'], $subject));
var_dump(str_replace(['cat', 'dog'], [new S('r')], $subject));
var_dump(str_replace(['cat', 'dog'], [[1]], $subject));
var_dump(str_replace(['', 'zz'], 'Y', $subject));
var_dump(str_replace(['quick', 'fox'], ['slow'], $subject));
var_dump(str_replace(['o', 'u'], ['0', ''], $subject, $count), $count);
var_dump(str_replace([1, 'q'], 'Q', 'q1q1'));
var_dump(str_replace('aa', 'b', 'aaaaa', $count), $count);
var_dump(str_replace('abc', '', 'abc'), str_replace('a', 'bcd', 'a-a-a'));
var_dump(str_replace(['a', 'b'], ['b', 'c'], 'ab'));
var_dump(str_ireplace(['QUICK', 'x'], 'Z', $subject, $count), $count);
var_dump(str_replace(['x'], 'y', ['k' => 'xox', 'n' => 5, 'a' => [1]]));
var_dump(str_replace('long search string', 'x', 'short'));

$h = 'abcabcabc';
foreach ([0, 1, 3, 8, 9, -1, -3, -9] as $offset) {
    var_dump(strpos($h, 'abc', $offset), strpos($h, '', $offset), strpos($h, 'cab', $offset));
}
foreach ([10, -10] as $offset) {
    try {
        strpos($h, 'a', $offset);
    } catch (ValueError $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}
var_dump(strpos($h, 'c', '2'), strpos($h, 'b', 1.0));
try {
    strpos($h, 'a', 'x');
} catch (TypeError $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
