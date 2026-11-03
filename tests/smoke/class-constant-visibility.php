<?php
// Issue 012: a class constant's visibility is checked from the scope (zend_verify_const_access): a private
// constant only from its class, and not inherited; a protected one from classes related to its declaring class;
// constant() raises the Error, defined() says false. In functions and closures (bytecode), in constant
// initializers, and at file level (the tree).

function t($what, $f) {
    try {
        echo "$what: ", var_export($f(), true), "\n";
    } catch (Error $e) {
        echo "$what: ", get_class($e), ': ', $e->getMessage(), "\n";
    }
}

class A {
    private const P = 'A::P';
    protected const R = 'A::R';
    public const U = 'A::U';
    const SELF_P = self::P;
    static function p() { return self::P; }
    static function viaStatic() { return static::P; }
    static function definedP() { return defined('A::P'); }
    static function constantP() { return constant('A::P'); }
    static function closure() { return fn() => self::P; }
}
class B extends A {
    const OWN_R = self::R;
    static function r() { return self::R; }
    static function parentP() { return parent::P; }
    static function ownP() { return self::P; }
}
class Other {
    static function p() { return A::P; }
    static function r() { return A::R; }
}
class Sibling extends A {
    static function rOfB() { return B::R; }
}
class Redeclared extends A {
    protected const R = 'Redeclared::R';
}
class Cousin extends A {
    static function rOfRedeclared() { return Redeclared::R; }
}
class UsesBadInit {
    const BAD = A::P;
}
interface I {
    const IC = 'I::IC';
}
trait T {
    private const TC = 'T::TC';
    static function tc() { return self::TC; }
}
class UsesT {
    use T;
}

t('private in its class', fn() => A::p());
t('private initializer in its class', fn() => A::SELF_P);
t('private outside', fn() => A::P);
t('protected outside', fn() => A::R);
t('public outside', fn() => A::U);
t('private through static:: of a subclass', fn() => B::viaStatic());
t('private through static:: of the class', fn() => A::viaStatic());
t('private from a subclass by parent::', fn() => B::parentP());
t('private not inherited', fn() => B::ownP());
t('private of the parent by the subclass name', fn() => B::P);
t('protected in a subclass', fn() => B::r());
t('protected initializer in a subclass', fn() => B::OWN_R);
t('private from another class', fn() => Other::p());
t('protected from another class', fn() => Other::r());
t('protected through a sibling', fn() => Sibling::rOfB());
t('protected redeclared, from a cousin', fn() => Cousin::rOfRedeclared());
t('private in an initializer of another class', fn() => UsesBadInit::BAD);
t('interface constant', fn() => I::IC);
t('trait constant in its class', fn() => UsesT::tc());
t('trait constant outside', fn() => UsesT::TC);
t('closure in the class', fn() => (A::closure())());
t('closure bound to the class', fn() => (Closure::bind(fn() => A::P, null, A::class))());
t('defined in the class', fn() => A::definedP());
t('defined outside', fn() => defined('A::P'));
t('defined protected outside', fn() => defined('A::R'));
t('constant in the class', fn() => A::constantP());
t('constant outside', fn() => constant('A::P'));
t('dynamic class outside', function () { $c = 'A'; return $c::P; });
t('object class outside', fn() => (new A)::P);

// at file level
try {
    echo A::P, "\n";
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
try {
    echo B::P, "\n";
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
var_dump(defined('A::P'), defined('A::U'));
echo A::U, ' ', B::OWN_R, "\n";
