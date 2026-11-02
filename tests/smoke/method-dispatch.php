<?php
// Method, static and constructor calls whose site sees many classes: a site calls per method found, so the
// classes that resolve to one method share its call. What depends on the class stays the call's own: $this, the
// called class (static::, get_called_class), __call and __callStatic for a class without the method, traits'
// self and __CLASS__. Each loop runs long enough for its sites to be compiled.

abstract class Base {
    public $id;
    public function __construct($id) { $this->id = $id; }
    public function getProp($name) { return static::class . ':' . $name . ':' . $this->id . ':' . $this->prefix(); }
    protected function prefix() { return 'base'; }
    public static function make($id) { return new static($id); }
    public static function kind() { return static::class . '/' . get_called_class() . '/' . self::class; }
    public function price() { return $this->getProp('price'); }
}
class P1 extends Base {}
class P2 extends Base { protected function prefix() { return 'p2'; } }
class P3 extends Base {}
class P4 extends P3 { public function __construct($id) { parent::__construct($id * 10); } }
class P5 extends Base {}
class P6 extends P5 { protected function prefix() { return 'p6:' . parent::prefix(); } }
class P7 extends Base {}
class P8 extends Base {}
class P9 extends Base {}
class P10 extends P9 { public static function kind() { return 'own:' . parent::kind(); } }
class P11 extends Base {}
class Magic extends Base {
    public function __call($name, $args) { return "__call $name(" . implode(',', $args) . ") on " . static::class; }
    public static function __callStatic($name, $args) { return "__callStatic $name on " . static::class; }
}
class Magic2 extends Magic {}

$classes = ['P1', 'P2', 'P3', 'P4', 'P5', 'P6', 'P7', 'P8', 'P9', 'P10', 'P11'];
$objects = [];
foreach ($classes as $i => $c) {
    $objects[] = new $c($i);
}
$seen = [];
for ($round = 0; $round < 300; $round++) {
    foreach ($objects as $o) {
        // one method for every class (Base::price, Base::getProp), $this->prefix() overridden in two
        $seen[$o->price()] = true;
        $seen[$o::kind()] = true;
        $seen[get_class($o::make($round % 3))] = true;
    }
}
ksort($seen);
echo implode("\n", array_keys($seen)), "\n";

// a site whose classes resolve to the method, to __call, and to a class with neither
$mixed = [new P1(1), new Magic(2), new Magic2(3), new P2(4)];
$out = [];
for ($i = 0; $i < 2000; $i++) {
    foreach ($mixed as $o) {
        $out[$o->price()] = true;
        if ($o instanceof Magic) {
            $out[$o->missing($i % 2, 'x')] = true;
            $out[$o::missingStatic()] = true;
        }
    }
}
ksort($out);
echo implode("\n", array_keys($out)), "\n";
function arg($v) { echo "argument evaluated\n"; return $v; }
try {
    (new P1(0))->nope(arg(1));
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
try {
    P1::nope(arg(1));
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
class OnlyCall { public function __call($n, $a) { return "__call $n"; } public function run() { return [$this->x(), self::y(), static::z()]; } }
var_dump((new OnlyCall)->run());
try {
    OnlyCall::w(arg(2));
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}

// traits: each using class gets its own methods (self, __CLASS__), one site calls them all
trait Named {
    public function name() { return __CLASS__ . '|' . self::class . '|' . static::class . '|' . get_class($this); }
    public static function create() { return new self(); }
}
class T1 { use Named; }
class T2 { use Named; }
class T3 extends T1 {}
class T4 extends T2 { use Named; }
$names = [];
for ($i = 0; $i < 2000; $i++) {
    foreach ([new T1, new T2, new T3, new T4] as $o) {
        $names[$o->name()] = true;
        $names[get_class($o::create())] = true;
    }
}
ksort($names);
echo implode("\n", array_keys($names)), "\n";

// static calls: forwarding (self::, parent::, static::) keeps the called class; a non-static method called
// statically from an instance of the class gets $this
class S0 {
    public static function who() { return static::class; }
    public function inst() { return isset($this) ? 'this ' . get_class($this) : 'no this'; }
    public static function fwd() { return self::who() . ',' . static::who() . ',' . S0::who(); }
}
class S1 extends S0 { public function viaParent() { return parent::inst() . '/' . S0::inst() . '/' . parent::fwd(); } }
class S2 extends S1 {}
class S3 extends S0 { public static function who() { return 'S3 own'; } }
$st = [];
for ($i = 0; $i < 2000; $i++) {
    foreach (['S0', 'S1', 'S2', 'S3'] as $c) {
        $st[$c::fwd()] = true;
        $st[$c::who()] = true;
    }
    $st[(new S2)->viaParent()] = true;
    $st[(new S1)->viaParent()] = true;
}
ksort($st);
echo implode("\n", array_keys($st)), "\n";

// constructors: inherited, own, none, and by reference
class C0 { public $v; public function __construct(&$r = null, $x = 0) { $r = static::class; $this->v = $x; } }
class C1 extends C0 {}
class C2 extends C0 { public function __construct(&$r = null, $x = 0) { $r = 'own ' . static::class; } }
class C3 {}
class C4 extends C1 {}
$made = [];
for ($i = 0; $i < 2000; $i++) {
    foreach (['C0', 'C1', 'C2', 'C3', 'C4'] as $c) {
        $r = null;
        $o = new $c($r, $i);
        $made[get_class($o) . '=' . var_export($r, true)] = true;
    }
}
ksort($made);
echo implode("\n", array_keys($made)), "\n";
