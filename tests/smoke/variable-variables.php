<?php
// Variable variables ($$name, ${expr}) by php-src's rules (ZEND_FETCH_* with a name that isn't a constant): the name
// converted to a string (an array with a warning, an object by __toString), looked up in the call's symbol table
// (the function's variables and those extract(), an include or $$name made; the globals at a file's top level),
// read with a warning when undefined, written, read and written, by reference, isset/empty/unset, in php-src's
// order of evaluation; "this" by name is $this when read, an Error when written; a superglobal's name in a
// function is a local's.

set_error_handler(function ($type, $message, $file, $line) {
    echo "[$type] $message (line $line)\n";
    return true;
});

function basics() {
    $name = 'x';
    $$name = 1;
    var_dump($x);
    $x = 2;
    var_dump($$name);
    $$name .= 'a';
    $$name++;
    var_dump($x, ${'x'});
    $other = 'notInBody';
    $$other = [1];
    $$other[] = 2;
    $$other['k'] = 'v';
    $$other['k'] .= 'w';
    $$other[0]++;
    var_dump($$other, compact('notInBody'), $$other['k'], $$other[1]);
    var_dump(isset($$other), isset($$other['k']), isset($$other['z']), empty($$other), empty($$other['k']));
    unset($$other['k']);
    var_dump($$other);
    unset($$other);
    var_dump(isset($$other), empty($$other), $$other ?? 'gone', compact('notInBody'));
    var_dump($$other);
    $n = 'counter';
    $$n += 5;
    var_dump($counter);
    $n = 'stepped';
    $$n++;
    var_dump($stepped);
    $n = 'coalesced';
    $$n ??= 'default';
    $$n ??= 'not again';
    var_dump($coalesced);
    $deep = 'n';
    var_dump($$$deep);
    $n = 'appended';
    $$n[] = 'first';
    $$n['a']['b'] = 'nested';
    var_dump($appended);
    $n = 'nullValue';
    $$n = null;
    var_dump(isset($$n), compact('nullValue'));
}
basics();

function bump(&$v) {
    $v++;
}

function refs() {
    $n = 'a';
    $a = 1;
    $r = &$$n;
    $r = 2;
    var_dump($a);
    $b = 10;
    $m = 'c';
    $$m = &$b;
    $c = 11;
    var_dump($b);
    $k = 'byref';
    bump($$k);
    bump($$k);
    var_dump($byref);
    $k = 'elementByRef';
    bump($$k['e']);
    var_dump($elementByRef);
    $first = 'p';
    $second = 'q';
    [$$first, $$second] = [1, 2];
    var_dump($p, $q);
    ['x' => $$first] = ['x' => 3];
    var_dump($p);
    [$$first, [$$second]] = [4, [5]];
    var_dump($p, $q);
    foreach ([8, 9] as $$first) {
    }
    var_dump($p);
    foreach (['k1' => 10] as $$second => $$first) {
    }
    var_dump($q, $p);
    $arr = [1, 2];
    $an = 'arr';
    foreach ($$an as &$v) {
        $v *= 10;
    }
    unset($v);
    var_dump($arr);
    $list = [1, 2];
    $target = 'element';
    foreach ($list as &$$target) {
        $$target += 100;
    }
    unset($$target);
    var_dump($list, isset($element));
    $made = 'madeByRef';
    $x = &$$made;
    var_dump(compact('madeByRef'));
}
refs();

$gv = 'global value';
function globals() {
    $n = 'gv';
    global $$n;
    var_dump($gv);
    $gv = 'changed';
    $m = 'newGlobal';
    global $$m;
    $$m = 'made global';
}
globals();
var_dump($gv, $newGlobal);

class Named {
    public function __toString(): string {
        return 'obj';
    }
}

function names() {
    $i = 1;
    $$i = 'int';
    var_dump(${'1'}, ${1});
    $null = null;
    $$null = 'empty name';
    var_dump(${''});
    $f = 1.5;
    $$f = 'float';
    var_dump(${'1.5'});
    $t = true;
    $$t .= ' true';
    var_dump(${'1'});
    $arr = [1];
    $$arr = 'array';
    var_dump(${'Array'}, isset($$arr));
    $o = new Named();
    $$o = 'object';
    var_dump($obj);
    try {
        $p = new stdClass();
        $$p = 1;
    } catch (Error $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
    try {
        var_dump(isset($$p));
    } catch (Error $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
    $space = 'with space';
    $$space = 'any string';
    var_dump(compact('with space'));
}
names();

class Loud {
    public function __toString(): string {
        echo "toString\n";
        return 'e';
    }
}

function order() {
    $n = 'a';
    $$n = ($n = 'b');
    var_dump(isset($a), $b);
    $name = function ($v) {
        echo "name $v\n";
        return $v;
    };
    $value = function ($v) {
        echo "value $v\n";
        return $v;
    };
    ${$name('c')} = $value(1);
    ${$name('c')} .= $value(2);
    ${$name('d')}[$name('k')] = $value(3);
    $o = new Loud();
    $$o = $value(4);
    $$o .= $value(5);
    var_dump($c, $d, $e);
    $object = 'holder';
    $holder = new stdClass();
    ${$name('holder')}->p = $value(6);
    $$object->{$name('q')} = $value(7);
    var_dump(get_object_vars($holder));
}
order();

class Box {
    public $p = 'prop';

    public function test() {
        $n = 'this';
        var_dump($$n === $this, $$n->p, isset($$n), empty($$n), ($$n ?? 'none') === $this, isset($$n->p));
        try {
            $$n = 1;
        } catch (Error $e) {
            echo $e->getMessage(), "\n";
        }
        try {
            $$n .= 1;
        } catch (Error $e) {
            echo $e->getMessage(), "\n";
        }
        try {
            $r = &$$n;
        } catch (Error $e) {
            echo $e->getMessage(), "\n";
        }
        unset($$n);
        var_dump($this->p);
        try {
            unset($$n['k']);
        } catch (Error $e) {
            echo $e->getMessage(), "\n";
        }
        $m = 'self';
        $$m = $this;
        $$m->p = 'changed';
        var_dump($this->p, $$n->p);
        $q = 'local';
        $$q = 'method local';
        var_dump($local, $$q);
        $method = 'name';
        return $$n->$method();
    }

    public function name() {
        return 'Box::name';
    }

    public static function noThis() {
        $n = 'this';
        var_dump($$n, isset($$n), $$n ?? 'none');
    }
}
var_dump((new Box())->test());
Box::noThis();

function supers() {
    $n = '_GET';
    var_dump(isset($$n));
    $$n = 'local _GET';
    var_dump($$n, is_array($_GET));
    $g = 'GLOBALS';
    var_dump(isset($$g));
    $$g = 'local GLOBALS';
    var_dump($$g, is_array($GLOBALS));
    $gv = 'gv';
    var_dump(isset($$gv));
}
supers();

function closures() {
    $n = 'made';
    $$n = 'by name';
    $f = function () use ($made) {
        return $made;
    };
    var_dump($f());
    $g = function ($name) {
        $$name = 'inside';
        return compact($name);
    };
    var_dump($g('zz'));
    $h = fn($name) => $$name;
    var_dump($h('name'));
    $captured = 'c0';
    $i = function ($name) use (&$captured) {
        $$name .= ' appended';
    };
    $i('captured');
    var_dump($captured);
    $callee = 'strtoupper';
    $fn = 'callee';
    var_dump($$fn('called'));
}
closures();

function gen($names) {
    foreach ($names as $i => $n) {
        $$n = $i;
        yield $n => $$n;
    }
    yield 'all' => compact($names);
}
foreach (gen(['g1', 'g2']) as $k => $v) {
    var_dump($k, $v);
}

function withScope() {
    extract(['ex' => 'extracted']);
    $n = 'ex';
    var_dump($$n);
    $m = 'made';
    $$m = 'by name';
    include __DIR__ . '/variable-variables.inc';
    var_dump($fromInclude, $$k);
}
withScope();

function loop() {
    $sum = 0;
    for ($i = 0; $i < 3; $i++) {
        $n = 'v' . $i;
        $$n = $i * 10;
        $sum += $$n;
    }
    return [$sum, $v0, $v1, $v2];
}
var_dump(loop(), loop());

function objects() {
    $n = 'missing';
    try {
        $$n->p = 1;
    } catch (Error $e) {
        echo $e->getMessage(), "
";
    }
    var_dump(compact('missing'));
    $n = 'missingToo';
    try {
        $$n->p .= 'x';
    } catch (Error $e) {
        echo $e->getMessage(), "
";
    }
    var_dump(compact('missingToo'));
    $n = 'notMade';
    unset($$n->p);
    var_dump(compact('notMade'));
    $n = 'object';
    $$n = new stdClass();
    $$n->list[] = 1;
    $$n->count = 1;
    $$n->count++;
    unset($$n->list);
    var_dump(get_object_vars($object), isset($$n->count), $$n->count ?? 'none');
}
objects();

class ThisByName {
    public function write() {
        $n = 'this';
        try {
            $$n->p = 1;
        } catch (Error $e) {
            echo $e->getMessage(), "
";
        }
    }
}
(new ThisByName())->write();

class IncludesThis {
    public function run() {
        include __DIR__ . '/variable-variables-this.inc';
    }
}
(new IncludesThis())->run();

function builtins() {
    $n = 'stack';
    $$n = [1];
    array_push($$n, 2);
    $m = 'matches';
    preg_match('/(b)/', 'abc', $$m);
    var_dump($stack, $matches);
}
builtins();

function recursive($depth) {
    $n = 'level';
    $$n = $depth;
    if ($depth < 2) {
        recursive($depth + 1);
    }
    var_dump($level);
}
recursive(0);

// a file's top level: the globals by name
$n = 'top';
$$n = 1;
var_dump($top, $GLOBALS['top']);
$$n .= 'x';
$$n++;
var_dump($top);
function readsTop() {
    global $top;
    return $top;
}
var_dump(readsTop());
$s = '_GET';
var_dump(is_array($$s));
$g = 'GLOBALS';
var_dump(isset($$g));
$t = 'this';
var_dump(isset($$t), $$t);
$r = &$$n;
$r = 'by reference';
var_dump($top);
$m = 'boundTop';
$$m = &$top;
$boundTop = 'bound';
var_dump($top);
$a = 'topArray';
$$a[] = 1;
$$a['k'] = 2;
var_dump($topArray, isset($$a['k']), empty($$a['z']));
unset($$a['k']);
var_dump($topArray);
unset($$n);
var_dump(isset($top), isset($$n), $$n ?? 'unset');
var_dump($$n);
[$$n, $$m] = ['l1', 'l2'];
var_dump($top, $boundTop);
foreach ([1, 2] as $$n) {
}
var_dump($top);
$k = 'topByRef';
bump($$k);
var_dump($topByRef);
${$name = 'computed'} = 'value';
var_dump($computed);
$na = [1];
$$na = 'array name';
var_dump(${'Array'});
$u = 'topMissing';
try {
    $$u->p = 1;
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
var_dump(array_key_exists('topMissing', $GLOBALS));
$u = 'topObject';
$$u = new stdClass();
$$u->p = 'top property';
$$u->p .= ' appended';
var_dump($topObject->p, isset($$u->p), empty($$u));
$u = 'topNotMade';
unset($$u->p);
var_dump(array_key_exists('topNotMade', $GLOBALS));
