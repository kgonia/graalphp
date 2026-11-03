<?php
// `a . b . c ...` in a function: the first two operands are evaluated, then converted, each next one is evaluated and
// converted in turn (php-src's ZEND_CONCAT chain), with literal pairs folded.
class S {
    function __construct(public string $s) {}
    function __toString(): string { echo "[str {$this->s}]"; return $this->s; }
}
function v($x) { echo "[eval $x]"; return new S($x); }
function chain() { return v('a') . v('b') . v('c') . v('d'); }
function mixed_parts($n) { return 'x' . $n . 'y' . 1.5 . true . null . 'z' . -3; }
function folded($a) { return $a . ' AND ' . 'b' . 'c' . $a; }
function ints() { return v('a') . 2 . v('b') . 3.5 . v('c'); }
function nested($a) { return ($a . 'x' . $a) . ('y' . $a . 'z'); }
echo chain(), "\n", mixed_parts(7), "\n", folded('q'), "\n";
echo ints(), "\n", nested('n'), "\n";
