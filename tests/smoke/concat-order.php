<?php
// The order of evaluation and conversion in concatenation: `a . b` (ZEND_CONCAT) evaluates both operands, then
// converts them left to right, so a chain `a . b . c` converts a and b once c... no: once b is evaluated, before
// c is; interpolation ("$a $b", a ROPE) evaluates and converts each part in turn; `$x .= v` evaluates v, then
// converts the variable's value and v. Warnings (Array to string, undefined variables) take their place in that
// order. Each case prints what ran, in order.

set_error_handler(function ($type, $message, $file, $line) {
    echo "[warn: $message]";
    return true;
});

class T {
    function __construct(public string $name) {
    }

    function __toString(): string {
        echo "[toString {$this->name}]";
        return $this->name;
    }
}

function g($name = 'g') {
    echo "[$name]";
    return new T($name);
}

function a($name) {
    echo "[$name]";
    return [];
}

function s($name) {
    echo "[$name]";
    return $name;
}

function show($label, $result) {
    echo " => $label: $result\n";
}

show('g . false . g', g('1') . false . g('2'));
show('g . g', g('1') . g('2'));
show('g . g . g', g('1') . g('2') . g('3'));
show('s . g . s . g', s('a') . g('1') . s('b') . g('2'));
show('g . "lit" . g', g('1') . "lit" . g('2'));
show('a . g', a('arr') . g('1'));
show('g . a', g('1') . a('arr'));
show('g . a . g', g('1') . a('arr') . g('2'));
show('(g . g) . (g . g)', (g('1') . g('2')) . (g('3') . g('4')));
show('g . (g . g)', g('1') . (g('2') . g('3')));
$o1 = new T('o1');
$o2 = new T('o2');
show('$o . $o', $o1 . $o2);
show('$o . 1 . $o', $o1 . 1 . $o2);
show('interpolation', "{$o1} {$o2} " . g('3'));
show('interpolation with calls', "x{$o1}y" . g('1') . "z{$o2}");
$arr = [];
show('interpolation with an array', "$o1 $arr $o2");
show('undefined and object', $o1 . $undefined . $o2);
$x = new T('x');
$x .= g('rhs');
show('.= object', $x);
$y = 'y';
$y .= g('rhs') . g('rhs2');
show('.= chain', $y);
show('echo-like concat in a function', (function () {
    $p = new T('p');
    $q = new T('q');
    return $p . $q . g('r') . $p;
})());
echo g('echo1'), g('echo2'), "\n";
echo "[before]" . g('cat1') . g('cat2'), "\n";
show('numbers', 1 . 2.5 . true . null . g('end'));
show('array then object then array', a('a1') . g('o') . a('a2'));
// opcache's block pass: an empty literal operand makes the concatenation a cast, and a cast operand's conversion
// moves into the concatenation that takes it
show("'' . g . g", '' . g('1') . g('2'));
show('g . null . g', g('1') . null . g('2'));
show("g . g . '' . g", g('1') . g('2') . '' . g('3'));
show("g . ('' . g)", g('1') . ('' . g('2')));
show('(string) g . g', (string) g('1') . g('2'));
show('g . (string) g', g('1') . (string) g('2'));
show('"$o" . g', "$o1" . g('1'));
show('g . "$o"', g('1') . "$o2");
show("g . ''", g('1') . '');
show("'' . g", '' . g('1'));
show("(string) a . g", (string) a('arr') . g('1'));
show('two expressions interpolated', "{$o1}{$o2}" . g('3'));
show('a literal and an expression', "x{$o1}" . g('4'));

// the same in a function (bytecode), a method, a closure and a generator
function inFunction() {
    show('g . false . g', g('1') . false . g('2'));
    show('g . g', g('1') . g('2'));
    show('g . g . g', g('1') . g('2') . g('3'));
    show('s . g . s . g', s('a') . g('1') . s('b') . g('2'));
    show('g . "lit" . g', g('1') . "lit" . g('2'));
    show('a . g', a('arr') . g('1'));
    show('g . a', g('1') . a('arr'));
    show('g . a . g', g('1') . a('arr') . g('2'));
    show('(g . g) . (g . g)', (g('1') . g('2')) . (g('3') . g('4')));
    show('g . (g . g)', g('1') . (g('2') . g('3')));
    $o1 = new T('o1');
    $o2 = new T('o2');
    show('$o . $o', $o1 . $o2);
    show('$o . 1 . $o', $o1 . 1 . $o2);
    show('interpolation', "{$o1} {$o2} " . g('3'));
    show('interpolation with calls', "x{$o1}y" . g('1') . "z{$o2}");
    $arr = [];
    show('interpolation with an array', "$o1 $arr $o2");
    show('undefined and object', $o1 . $undefined . $o2);
    $x = new T('x');
    $x .= g('rhs');
    show('.= object', $x);
    $y = 'y';
    $y .= g('rhs') . g('rhs2');
    show('.= chain', $y);
    show('echo-like concat in a function', (function () {
        $p = new T('p');
        $q = new T('q');
        return $p . $q . g('r') . $p;
    })());
    echo g('echo1'), g('echo2'), "\n";
    echo "[before]" . g('cat1') . g('cat2'), "\n";
    show('numbers', 1 . 2.5 . true . null . g('end'));
    show('array then object then array', a('a1') . g('o') . a('a2'));
    // opcache's block pass: an empty literal operand makes the concatenation a cast, and a cast operand's conversion
    // moves into the concatenation that takes it
    show("'' . g . g", '' . g('1') . g('2'));
    show('g . null . g', g('1') . null . g('2'));
    show("g . g . '' . g", g('1') . g('2') . '' . g('3'));
    show("g . ('' . g)", g('1') . ('' . g('2')));
    show('(string) g . g', (string) g('1') . g('2'));
    show('g . (string) g', g('1') . (string) g('2'));
    show('"$o" . g', "$o1" . g('1'));
    show('g . "$o"', g('1') . "$o2");
    show("g . ''", g('1') . '');
    show("'' . g", '' . g('1'));
    show("(string) a . g", (string) a('arr') . g('1'));
}
echo "-- function
";
inFunction();

class Runs {
    function method() {
        show('method g . false . g', g('1') . false . g('2'));
        show('method (string) g . g', (string) g('1') . g('2'));
        $o = new T('o');
        show('method "$o" . g', "$o" . g('1'));
    }
}
echo "-- method
";
(new Runs())->method();
echo "-- closure
";
(function () {
    show('closure g . null . g', g('1') . null . g('2'));
    $a = new T('a');
    $b = new T('b');
    show('closure two parts', "{$a}{$b}" . g('3'));
})();
function generates() {
    yield g('1') . '' . g('2');
    yield (string) a('arr') . g('3');
}
echo "-- generator
";
foreach (generates() as $value) {
    show('generator', $value);
}
