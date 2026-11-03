<?php
// Issue 013: a method's visibility is checked from the calling scope (zend_std_get_method,
// zend_std_get_static_method, zend_is_callable_check_func): a private method of the calling scope wins over a
// subclass's redeclaration (ZEND_ACC_CHANGED), an inaccessible method goes to __call or __callStatic, else php-src's
// Error; protected methods are checked against their root class; constructors and __clone too. In functions and
// closures (bytecode) and at file level (the tree).

function t($what, $f) {
    try {
        $r = $f();
        echo "$what: ", var_export($r, true), "\n";
    } catch (Error $e) {
        echo "$what: ", get_class($e), ': ', $e->getMessage(), "\n";
    }
}

class A {
    private function f() { return 'A::f'; }
    function g() { return $this->f(); }
    function viaArray() { return [$this, 'f'](); }
    function viaName() { $n = 'f'; return $this->$n(); }
    function viaCallable() { return call_user_func([$this, 'f']); }
    function viaConvert() { return ($this->f(...))(); }
    function isCallable() { return is_callable([$this, 'f']); }
    private static function ps() { return 'A::ps'; }
    static function callPs() { return self::ps(); }
    static function callStaticPs() { return static::ps(); }
}
class B extends A {
    function f() { return 'B::f'; }
    function own() { return $this->f(); }
}
t('private of the calling scope', fn() => (new B)->g());
t('array callable in scope', fn() => (new B)->viaArray());
t('dynamic name in scope', fn() => (new B)->viaName());
t('call_user_func in scope', fn() => (new B)->viaCallable());
t('first-class in scope', fn() => (new B)->viaConvert());
t('is_callable in scope', fn() => (new A)->isCallable());
t('subclass redeclared', fn() => (new B)->own());
t('public redeclaration outside', fn() => (new B)->f());
t('private outside', fn() => (new A)->f());
t('private by name outside', function () { $n = 'f'; return (new A)->$n(); });
t('private array callable outside', fn() => [new A, 'f']());
t('private call_user_func outside', fn() => call_user_func([new A, 'f']));
t('private is_callable outside', fn() => is_callable([new A, 'f']));
t('private first-class outside', fn() => (new A)->f(...));
t('private static outside', fn() => A::ps());
t('private static string outside', fn() => 'A::ps'());
t('private static in scope', fn() => A::callPs());
t('private static via static::', fn() => B::callStaticPs());

class C {
    private function p() { return 'p'; }
    protected function q() { return 'q'; }
    public function __call($n, $a) { return "__call $n"; }
    public static function __callStatic($n, $a) { return "__callStatic $n"; }
    private static function sp() { return 'sp'; }
}
t('inaccessible goes to __call', fn() => (new C)->p());
t('protected goes to __call', fn() => (new C)->q());
t('inaccessible static goes to __callStatic', fn() => C::sp());
t('callable to __call', fn() => call_user_func([new C, 'p']));

class D {
    protected function q() { return 'D::q'; }
    private function r() { return 'D::r'; }
}
class E {
    function callQ(D $d) { return $d->q(); }
    function callR(D $d) { return $d->r(); }
}
t('protected from global scope', fn() => (new D)->q());
t('protected from another scope', fn() => (new E)->callQ(new D));
t('private from another scope', fn() => (new E)->callR(new D));

// protected: checked against the root class of its declaration
class Base {
    protected function shared() { return 'Base::shared'; }
    function hook() { return $this->hooked(); }
}
class Left extends Base {
    protected function shared() { return 'Left::shared'; }
    protected function only() { return 'Left::only'; }
    protected function hooked() { return 'Left::hooked'; }
}
class Right extends Base {
    function viaSibling(Left $l) { return $l->shared(); }
    function onlySibling(Left $l) { return $l->only(); }
    function viaParent(Base $b) { return $b->shared(); }
}
t('protected of a sibling sharing the root', fn() => (new Right)->viaSibling(new Left));
t('protected declared only in a sibling', fn() => (new Right)->onlySibling(new Left));
t('protected of the parent', fn() => (new Right)->viaParent(new Base));
t('parent calls a subclass protected', fn() => (new Left)->hook());

// constructors and __clone
class Single {
    private static $one;
    private function __construct() { echo "constructed\n"; }
    private function __clone() {}
    static function get() { return self::$one ??= new Single; }
    function copy() { return clone $this; }
}
class Child extends Single {
    function __construct() { parent::__construct(); }
}
class Guarded {
    protected function __construct() {}
    static function make() { return new static; }
}
class GuardedChild extends Guarded {
    static function build() { return new GuardedChild; }
}
t('private constructor outside', fn() => new Single);
t('private constructor in scope', fn() => get_class(Single::get()));
t('private parent constructor', fn() => new Child);
t('protected constructor outside', fn() => new Guarded);
t('protected constructor through static', fn() => get_class(Guarded::make()));
t('protected constructor from a subclass', fn() => get_class(GuardedChild::build()));
t('dynamic new', function () { $c = 'Single'; return new $c; });
t('private clone outside', fn() => clone Single::get());
t('private clone in scope', fn() => get_class(Single::get()->copy()));

// closures: their scope, bound or rebound
class F {
    private function secret() { return 'F::secret'; }
    function closure() { return function () { return $this->secret(); }; }
    function staticClosure() { return static function (F $f) { return $f->secret(); }; }
}
t('closure in its class', fn() => ((new F)->closure())());
t('static closure in its class', fn() => ((new F)->staticClosure())(new F));
$outside = function (F $f) { return $f->secret(); };
t('closure outside', fn() => $outside(new F));
t('closure bound to the class', fn() => (Closure::bind($outside, null, F::class))(new F));
t('closure bound to an object', fn() => (Closure::bind(fn() => $this->secret(), new F, F::class))());

// traits: the using class's scope
trait T {
    private function fromTrait() { return 'T::fromTrait'; }
}
class UsesT {
    use T;
    function call() { return $this->fromTrait(); }
}
t('private trait method in the class', fn() => (new UsesT)->call());
t('private trait method outside', fn() => (new UsesT)->fromTrait());

// at file level (the tree)
try {
    (new A)->f();
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
try {
    A::ps();
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
try {
    new Single;
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
try {
    [new A, 'f']();
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
echo (new B)->g(), ' ', (new C)->p(), ' ', C::sp(), "\n";
var_dump(is_callable([new A, 'f']));
