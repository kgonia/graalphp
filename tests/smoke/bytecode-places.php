<?php
// Places the bytecode translator used to leave to the tree: static properties by a computed name, elements of
// properties by a computed name, of a call's result and of $this, `[]` inside an argument, as php-src runs them

function f() { return [1, [2]]; }
function &r() { static $a = [1, [2]]; return $a; }
class A {
    public $p = 1;
    public static $s = [1];
    public $arr = [];
    function m() { return [1]; }
}
function byref(&$x) { $x = 5; }

$sn = 's';
A::$$sn[] = 2;
A::$$sn[0] += 10;
A::$$sn[1]++;
var_dump(A::$$sn, isset(A::${'s'}[0]), isset(A::$$sn[9]), A::$$sn[5] ?? 'none');
$x = &A::$$sn;
$x[] = 9;
foreach (A::$$sn as &$v) {
    $v++;
}
unset($v);
var_dump(A::$s);
$y = 'bound';
A::$$sn = &$y;
$y = 'rebound';
var_dump(A::$s);
try {
    unset(A::$$sn);
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
try {
    var_dump(A::${'missing'});
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}

function computed() {
    $o = new A;
    $n = 'p';
    $o->$n = [];
    $o->$n[0][1] = 1;
    $o->$n[0][1] += 1;
    $o->$n[0][1]++;
    $o->$n[1] ??= 'set';
    $o->$n[1] ??= 'not set';
    $o->$n[][] = 'new';
    unset($o->$n[0][1]);
    $r = &$o->$n[2][0];
    $r = 'ref';
    unset($r);
    $b = 'bound';
    $o->$n[3] = &$b;
    byref($o->$n[4]);
    byref($o->$n[]);
    var_dump(isset($o->$n[1]), $o->$n);
}
computed();

function temporaries() {
    f()[0] += 1;
    f()[1][0]++;
    unset(f()[0]);
    r()[0] += 5;
    r()[1][0]++;
    $x = &r()[1][1];
    $x = 'via reference';
    unset(r()[1][0]);
    var_dump(r());
    $copy = &f()[0];
    var_dump($copy);
    $a = [];
    byref($a[][0]);
    byref($a['k'][]);
    var_dump($a);
}
temporaries();

class Store implements ArrayAccess {
    private $data = ['k' => 1];
    function offsetExists($o): bool { echo "exists $o\n"; return isset($this->data[$o]); }
    function offsetGet($o): mixed { echo "get ", var_export($o, true), "\n"; return $this->data[$o] ?? null; }
    function offsetSet($o, $v): void { echo "set $o\n"; $this->data[$o] = $v; }
    function offsetUnset($o): void { echo "unset $o\n"; unset($this->data[$o]); }
    function run() {
        $this['k'] .= 'x';
        $this['k']++;
        unset($this['k']);
        $this['j'] = 2;
    }
}
(new Store)->run();
$store = new Store;
$store['k']--;
$nested = ['s' => $store];
++$nested['s']['k'];

$list = [];
try {
    $list[]->p = 1;
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
try {
    $list[]->p .= 1;
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}

$i = 0;
again:
$i++;
if ($i < 3) {
    foreach ([1] as $unused) {
        goto again;
    }
}
var_dump($i);
