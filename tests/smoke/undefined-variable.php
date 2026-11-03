<?php
// "Undefined variable $x" (zval_undefined_cv): a read of an unset variable warns and reads null; so does a
// read-modify-write ($x .= , $x++, $x += ), before the operation. isset, empty, ??, ??=, by-reference uses, unset
// and writes (also $x[] = and $x['k'] =) don't. In functions, methods, closures, generators and at file level;
// a handler that throws stops the operation at the warning.

set_error_handler(function ($type, $message, $file, $line) {
    echo "[$type] $message (line $line)\n";
    return true;
});

function byValue($v) {
    return 'byValue:' . var_export($v, true);
}

function byRef(&$v) {
    return 'byRef:' . var_export($v, true);
}

class Str {
    function __toString(): string {
        return 'str';
    }
}

function reads() {
    echo "echo: ", $x, "\n";
    $y = $x;
    var_dump($y);
    echo byValue($x), "\n";
    echo "concat: " . $x . "|\n";
    echo "interpolation: $x|{$x}|\n";
    var_dump($x == null, $x + 1, -$x, !$x, (string) $x, (int) $x);
    if ($x) {
        echo "never\n";
    }
    var_dump(strlen($x ?? ''));
    $f = 'byValue';
    echo $f($x), "\n";
    echo [$x][0] ?? 'null', "\n";
    var_dump([$x, 'k' => $x]);
    return $x;
}
var_dump(reads());

function quiet() {
    var_dump(isset($x), empty($x), $x ?? 'default', isset($x['k']), isset($x->p), $x['k'] ?? 'dim default');
    echo byRef($x), "\n";
    unset($x);
    unset($z);
    $w ??= 'assigned';
    var_dump($w);
    $a[] = 1;
    $b['k'] = 2;
    $c['k']['j'] = 3;
    $r = &$d;
    var_dump($a, $b, $c, $d);
    $e = [&$g];
    var_dump($e);
}
quiet();

function modifies() {
    $a .= 'a';
    var_dump($a);
    $b++;
    var_dump($b);
    $c--;
    var_dump($c);
    ++$d;
    var_dump($d);
    $e += 2;
    var_dump($e);
    $f *= 3;
    var_dump($f);
    $g .= $g;
    var_dump($g);
    $h = $h . 'h';
    var_dump($h);
}
modifies();

function containers() {
    echo "dim\n";
    var_dump($a['k']);
    echo "prop\n";
    var_dump($o->p);
    echo "foreach\n";
    foreach ($l as $v) {
        echo "never\n";
    }
    echo "list\n";
    [$p, $q] = $m;
    var_dump($p, $q);
    list('k' => $s) = $n;
    var_dump($s);
    echo "key\n";
    $arr = ['k' => 'v'];
    var_dump($arr[$key . 'k']);
    echo "call\n";
    try {
        $obj->method();
    } catch (Error $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
    echo "compact\n";
    $defined = 1;
    var_dump(compact('defined', 'missing'));
    echo "closure use\n";
    $fn = function () use ($captured) {
        return $captured;
    };
    var_dump($fn());
    echo "arrow fn\n";
    $arrow = fn() => $outer;
    var_dump($arrow());
}
containers();

class Holder {
    public $p = 1;

    function method() {
        return $missing . '|' . $this->p;
    }

    static function staticMethod() {
        $missing2++;
        return $missing2;
    }
}
var_dump((new Holder())->method(), Holder::staticMethod());

function gen() {
    yield $x;
    $y .= 'y';
    yield $y;
}
foreach (gen() as $v) {
    var_dump($v);
}

$closure = function () {
    return $inClosure;
};
var_dump($closure());

// file level: the global scope
echo "top: ", $topLevel, "\n";
$topCounter++;
var_dump($topCounter);
$topString .= 'x';
var_dump($topString);
var_dump(isset($topMissing), $topMissing ?? 'top default');
var_dump($topArray['k']);
var_dump(compact('topMissing'));

function more() {
    $a['k'] .= 'x';
    $b['k']['j']++;
    var_dump($a, $b);
    try {
        var_dump(byValue(...$spread));
    } catch (Error $e) {
        echo get_class($e), ': ', $e->getMessage(), "
";
    }
    foreach ($w as &$v) {
    }
    var_dump($w);
    var_dump($GLOBALS['nope'], $_SESSION, $_SESSION['a'] ?? 'quiet', isset($GLOBALS['zz']), $GLOBALS['zz'] ?? 'q2');
    $GLOBALS['created'] .= 'a';
    $GLOBALS['counter']++;
    $name = 'dynamic';
    var_dump($GLOBALS[$name], $GLOBALS['created'], $GLOBALS['counter']);
    $fn = fn() => $z ?? 'quiet in an arrow fn';
    var_dump($fn());
    $c = 3;
    var_dump((fn() => $c)());
    global $g;
    static $st;
    var_dump($g, $st, @$silenced, gettype($gt), $u instanceof stdClass, (array) $u2, is_null($u3));
    unset($un);
    var_dump($un);
}
more();
global $topGlobal;
var_dump($topGlobal);
var_dump($GLOBALS['topNope']);
$topFn = fn() => $topArrow;
var_dump($topFn());

// order: the warning comes before the operation's other effects
function order() {
    echo "before " . $x . (new Str()) . "\n";
    $a = [1, 2];
    echo $a[0] . $u . $a[1], "\n";
}
order();

// a handler that throws: the operation stops at the warning
set_error_handler(function ($type, $message, $file, $line) {
    throw new RuntimeException("$message (line $line)");
});
function throws() {
    try {
        echo "a" . $x . "b\n";
        echo "not reached\n";
    } catch (RuntimeException $e) {
        echo "caught: ", $e->getMessage(), "\n";
    }
    try {
        $y .= 'x';
        echo "not reached\n";
    } catch (RuntimeException $e) {
        echo "caught: ", $e->getMessage(), "\n";
        var_dump(isset($y));
    }
    try {
        $z++;
        echo "not reached\n";
    } catch (RuntimeException $e) {
        echo "caught: ", $e->getMessage(), "\n";
        var_dump(isset($z));
    }
}
throws();
try {
    echo $topThrows;
} catch (RuntimeException $e) {
    echo "caught at top: ", $e->getMessage(), "\n";
}
restore_error_handler();
restore_error_handler();
echo "done\n";
