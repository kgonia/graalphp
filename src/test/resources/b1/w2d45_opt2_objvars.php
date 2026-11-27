<?php
// Pass 2: get_object_vars builds its array unindexed; every later lookup, write, unset and copy must behave as on
// an ordinary hash. Object defaults are cloned for plain layouts; objects without counted slots skip the snapshot.
$o = new stdClass();
$o->{'1'} = 'one'; $o->{'01'} = 'zero-one'; $o->name = 'n'; $o->{'-5'} = 'neg';
$a = get_object_vars($o);
var_dump($a);
var_dump($a[1], $a['01'], isset($a['name']), isset($a['missing']), array_key_exists(-5, $a));
foreach ($a as $k => $v) { var_dump($k); echo "=> $v
"; }
$a[] = 'appended';
$b = $a;
$b['name'] = 'changed';
unset($a['01']);
unset($a[2]);
var_dump(array_keys($a), $b['name'], $a['name'], count($b));
$c = get_object_vars($o);
array_unshift($c, 'first');
var_dump($c);
$d = get_object_vars($o);
unset($d['-5']);
$d['-5'] = 'again';
var_dump($d);

class Base { private $secret = 's'; protected $prot = 'p'; public $pub = 'u'; public $list = [1, 2];
    public function vars() { return get_object_vars($this); } }
$c = new Base();
var_dump(get_object_vars($c), $c->vars());
$c->dyn = 'd';
var_dump(get_object_vars($c));
$k = get_object_vars($c);
var_dump($k['pub'], $k['dyn'] ?? null, count($k), $k);

class Holder { public $a = 1; public $b = 'x'; public $c = null; public $d = [1, [2]]; public $e = 1.5; }
$h1 = new Holder(); $h2 = new Holder();
$h1->d[1][] = 3; $h1->a++;
var_dump($h1, $h2);
class Owner { public $child; public $n = 0; public function __destruct() { echo "owner gone\n"; } }
class Kid { public function __destruct() { echo "kid gone\n"; } }
$w = new Owner(); $w->child = new Kid(); unset($w);
$plain = new Holder(); unset($plain);
$nan = NAN;
if ($nan < 1.0) echo "wrong lt\n"; else echo "nan not lt\n";
if ($nan == $nan) echo "wrong eq\n"; else echo "nan not eq\n";
if ($nan >= $nan) echo "wrong ge\n"; else echo "nan not ge\n";
if (1.0 <= 1) echo "mixed le\n";
$x = 0;
if (0 === $x) echo "zero identical\n";
if ($x === 0.0) echo "wrong\n"; else echo "int is not float\n";
if (1.5 > 1) echo "mixed gt\n";
echo "done\n";

// array_pop on unprobed get_object_vars results: the tail leaves without an index; the next free key follows PHP.
$p = new stdClass(); $p->x = 'X'; $p->{'3'} = 'three'; $p->y = 'Y';
$v = get_object_vars($p);
var_dump(array_pop($v), $v);
$v[] = 'next';
var_dump($v, $v['x'], isset($v['y']));
$w = get_object_vars($p);
var_dump(array_pop($w), array_pop($w), array_pop($w), array_pop($w), $w);
$w[] = 'after';
var_dump($w);
