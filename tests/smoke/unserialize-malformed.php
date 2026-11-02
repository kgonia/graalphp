<?php
// unserialize() of malformed data as var_unserializer.re reads it: false, with "Error at offset X of Y bytes" where
// php-src's scanner stopped (or the other warnings it raises first), "Extra data" after a complete value, and
// the edge cases of each token: signs, leading zeros, out-of-range numbers, float forms, lengths past the end,
// duplicate keys, back references to nothing, and keys that aren't ints or strings.
set_error_handler(function ($type, $message, $file, $line) {
    echo "  error $type: $message\n";
    return true;
});
function show($value)
{
    return is_array($value) || is_object($value) ? str_replace("\0", '~', serialize($value)) : var_export($value, true);
}

$cases = [
    '', 'x', ' ', 'i:', 'i:1', 'i:1;x', 'i:+5;', 'i:-0;', 'i:007;', 'i:-;', 'i:1.5;',
    'i:99999999999999999999;', 'i:-9223372036854775808;', 'i:9223372036854775807;', 'i:9223372036854775808;',
    'i:-9223372036854775809;', 'i:000000000000000000000000001;',
    'b:2;', 'b:1;', 'b:0', 'N;', 'N', 'n;',
    'd:1.5;', 'd:.5;', 'd:5.;', 'd:1e5;', 'd:1.5e-3;', 'd:-INF;', 'd:INF;', 'd:NAN;', 'd:INF', 'd:-NAN;', 'd:1e;',
    'd:.;', 'd:+1.5E+2;', 'd:-0;', 'd:1e999;', 'd:0x1A;',
    's:3:"abc";', 's:3:"ab";', 's:3:"abcd";', 's:3:"abc"', 's:10:"abc";', 's:0:"";', 's:-1:"";', 's:3:"abc";i:1;',
    's:3:abc;', 's:99999999999999999999:"a";', 's:3:"a' . "\0" . 'c";',
    'S:3:"\61bc";', 'S:3:"\6xbc";', 'S:2:"ab', 'S:1:"\41"',
    'a:0:{}', 'a:0:{', 'a:1:{}', 'a:1:{i:0;i:1;}', 'a:2:{i:0;i:1;}', 'a:1:{i:0;i:1;i:1;i:2;}', 'a:1:{i:0;i:1;',
    'a:1:{N;i:1;}', 'a:1:{b:1;i:1;}', 'a:1:{d:1.5;i:1;}', 'a:1:{s:1:"5";i:1;}', 'a:1:{s:2:"05";i:1;}',
    'a:2:{i:0;i:1;i:0;i:2;}', 'a:1:{a:0:{}i:1;}', 'a:1:{R:1;i:1;}', 'a:1:{r:1;i:1;}', 'a:1:{O:8:"stdClass":0:{}}',
    'a:99999999999999999999:{', 'a:-1:{}', 'a:3:{i:0;i:1;i:1;i:2;}', 'a:2:{i:0;i:1;i:1;i:2;}}',
    'a:2:{i:0;a:0:{}i:1;i:2;}', 'a:2:{i:0;s:1:"x"i:1;i:2;}',
    'a:3:{i:0;i:1;i:1;R:2;i:2;R:3;}', 'a:2:{i:0;O:8:"stdClass":0:{}i:1;r:2;}', 'a:1:{i:0;R:0;}', 'a:1:{i:0;R:5;}',
    'a:2:{i:0;i:1;i:1;r:2;}', 'a:1:{i:0;r:1;}', 'a:1:{i:0;r:2;}', 'a:2:{i:0;i:5;i:0;R:2;}',
    'a:1:{i:0;R:99999999999999999999;}',
    'O:8:"stdClass":0:{}', 'O:8:"stdClass":1:{s:1:"a";i:1;}', 'O:0:"":0:{}', 'O:1:"\\":0:{}', 'O:5:"a-b-c":0:{}',
    'O:8:"stdClass":0:', 'O:8:"stdClass"', 'O:8:"stdClass":', 'O:8:"stdClass":-1:{}', 'O:8:"stdClass":1:{i:5;i:1;}',
    'O:8:"stdClass":1:{N;i:1;}', 'O:9:"stdClass":0:{}', 'O:8:"stdClass:0:{}', 'O:8:"stdClass":0:[}',
    'O:8:"stdClass":2:{s:1:"a";i:1;}', 'O:8:"stdClass":1:{s:1:"a";i:1;', 'O:8:"stdClass":00001:{s:1:"a";i:1;}',
    'O:8:"stdClass":99999999999999999999:{', 'O:3:"' . "\0ab" . '":0:{}', 'O:7:"Missing":1:{s:1:"a";i:1;}',
    'O:8:"stdClass":0:{}extra', 'O:8:"stdClass":2:{s:1:"a";i:1;s:1:"a";i:2;}',
    'C:8:"stdClass":0:{}', 'C:8:"stdClass":5:{abc}', 'C:8:"stdClass":2:{abc}', 'C:7:"Missing":3:{abc}',
    'C:8:"stdClass":-1:{}', 'C:8:"stdClass":3:[abc}', 'C:8:"stdClass":3', 'C:8:"stdClass":100:{abc}',
    'E:3:"A:B";', 'E:3:"AB_";', 'E:10:"stdClass:X";', 'E:9:"Nope1:Foo";', 'E:4:"a-b:";', 'E:0:"";', 'E:3:"A:B"',
    '}', 'R:1;', 'r:1;', 'x:1;', 'R:', 'a:1:{i:0;}',
];
foreach ($cases as $data) {
    echo show($data), "\n";
    echo '  => ', show(unserialize($data)), "\n";
}

echo "-- depth\n";
$deep = 'a:1:{i:0;a:1:{i:0;a:1:{i:0;O:8:"stdClass":0:{}}}}';
foreach ([0, 1, 2, 3, 4] as $depth) {
    echo $depth, ': ', show(unserialize($deep, ['max_depth' => $depth])), "\n";
}
ini_set('unserialize_max_depth', '2');
echo show(unserialize($deep)), "\n";
echo show(unserialize($deep, ['max_depth' => 10])), "\n";
ini_restore('unserialize_max_depth');
echo show(unserialize('O:8:"stdClass":0:{}', ['max_depth' => 1])), "\n";
echo show(unserialize('a:1:{i:0;O:8:"stdClass":0:{}}', ['max_depth' => 1])), "\n";
echo show(unserialize('a:1:{i:0;a:0:{}}', ['max_depth' => 1])), "\n";

echo "-- references\n";
$a = unserialize('a:2:{i:0;i:1;i:1;R:2;}');
$a[0] = 5;
var_dump($a[1]);
$o = unserialize('O:8:"stdClass":2:{s:1:"a";i:1;s:1:"b";R:2;}');
$o->a = 9;
var_dump($o->b);
$nested = unserialize('a:2:{i:0;a:1:{s:1:"x";i:1;}i:1;R:3;}');
$nested[1] = 'changed';
echo show($nested), "\n";
$objects = unserialize('a:3:{i:0;O:8:"stdClass":0:{}i:1;r:2;i:2;R:2;}');
var_dump($objects[0] === $objects[1], $objects[0] === $objects[2]);
$objects[2] = 'replaced';
var_dump(is_object($objects[0]), is_object($objects[1]));
echo show(unserialize('a:2:{i:0;i:1;i:0;R:2;}')), "\n";
echo show(unserialize('a:3:{i:0;i:1;i:1;R:2;i:1;i:7;}')), "\n";
