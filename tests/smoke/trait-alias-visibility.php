<?php
// A trait alias's visibility (`m as public`, `m as protected n`) applies to the method the class gets; the
// original name keeps its own (zend_traits_copy_functions).

trait A {
    protected static function foo() { echo "foo\n"; }
    private static function bar() { echo "bar\n"; }
    public function open() { echo "open\n"; }
}

class B {
    use A {
        A::foo as public;
        A::bar as public baz;
        open as protected shut;
    }
}

function attempt(Closure $f) {
    try {
        $f();
    } catch (Error $e) {
        echo get_class($e), ": ", $e->getMessage(), "\n";
    }
}

B::foo();
B::baz();
attempt(fn() => B::bar());
$b = new B();
$b->open();
attempt(fn() => $b->shut());
var_dump(is_callable([$b, 'shut']), is_callable('B::baz'), is_callable('B::bar'));

// through a trait that uses another one with an alias
trait T1 {
    protected function aa() { echo "aa\n"; }
}

trait T2 {
    use T1 {
        aa as public;
    }
}

class C {
    use T1;
}

class D extends C {
    use T2;
}

(new D())->aa();
attempt(fn() => (new C())->aa());

// a visibility alias on a private method makes it callable from outside
trait Hidden {
    private function secret() { echo "secret\n"; }
}

class E {
    use Hidden {
        secret as public;
    }
}

(new E())->secret();
$m = new ReflectionMethod('B', 'baz');
var_dump($m->isPublic(), (new ReflectionMethod('B', 'bar'))->isPrivate(), (new ReflectionMethod('B', 'shut'))->isProtected());
