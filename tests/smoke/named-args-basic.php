<?php
// Named arguments (php-src's zend_handle_named_arg, zend_handle_undef_args): bound by parameter name, case-sensitive,
// in any order and after positional ones; a skipped parameter takes its default, a constant expression evaluated at
// the call (in the callee's class scope); func_get_args() and func_num_args() count up to the last parameter bound,
// the skipped ones as their defaults; reserved words as names; by-reference parameters by name; methods, static
// methods, constructors (promoted parameters too), closures, arrow functions, __invoke, first-class callables,
// `new` in a default and in a constant expression; the order arguments are evaluated in.

function show($label, $value) {
    echo $label, ': ', json_encode($value), "\n";
}

function five($a, $b, $c = 'c', $d = 'd', $e = 'e') {
    return "a=$a b=$b c=$c d=$d e=$e";
}

function counts($a, $b = 'b', $c = 'c') {
    return [func_num_args(), func_get_args(), func_get_arg(func_num_args() - 1)];
}

function keywords($array, $list = 0, $function = 1, $class = 2) {
    return [$array, $list, $function, $class];
}

function cased($value, $Value = 'upper') {
    return [$value, $Value];
}

function refs(&$x, $y = 0, &$z = null) {
    $x .= '+x';
    $z .= '+z';
}

const LATER = 'later';

function laterConst($a = LATER, $b = 0) {
    return [$a, $b];
}

class Logged {
    public static $made = 0;

    public function __construct(public string $tag = 'logged') {
        self::$made++;
        echo "  Logged({$this->tag}) made\n";
    }
}

function withNew($a = 1, $b = new Logged('default'), $c = 3) {
    return [$a, $b->tag, $c];
}

class Shape {
    const SIDES = 4;

    public function __construct(public $name = 'shape', public $sides = self::SIDES, public $color = 'none') {
    }

    public function describe($prefix = '', $suffix = '') {
        return $prefix . $this->name . '/' . $this->sides . '/' . $this->color . $suffix;
    }

    public static function make($name = 'made', $sides = self::SIDES * 2) {
        return new static(sides: $sides, name: $name);
    }
}

class Square extends Shape {
    const SIDES = 40;

    public static function parentDefault($sides = parent::SIDES, $x = 0) {
        return $sides;
    }
}

class Inv {
    public function __invoke($a = 'a', $b = 'b') {
        return "$a,$b";
    }
}

// named arguments in a constant expression's `new` (php-src's zend_compile_const_expr_args)
const ITEM = new Shape(color: 'red', name: 'const');

function staticNew() {
    static $made = new Shape(sides: 6);
    return $made->describe();
}

function trace($label) {
    echo "  eval $label\n";
    return $label;
}

function main() {
    show('positional', five('A', 'B', 'C', 'D', 'E'));
    show('named after positional', five('A', 'B', 'C', d: 'D', e: 'E'));
    show('named reversed', five('A', 'B', 'C', e: 'E', d: 'D'));
    show('all named, shuffled', five(e: 'E', a: 'A', d: 'D', b: 'B', c: 'C'));
    show('skip c and d', five('A', 'B', e: 'E'));
    show('skip all optional but e', five(b: 'B', a: 'A', e: 'E'));
    show('in order, named', five(a: 'A', b: 'B'));

    show('func_get_args skipped', counts(c: 'C', a: 'A'));
    show('func_get_args in order', counts(a: 'A', b: 'B'));
    show('func_get_args only first', counts(a: 'A'));

    show('reserved words', keywords(array: [1], class: 'K', list: 'L'));
    show('case-sensitive', cased(Value: 'U', value: 'v'));

    $p = 'p';
    $q = 'q';
    refs(z: $q, x: $p);
    show('by reference by name', [$p, $q]);
    $arr = ['k' => 'v'];
    refs(x: $arr['k'], z: $arr['new']);
    show('by reference elements by name', $arr);

    show('constant default at call time', laterConst(b: 1));
    Logged::$made = 0;
    show('new in a skipped default', withNew(c: 30));
    show('new in a trailing default', withNew(1));
    show('new not evaluated when passed', withNew(b: new Logged('passed')));
    show('objects made', Logged::$made);

    $s = new Shape(color: 'blue');
    show('constructor named', $s->describe(suffix: '!'));
    show('promoted, self:: default', (new Shape('x', color: 'green'))->describe());
    show('static method, new static', Shape::make(name: 'n')->describe());
    show('static method on subclass', Square::make(name: 'sq')->describe());
    show('static::', Square::make()->describe(prefix: '>'));
    show('parent:: default skipped', Square::parentDefault(x: 1));
    show('constant expression new', ITEM->describe());
    show('static variable new', staticNew());

    $closure = function ($a = 'a', $b = 'b', ...$rest) {
        return [$a, $b, $rest];
    };
    show('closure', $closure(b: 'B'));
    show('closure extra', $closure(b: 'B', z: 'Z'));
    $arrow = fn($x, $y = 'y') => "$x$y";
    show('arrow function', $arrow(y: 'Y', x: 'X'));
    $inv = new Inv();
    show('__invoke', $inv(b: 'B'));
    show('__invoke explicit', $inv->__invoke(b: 'B2'));
    $fcc = five(...);
    show('first-class callable', $fcc('A', 'B', d: 'D'));
    $mfcc = $s->describe(...);
    show('first-class method', $mfcc(suffix: '?'));
    $name = 'five';
    show('by name in a variable', $name(e: 'E', b: 'B', a: 'A'));
    $method = 'describe';
    show('dynamic method', $s->$method(prefix: '#'));
    show('callable array', [$s, 'describe'](suffix: '$'));
    show('callable string static', 'Shape::make'(sides: 3)->describe());

    echo "evaluation order:\n";
    show('order', five(trace('a'), trace('b'), e: trace('e'), c: trace('c')));
    show('nested named', five(b: five('x', 'y', e: 'z'), a: strtoupper(string: 'q')));
}

main();
