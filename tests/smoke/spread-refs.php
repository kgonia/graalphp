<?php
// By-reference parameters reached through unpacked arguments (ZEND_SEND_UNPACK) and by-reference variadics.
class W {
    function start_el(&$output, $x, $depth = 0, $args = []) { $output .= "[$x]"; }
    function display(&$output, $args) {
        $this->start_el($output, 'a', 0, ...array_values($args));
        $this->start_el($output, 'b', 0);
    }
}
function f(&$o, ...$rest) { $o .= 'f'; }
function g(&$a, $b, &$c) { $a .= '1'; $c .= '3'; $b .= '2'; }
function v(&...$all) { foreach ($all as &$x) { $x = "v$x"; } }
$out = '';
(new W)->display($out, [['k' => 1]]);
echo $out, "\n";
$s = '';
f($s, ...[1, 2]);
echo $s, "\n";
$t = '';
$w = new W;
$w->start_el($t, 'c', ...[0, []]);
echo $t, "\n";
$arr = ['a', 'b', 'c'];
$copy = $arr;
g(...$arr);
var_dump($arr, $copy);
$x = 'x';
$more = ['y', 'z'];
v($x, ...$more);
var_dump($x, $more);
v(...['p', 'q']);
$tmp = [1];
g($tmp[0], ...[2, 3]);
var_dump($tmp);
function w(&...$all) { $all[0] = 'w'; }
$x = 'x';
w($x);
var_dump($x);
function cnt(&$r) { $r++; }
$n = [1, 2];
$alias = &$n;
cnt(...$n);
var_dump($n, $alias);
