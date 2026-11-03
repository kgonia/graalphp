<?php
// Closures' scope and calls, at top level (the tree) and in a function (bytecode): static properties' visibility
// from the calling scope, by-reference arguments through $f() and ->__invoke(), callable names, == of fake
// closures, Closure::getCurrent(), properties of a Closure, __METHOD__, include beside the including file, and
// self, parent and static in a closure bound to another scope

class A {
    private static $priv = 'private';
    protected static $prot = 'protected';
    public static $pub = 'public';

    public static function reader() {
        return function () {
            return [A::$priv, isset(A::$priv), self::$prot];
        };
    }

    public function method() {
        return [__METHOD__, (function () { return __METHOD__; })()];
    }
}
class B extends A {
    public static function prot() {
        return [B::$prot, parent::$prot, isset(B::$priv)];
    }
}

function inc(&$x) { $x++; return $x; }
function &id(&$x) { return $x; }

function checks($label) {
    echo "-- $label\n";
    var_dump(isset(A::$priv), A::$pub, B::prot());
    try {
        var_dump(A::$priv);
    } catch (Error $e) {
        echo $e->getMessage(), "\n";
    }
    try {
        A::$prot = 1;
    } catch (Error $e) {
        echo $e->getMessage(), "\n";
    }
    var_dump(A::reader()());
    $outside = function () { return isset(A::$priv) ? A::$priv : 'no access'; };
    var_dump($outside(), Closure::bind($outside, null, A::class)(), Closure::bind($outside, null, B::class)());

    $n = 1;
    $f = inc(...);
    $g = 'inc';
    $c = function (&$x) { $x++; };
    $f($n);
    $g($n);
    $c($n);
    $c->__invoke($n);
    $c->__INVOKE($n);
    call_user_func_array($c, [&$n]);
    var_dump($n);
    $ref = id(...);
    $r = &$ref($n);
    $r = 100;
    var_dump($n);

    var_dump(is_callable($c, false, $name), $name, is_callable([$c, '__invoke'], true, $name), $name);
    is_callable(inc(...), false, $name);
    var_dump($name);
    var_dump(inc(...) == inc(...), $c == $c, $c == function (&$x) {}, strlen(...) == strrev(...));

    $self = function ($depth) {
        return $depth > 0 ? Closure::getCurrent()($depth - 1) : 'bottom';
    };
    var_dump($self(3));
    try {
        Closure::getCurrent();
    } catch (Error $e) {
        echo $e->getMessage(), "\n";
    }

    try {
        $c->prop = 1;
    } catch (Error $e) {
        echo $e->getMessage(), "\n";
    }
    var_dump(isset($c->prop), property_exists($c, 'prop'));
    var_dump((new A)->method());
    var_dump(include 'closure-scope.inc');
}

class Base { public static function who() { return 'Base'; } }
class Scoped extends Base {
    public static function who() { return 'Scoped'; }
    public function get() {
        return function () {
            $inner = function () { return self::class; };
            return [self::class, static::class, parent::class, self::who(), static::who(), parent::who(), $inner()];
        };
    }
}
class Other extends Base {
    public static function who() { return 'Other'; }
}
function rebinding() {
    $f = (new Scoped)->get();
    var_dump($f(), Closure::bind($f, new Other, Other::class)(), $f->call(new Other));
    $unscoped = function () { return [self::class, static::class]; };
    try {
        $unscoped();
    } catch (Error $e) {
        echo $e->getMessage(), "\n";
    }
    var_dump(Closure::bind($unscoped, null, Scoped::class)(), Closure::bind($unscoped, new Other, Scoped::class)());
}

checks('function');
rebinding();
echo "-- top level\n";
var_dump(isset(A::$priv), A::$pub, B::prot());
try {
    var_dump(A::$priv);
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
$outside = function () { return isset(A::$priv) ? A::$priv : 'no access'; };
var_dump($outside(), Closure::bind($outside, null, A::class)());
$n = 1;
$f = inc(...);
$c = function (&$x) { $x++; };
$f($n);
$c($n);
$c->__invoke($n);
var_dump($n);
$ref = id(...);
$r = &$ref($n);
$r = 100;
var_dump($n);
var_dump(inc(...) == inc(...), $c == $c);
try {
    $c->prop = 1;
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
var_dump(include 'closure-scope.inc');
