<?php
// "Undefined array key" (zend_undefined_index / zend_undefined_offset): a read of a missing key warns and reads
// null, also a read-modify-write ($a['k'] .= , $a['k']++, $a['k'] += ), which then creates the element, nested
// reads and list() / [...] = destructuring; isset, empty, ??, writes, references and unset don't. Reads of an
// offset on a string ("Uninitialized string offset", "Illegal string offset", "String offset cast occurred",
// "Cannot access offset of type %s on string") and on scalars ("Trying to access array offset on null").

set_error_handler(function ($type, $message, $file, $line) {
    echo "[$type] $message (line $line)\n";
    return true;
});

class Box implements ArrayAccess {
    function offsetExists($k): bool { return false; }
    function offsetGet($k): mixed { return null; }
    function offsetSet($k, $v): void {}
    function offsetUnset($k): void {}
}

function reads() {
    $a = ['k' => 1, 3 => 'three', 'n' => ['j' => 2]];
    var_dump($a['missing'], $a[7], $a['n']['nope'], $a['x']['y'], $a[-1], $a['3'], $a['07']);
    var_dump($a[true], $a[false]);
    echo "echo: ", $a['e'], "|\n";
    echo "interpolation: $a[i]|{$a['j']}|\n";
    $k = 'dynamic';
    var_dump($a[$k]);
    $list = [10, 20];
    var_dump($list[2]);
    foreach ($a['none'] ?? [] as $v) {
    }
    foreach ($a['none2'] as $v) {
    }
    var_dump(count($a['n']) + strlen((string) $a['z']));
    $packed = [1, 2, 3];
    for ($i = 0; $i <= 3; $i++) {
        $sum = ($sum ?? 0) + $packed[$i];
    }
    var_dump($sum);
    $nested = ['a' => ['b' => ['c' => 1]]];
    var_dump($nested['a']['b']['d'], $nested['a']['x']['c']);
}
reads();

function quiet() {
    $a = ['k' => 1];
    var_dump(isset($a['m']), empty($a['m']), $a['m'] ?? 'default', isset($a['m']['n']), $a['m']['n'] ?? 'nested');
    $a['w'] = 1;
    $a['x']['y'] = 2;
    $a['z'][] = 3;
    $r = &$a['ref'];
    unset($a['gone'], $a['gone2']['deeper']);
    $a['q'] ??= 'assigned';
    var_dump($a);
    var_dump(array_key_exists('m', $a));
    $box = new Box();
    var_dump($box['anything']);
}
quiet();

function modifies() {
    $a = [];
    $a['c'] .= 'x';
    $a['i']++;
    $a['d']--;
    ++$a['p'];
    $a['s'] += 5;
    $a[4] *= 2;
    $a['x']['y'] .= 'nested';
    var_dump($a);
    $o = new stdClass();
    $o->arr = [];
    $o->arr['k'] .= 'prop';
    var_dump($o->arr);
}
modifies();

function lists() {
    [$a, $b, $c] = [1, 2];
    var_dump($a, $b, $c);
    ['x' => $x, 'y' => $y] = ['x' => 1];
    var_dump($x, $y);
    [[$p, $q]] = [[1]];
    var_dump($p, $q);
    list(, $second) = [1];
    var_dump($second);
    [$n] = null;
    [$i] = 5;
    [$s] = 'str';
    var_dump($n, $i, $s);
    foreach ([[1], [2, 3]] as [$first, $other]) {
        var_dump($other);
    }
}
lists();

function strings() {
    $s = 'abc';
    var_dump($s[1], $s[-1], $s[3], $s[-4], $s['1'], $s[' 1'], $s['1x'], $s[1.7], $s[true], $s[null]);
    try {
        var_dump($s['x']);
    } catch (Error $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
    try {
        var_dump($s[[]]);
    } catch (Error $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
    var_dump(isset($s[5]), $s[5] ?? 'default', isset($s['x']));
    echo "interpolated: $s[9]|\n";
}
strings();

function scalars() {
    $n = null;
    $i = 5;
    $f = 1.5;
    $t = true;
    $false = false;
    var_dump($n['k'], $i[0], $f[0], $t['x'], $false[1], $n['a']['b']);
    var_dump(isset($n['k']), $i[0] ?? 'default');
    try {
        $o = new stdClass();
        var_dump($o['k']);
    } catch (Error $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}
scalars();

class Holder {
    public $props = ['a' => 1];
    static $statics = ['s' => 1];

    function method() {
        var_dump($this->props['missing'], self::$statics['missing'], static::$statics['m2']);
        $this->props['inc']++;
        return $this->props;
    }
}
var_dump((new Holder())->method());

$closure = function ($arr) {
    return $arr['missing'];
};
var_dump($closure([]));

function gen() {
    $a = [];
    yield $a['missing'];
}
foreach (gen() as $v) {
    var_dump($v);
}

// file level
$top = ['k' => 1];
var_dump($top['missing'], $top[5]);
$top['inc']++;
$top['cat'] .= 'x';
[$t1, $t2] = [1];
var_dump($t2);
echo "$top[missing2]|\n";
var_dump($GLOBALS['top']['nope']);

// a property written fetches its object quietly (BP_VAR_W); a read-modify-write of it warns (BP_VAR_RW)
function objects() {
    $n = [];
    foreach (['assign' => fn() => $n[0]->v = 1, 'compound' => fn() => $n['a']->v .= 1,
                 'increment' => fn() => $n['b']->v++, 'element' => fn() => $n['c']->v['k'] = 1,
                 'call' => fn() => $n['d']->m(), 'undefined variable' => fn() => $u->v = 1] as $label => $f) {
        try {
            $f();
        } catch (Error $e) {
            echo "$label: ", $e->getMessage(), "
";
        }
    }
    unset($n['e']->v);
    var_dump(isset($u2->p), $u3->p ?? 'default');
}
objects();

// a handler that throws: the element isn't created
set_error_handler(function ($type, $message, $file, $line) {
    throw new RuntimeException("$message (line $line)");
});
function throws() {
    $a = [];
    try {
        echo "a" . $a['k'] . "b\n";
        echo "not reached\n";
    } catch (RuntimeException $e) {
        echo "caught: ", $e->getMessage(), "\n";
    }
    try {
        $a['c'] .= 'x';
        echo "not reached\n";
    } catch (RuntimeException $e) {
        echo "caught: ", $e->getMessage(), "\n";
    }
    try {
        $a['i']++;
        echo "not reached\n";
    } catch (RuntimeException $e) {
        echo "caught: ", $e->getMessage(), "\n";
    }
    try {
        [$x, $y] = [1];
        echo "not reached\n";
    } catch (RuntimeException $e) {
        echo "caught: ", $e->getMessage(), "\n";
        var_dump($x ?? 'unset x');
    }
    var_dump($a);
    $s = 'abc';
    try {
        echo $s[10];
    } catch (RuntimeException $e) {
        echo "caught: ", $e->getMessage(), "\n";
    }
}
throws();
restore_error_handler();
restore_error_handler();
echo "done\n";
