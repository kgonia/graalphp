<?php
// `.=` on a variable appends in place (AppendVar): the strings it made earlier, and other variables holding them,
// must keep their values; reading the string between appends must see every byte.
set_error_handler(function ($no, $message) { echo "[warning: $message]\n"; return true; });
class S {
    function __toString(): string { return '<s>'; }
}
$s = '';
$seen = [];
for ($i = 0; $i < 300; $i++) {
    $s .= chr(65 + $i % 26);
    if ($i % 37 == 0) {
        $seen[] = $s;
    }
    if ($s[$i] !== chr(65 + $i % 26) || strlen($s) !== $i + 1) {
        echo "bad at $i\n";
    }
}
var_dump(strlen($s), md5($s), array_map('strlen', $seen), $seen[2]);

$a = 'x';
$a .= 'y';
$b = $a;
$a .= 'z';
$b .= 'w';
$a .= '1';
$b .= '2';
var_dump($a, $b);

$a = 'p';
$a .= 'q';
$old = $a;
$a .= 'r';
$a = $old;
$a .= 's';
var_dump($a, $old);

$r = 'r';
$ref = &$r;
$r .= 'a';
$ref .= 'b';
$r .= 'c';
var_dump($r, $ref);

$n = 5;
$n .= 6;
$n .= 1.5;
$n .= new S;
$n .= true;
$n .= null;
var_dump($n);

$u .= 'undefined';
var_dump($u);

function build($depth) {
    $out = "[$depth";
    if ($depth > 0) {
        $out .= build($depth - 1);
        $out .= build($depth - 1);
    }
    $out .= ']';
    return $out;
}
var_dump(build(3));

$parts = ['a' => 'A'];
$x = $parts['a'];
$x .= 'B';
$x .= 'C';
var_dump($parts, $x, ($y = 'm') . ($y .= 'n') . ($y .= 'o'), $y);

function gen() {
    $g = '';
    foreach (['x', 'y', 'z'] as $c) {
        $g .= $c;
        yield $g;
    }
}
var_dump(iterator_to_array(gen()));

$big = str_repeat('ab', 1000);
$big .= 'c';
$copy = $big;
$big .= 'd';
var_dump(strlen($copy), strlen($big), substr($copy, -2), substr($big, -2));
$multibyte = 'zaż';
$multibyte .= 'ółć';
$multibyte .= "\xff";
var_dump(bin2hex($multibyte), strlen($multibyte));
