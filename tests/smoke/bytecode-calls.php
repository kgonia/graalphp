<?php
// Method calls, static calls, new, class constants, ::class, instanceof and clone in functions and methods on
// bytecode (-Dpolyglot.php.Interpreter=bytecode): the callee is found before the arguments, which go by reference
// where its parameters do; __call and __callStatic, Closure receivers, self/parent/static and the called class
// they forward, constructors and their arguments, and the traces of calls made from bytecode.

function rel($text) {
    return str_replace([__DIR__ . "\\", __DIR__ . "/"], "DIR/", $text);
}

function show($label, $value) {
    echo $label, ': ', json_encode($value), "\n";
}

function cls($o) {
    return get_class($o);
}

function report($label, $e) {
    echo $label, ': ', get_class($e), ': ', $e->getMessage(), "\n";
}

class Base {
    const NAME = 'base';
    const KIND = 'base kind';
    public $log = [];

    function __construct($first = 'none') {
        $this->log[] = 'Base::__construct ' . $first;
    }

    static function create() {
        return new static('created');
    }

    static function make() {
        return static::create();
    }

    static function selfMake() {
        return self::create();
    }

    function hello($who) {
        return 'hello ' . $who . ' from ' . static::NAME;
    }

    static function who() {
        return static::class . ' ' . self::class . ' ' . static::NAME . ' ' . self::NAME;
    }

    function constants() {
        return [self::NAME, static::NAME, Base::KIND, static::KIND];
    }

    function nonStatic() {
        return isset($this) ? 'with $this of ' . cls($this) : 'without $this';
    }
}

class Child extends Base {
    const NAME = 'child';

    function __construct($first = 'none', $second = 'none') {
        parent::__construct($first);
        $this->log[] = 'Child::__construct ' . $second;
    }

    function hello($who) {
        return parent::hello($who) . ' (child)';
    }

    function parentThings() {
        return [parent::NAME, parent::class, parent::nonStatic(), Base::nonStatic(), self::nonStatic()];
    }

    static function staticParent() {
        return parent::who();
    }

    function newOnes($class) {
        $self = new self('self');
        $parent = new parent('parent');
        $static = new static('static');
        $named = new $class('named');
        $fromObject = new $this('object');
        return [cls($self), cls($parent), cls($static), cls($named), cls($fromObject),
            $self->log, $parent->log];
    }
}

final class Leaf extends Child {
    const NAME = 'leaf';
}

function methods() {
    $b = new Base('b');
    $c = new Child('c1', 'c2');
    show('logs', [$b->log, $c->log]);
    show('hello', [$b->hello('you'), $c->hello('me')]);
    show('static via ->', [$b->who(), $c->who()]);
    show('make', [cls(Base::make()), cls(Child::make()), cls(Leaf::make()),
        cls(Child::selfMake())]);
    show('who', [Base::who(), Child::who(), Leaf::who(), Leaf::staticParent()]);
    show('constants', [$b->constants(), $c->constants(), (new Leaf())->constants()]);
    show('class constants', [Base::NAME, Child::NAME, Leaf::KIND, Base::class, Leaf::class]);
    show('parent things', (new Leaf())->parentThings());
    show('new', (new Leaf())->newOnes('Base'));
}
methods();

// arguments by reference, by value, from elements, properties and calls
class Refs {
    public $count = 0;

    function inc(&$value, $by = 1) {
        $value += $by;
        return $value;
    }

    static function append(&$list, $item) {
        $list[] = $item;
    }

    function &counter() {
        return $this->count;
    }

    function value($v) {
        return $v;
    }
}

function references() {
    $r = new Refs();
    $x = 1;
    $r->inc($x);
    $r->inc($x, 10);
    $a = ['k' => 5];
    $r->inc($a['k']);
    $r->inc($a['new']);
    $r->inc($r->count, 3);
    $list = [];
    Refs::append($list, 'one');
    Refs::append($list, 'two');
    $byValue = $r->value($x);
    $r->inc($r->counter());
    return [$x, $a, $r->count, $list, $byValue];
}
show('references', references());

class Ctor {
    public $seen;

    function __construct(&$value) {
        $value = 'set by the constructor';
        $this->seen = $value;
    }
}

function constructorByRef() {
    $v = 'before';
    $o = new Ctor($v);
    return [$v, $o->seen];
}
show('constructor by reference', constructorByRef());

class NoConstructor {
}

function sideEffect() {
    echo "argument evaluated\n";
    return 1;
}

// php-src evaluates the arguments of a class without a constructor (zend_pass_function)
function noConstructor() {
    $o = new NoConstructor(sideEffect());
    return cls($o);
}
show('no constructor', noConstructor());

// __call and __callStatic
class Magic {
    public $calls = [];

    function __call($name, $args) {
        $this->calls[] = $name . '(' . implode(', ', $args) . ')';
        return 'called ' . $name;
    }

    static function __callStatic($name, $args) {
        return 'static ' . $name . '(' . implode(', ', $args) . ')';
    }

    function viaStatic() {
        return [self::inside(1), static::inside(2), Magic::inside(3)];
    }
}

function magic() {
    $m = new Magic();
    $results = [$m->doSomething(1, 2), $m->CamelCase('x'), Magic::staticThing('y'), $m->viaStatic()];
    return [$results, $m->calls];
}
show('magic', magic());

// a Closure receiver
class Target {
    public $secret = 'target secret';
}

function closures($add, $peek) {
    $bound = $peek->bindTo(new Target());
    return [$add->__invoke(2, 3), $bound->__invoke('!'), $peek->call(new Target(), '?'), $add instanceof Closure];
}
show('closures', closures(function ($a, $b) {
    return $a + $b;
}, function ($suffix) {
    return $this->secret . $suffix;
}));

// errors
function callOnNull($v) {
    return $v->method();
}
function undefinedMethod($o) {
    return $o->nope();
}
function undefinedStatic() {
    return Base::nope();
}
function undefinedConstant() {
    return Base::NOPE;
}
function unknownClass() {
    return new DoesNotExist();
}
function unknownStatic() {
    return DoesNotExist::m();
}
foreach (['callOnNull', 'undefinedMethod', 'undefinedStatic', 'undefinedConstant', 'unknownClass',
             'unknownStatic'] as $f) {
    try {
        $f($f === 'callOnNull' ? null : new Base());
    } catch (Error $e) {
        report($f, $e);
    }
}
try {
    callOnNull(42);
} catch (Error $e) {
    report('callOnNull 42', $e);
}

// instanceof
interface Shape {
}
class Square implements Shape {
    function isSelf($x) {
        return $x instanceof self;
    }
}
function instances($x, $class) {
    return [$x instanceof Shape, $x instanceof Square, $x instanceof Base, $x instanceof Unknown,
        $x instanceof $class, $x instanceof Closure];
}
show('instanceof square', instances(new Square(), 'Shape'));
show('instanceof closure', instances(function () {
}, 'Closure'));
show('instanceof scalar', instances(5, 'Square'));
show('instanceof object class', instances(new Square(), new Square()));
show('instanceof self', [(new Square())->isSelf(new Square()), (new Square())->isSelf(new Base())]);

// clone
class Cloned {
    public $items = [1];
    public $copies = 0;

    function __clone() {
        $this->copies++;
        $this->items[] = 'cloned';
    }
}
function cloning() {
    $a = new Cloned();
    $b = clone $a;
    $c = clone $b;
    return [$a->items, $b->items, $c->items, $c->copies, $a === $b];
}
show('clone', cloning());

// traces of exceptions thrown in methods called from bytecode
class Thrower {
    function instance($arg) {
        throw new RuntimeException('from instance');
    }

    static function statically($arg) {
        return (new Thrower())->instance($arg);
    }

    function __call($name, $args) {
        return self::statically($name);
    }
}

function traced() {
    try {
        $t = new Thrower();
        $t->magicName('x');
    } catch (RuntimeException $e) {
        echo rel($e->getTraceAsString()), "\n";
        echo 'line ', $e->getLine(), "\n";
    }
    try {
        Thrower::statically([1, 2]);
    } catch (RuntimeException $e) {
        echo rel($e->getTraceAsString()), "\n";
    }
}
traced();

function made() {
    $e = new LogicException('made here');
    return [$e->getLine(), rel($e->getTraceAsString())];
}
show('made', made());

// a generator calling methods around its yields
function generating($o) {
    $r = new Refs();
    $n = 0;
    while ($n < 3) {
        $r->inc($n);
        yield $n => $o->hello('gen ' . $n);
    }
    return Base::who();
}
$g = generating(new Child());
foreach ($g as $k => $v) {
    echo $k, ': ', $v, "\n";
}
echo $g->getReturn(), "\n";

// ?-> short-circuits the rest of its chain (ZEND_JMP_NULL); in a generator, which runs on bytecode in either
// mode (the tree has no ?->)
class Node {
    public $name;
    public $inner;
    public $list = ['k' => 'element'];

    function __construct($name, $inner = null) {
        $this->name = $name;
        $this->inner = $inner;
    }

    function self() {
        return $this;
    }

    function hello($who) {
        return 'hello ' . $who . ' from ' . $this->name;
    }
}

function nullsafe($o) {
    yield 'name' => $o?->name;
    yield 'inner name' => $o?->inner?->name;
    yield 'chain' => $o?->self()->inner?->self()->name;
    yield 'element' => $o?->list['k'];
    yield 'call' => $o?->hello(sideEffect());
    yield 'inner call' => $o?->inner?->hello(sideEffect());
}
foreach ([null, new Node('outer'), new Node('outer', new Node('inner'))] as $o) {
    foreach (nullsafe($o) as $k => $v) {
        echo $k, ': ', json_encode($v), "\n";
    }
}
function nullsafeError($o) {
    try {
        yield $o?->inner->hello('x');
    } catch (Error $e) {
        yield $e->getMessage();
    }
}
foreach (nullsafeError(new Node('no inner')) as $v) {
    echo json_encode($v), "\n";
}
