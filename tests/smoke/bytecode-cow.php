<?php
// Copy-on-write through the statements tla-cow.md models that functions on bytecode cover (values, copies,
// element writes, unset, calls by value and by reference, returns, foreach by value), with calls between functions
// on bytecode and functions the bytecode translator leaves on the tree (static marks those). Run with
// -Dpolyglot.php.Interpreter=bytecode and without: both must print what PHP prints.

function show($label, $value) {
    echo $label, ': ', json_encode($value), "\n";
}

function copies() {
    $a = [0, 0];
    $b = $a;
    $b[0] = 1;
    $c = $b;
    $c[1] = 2;
    $a[1] = 5;
    show('copies', [$a, $b, $c]);
}

function nestedCopy() {
    $inner = [0, 0];
    $outer = [$inner, $inner];
    $inner[0] = 9;
    $copy = $outer;
    $row = $copy[0];
    $row[1] = 7;
    $copy[0] = $row;
    show('nested', [$inner, $outer, $copy]);
}

function selfAssign() {
    $a = [1, 2];
    $a[0] = $a;
    show('self', $a);
}

function byValue($p) {
    $p[0] = 'changed';
    $p[] = 'appended';
    return $p;
}

function byRef(&$p) {
    $p[0] = 'through ref';
    $p[] = 'appended';
}

function readsRef(&$p) {
    return count($p) + $p[0];
}

function calls() {
    $a = [1, 2];
    $b = byValue($a);
    show('by value', [$a, $b]);
    $c = $a;
    byRef($a);
    show('by ref', [$a, $c]);
    $d = [3, 4];
    $e = $d;
    show('reads ref', [readsRef($d), $d, $e]);
    $d[0] = 0;
    show('after ref', [$d, $e]);
}

function element($x) {
    $a = [[1], [2]];
    $copy = $a;
    $copy[0] = 'x';
    $n = $a[1];
    $n[] = 3;
    show('element', [$a, $copy, $n, byValue($a[0])]);
    $list = [5, 6];
    byRef($list);
    $first = $list[0];
    unset($list);
    $list[] = 'recreated';
    show('unset', [$first, $list]);
    return $x;
}

function literal($s) {
    // each element goes into the array as it is evaluated: [$a, ...] holds $a before $a[] = 2 runs
    $a = [$s];
    $b = [$a, $a[] = 2];
    $c = ['k' => $a, 'l' => $a[] = 3];
    show('literal', [count($b[0]), $b, count($c['k']), $a]);
}

function returned() {
    $a = [1];
    $r = $a;
    return $r;
}

function returns() {
    $x = returned();
    $y = $x;
    $x[0] = 2;
    show('returns', [$x, $y]);
}

function loops() {
    $a = [1, 2, 3];
    $seen = [];
    foreach ($a as $k => $v) {
        $a[$k] = $v * 10;
        $a[] = $v;
        $seen[] = $v;
    }
    show('foreach', [$a, $seen]);
    $h = ['x' => 1, 'y' => 2];
    $keys = '';
    foreach ($h as $key => $value) {
        $keys .= $key . $value;
        $h = 'replaced';
    }
    show('foreach hash', [$h, $keys]);
}

function treeCounter() {
    static $calls = 0;
    $calls++;
    $a = [1, 2];
    $b = byValue($a);
    return [$calls, $a, $b];
}

function fromTree() {
    $a = treeCounter();
    $b = treeCounter();
    $b[1][0] = 'nested write on the tree';
    show('tree', [$a, $b]);
}

copies();
nestedCopy();
selfAssign();
calls();
element(1);
returns();
literal(1);
loops();
fromTree();
for ($i = 0; $i < 20; $i++) {
    // enough calls for the cached interpreter and compilation
    copies();
    calls();
    literal(1);
    loops();
}
echo "done\n";
