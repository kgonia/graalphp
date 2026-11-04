<?php
// Calls of callable values in functions on bytecode: `$f(args)` of names,
// closures, [$obj, 'm'], ['Cls', 'm'], 'Cls::m' and invokable objects, `$obj->$name(args)`, call_user_func(),
// call_user_func_array(), first-class callable syntax, func_get_args(), func_num_args(), get_class() and
// get_called_class(), and parameter defaults that aren't literals; with php-src's errors.

set_error_handler(function ($type, $message, $file, $line) {
    echo "[$type] $message (line $line)\n";
    return true;
});

const LIMIT = 3;

function twice($x) {
    return $x * 2;
}

function inc(&$x) {
    $x++;
    return $x;
}

function &counter() {
    static $count = 0;
    return $count;
}

function args() {
    return [func_num_args(), func_get_args()];
}

class Calc {
    const BASE = 100;
    public $factor = 3;

    function scale($x) {
        return $x * $this->factor;
    }

    static function plus($a, $b = self::BASE) {
        return $a + $b;
    }

    function exclaim(&$x) {
        $x .= '!';
    }

    function __invoke($x) {
        return "invoked $x";
    }

    function __call($name, $args) {
        return "__call $name(" . implode(', ', $args) . ")";
    }

    static function __callStatic($name, $args) {
        return "__callStatic $name(" . implode(', ', $args) . ")";
    }

    function names() {
        return [get_class($this), get_called_class()];
    }

    static function staticNames() {
        return [self::class, get_called_class()];
    }

    // a generator runs on bytecode in either interpreter: the tree has no deprecation for get_class()
    function generatedNames() {
        yield get_class();
        yield get_called_class();
    }

    function converted() {
        return [self::plus(...), static::plus(...), $this->scale(...)];
    }
}

class Sub extends Calc {
}

function show($label, $f) {
    try {
        echo $label, ": ", json_encode($f()), "\n";
    } catch (Throwable $e) {
        echo $label, ": ", get_class($e), ": ", $e->getMessage(), "\n";
    }
}

function values() {
    $f = 'twice';
    var_dump($f(4));
    $f = 'TWICE';
    var_dump($f(5));
    $c = new Calc;
    $m = [$c, 'scale'];
    var_dump($m(2));
    $s = ['Calc', 'plus'];
    var_dump($s(1, 2), $s(1));
    $t = 'Calc::plus';
    var_dump($t(5, 5));
    var_dump('Calc::plus'(7, 1));
    var_dump($c(9));
    $closure = function ($a, $b) {
        return "$a-$b";
    };
    var_dump($closure('x', 'y'));
    var_dump([$c, 'missing'](1, 2), ['Calc', 'gone'](3));
    $name = 'scale';
    var_dump($c->$name(10));
    $name = 'magic';
    var_dump($c->$name('m'));
    $parts = ['sc', 'ale'];
    var_dump($c->{$parts[0] . $parts[1]}(1));
    show('undefined', fn() => ('nope')());
    show('not callable', function () {
        $n = 42;
        return $n();
    });
    show('no method', function () {
        $x = [new stdClass, 'm'];
        return $x();
    });
}
values();

function builtins() {
    var_dump(call_user_func('twice', 21));
    var_dump(call_user_func([new Calc, 'scale'], 2), call_user_func('Calc::plus', 1, 1));
    var_dump(call_user_func_array(fn($a, $b) => $a . $b, ['p', 'q']));
    var_dump(call_user_func_array([new Calc, 'scale'], [5]));
    var_dump(call_user_func_array('Calc::plus', [1]));
    show('invalid', fn() => call_user_func('nope'));
    $n = 1;
    $r = call_user_func_array('inc', [&$n]);
    var_dump($n, $r);
}
builtins();

function conversions() {
    $twice = twice(...);
    var_dump($twice(8));
    $strlen = strlen(...);
    var_dump($strlen('four'));
    $c = new Calc;
    $scale = $c->scale(...);
    var_dump($scale(3));
    $dynamic = 'scale';
    $byName = $c->$dynamic(...);
    var_dump($byName(4));
    $plus = Calc::plus(...);
    var_dump($plus(1, 2));
    $f = 'twice';
    $fromValue = $f(...);
    var_dump($fromValue(6));
    $fromArray = [$c, 'scale'](...);
    var_dump($fromArray(7));
    $magic = $c->magic(...);
    var_dump($magic('a'));
    [$self, $static, $bound] = (new Sub)->converted();
    var_dump($self(1, 1), $static(2, 2), $bound(3));
    show('undefined function', fn() => undefinedFunction(...));
    show('undefined method', fn() => (new stdClass)->m(...));
}
conversions();

function frames($a, $b = 'default', ...$rest) {
    return [func_num_args(), func_get_args()];
}

function introspection() {
    var_dump(frames(1));
    var_dump(frames(1, 2, 3, 4));
    var_dump(args(), args('x', ['y']));
    var_dump((new Calc)->names(), (new Sub)->names(), Sub::staticNames(), Calc::staticNames());
}
introspection();

function defaults($limit = LIMIT, $base = Calc::BASE, $object = new Calc, $sum = Calc::BASE + LIMIT) {
    return [$limit, $base, get_class($object), $sum];
}

function parameterDefaults() {
    var_dump(defaults(), defaults(1), defaults(1, 2, new Sub));
    var_dump(Calc::plus(1));
}
parameterDefaults();

// Generators run on bytecode in either interpreter: what the tree does differently from php-src is tested in them
// (the tree passes a callable's arguments by value, and its func_get_args() gives the arguments as passed).
function drive($generator) {
    foreach ($generator as $ignored) {
    }
}

function modified($a, $b) {
    $a = 'changed';
    unset($b);
    yield func_get_args();
}

function byReference() {
    yield;
    $f = 'inc';
    $n = 1;
    $f($n);
    var_dump($n);
    $arr = ['k' => 5];
    $f($arr['k']);
    var_dump($arr);
    $c = new Calc;
    $name = 'scale';
    $s = 'hey';
    [$c, 'exclaim']($s);
    var_dump($s);
    $r = &counter();
    $g = 'counter';
    $g();
    $r = 5;
    var_dump(counter());
    var_dump(modified(1, 2)->current());
    var_dump(iterator_to_array((new Sub)->generatedNames(), false));
}
drive(byReference());
