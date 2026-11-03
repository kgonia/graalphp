<?php
// A static property bound by reference: `$x = &A::$p`, `A::$p = &$x`, `static::$p`, `self::$p`, `parent::$p`,
// `$cls::$p`, elements of it, `A::$p` as a by-reference argument, foreach by reference, in a function (bytecode)
// and at file level (the tree); php-src's Error for a missing one.

class A {
    public static $p = 'p';
    public static $list = [1, 2];
    protected static $hidden = 'h';
    private static $own = 'o';
    static function refs() {
        $s = &static::$p;
        $s .= '+static';
        $t = &self::$p;
        $t .= '+self';
        return A::$p;
    }
    static function bindOwn() {
        $x = 'bound';
        self::$own = &$x;
        $x .= '!';
        return self::$own;
    }
}
class B extends A {
    public static $p = 'Bp';
    static function parentRef() {
        $r = &parent::$p;
        $r .= '+parent';
        return [A::$p, B::$p, static::refs()];
    }
    static function hiddenRef() {
        $h = &self::$hidden;
        $h = 'changed';
        return A::$hidden;
    }
}

function add(&$v) { $v .= '+arg'; }

function t($what, $f) {
    try {
        echo "$what: ", var_export($f(), true), "\n";
    } catch (Error $e) {
        echo "$what: ", get_class($e), ': ', $e->getMessage(), "\n";
    }
}

t('reference to A::$p', function () { $x = &A::$p; $x = 'changed'; return A::$p; });
t('A::$p bound to a variable', function () { $y = 'y'; A::$p = &$y; $y = 'y2'; return A::$p; });
t('static and self', fn() => A::refs());
t('static in a subclass', fn() => B::refs());
t('parent', fn() => B::parentRef());
t('class in a variable', function () { $c = 'A'; $x = &$c::$p; $x = 'via var'; return A::$p; });
t('element', function () { $e = &A::$list[0]; $e = 10; $n = &A::$list[]; $n = 3; return A::$list; });
t('argument', function () { add(A::$p); $f = 'add'; $f(A::$p); return A::$p; });
t('foreach', function () { foreach (A::$list as &$v) { $v *= 2; } unset($v); return A::$list; });
t('protected from a subclass', fn() => B::hiddenRef());
t('private bound', fn() => A::bindOwn());
t('missing', function () { $x = &A::$nope; return $x; });
t('static outside a class', function () { $x = &static::$p; return $x; });

// at file level
$f = &A::$p;
$f = 'file';
echo A::$p, "\n";
$g = 'g';
B::$p = &$g;
$g = 'g2';
echo B::$p, ' ', A::$p, "\n";
$e = &A::$list[5];
$e = 'five';
echo json_encode(A::$list), "\n";
try {
    $m = &A::$missing;
} catch (Error $err) {
    echo $err->getMessage(), "\n";
}
