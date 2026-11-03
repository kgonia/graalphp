<?php
// A file's top level on bytecode: globals by name, global and static at the top level, closures capturing
// globals, catch into a global, list() and foreach into globals, self and $this in a file included from a method,
// defined() and const, early-bound classes

namespace First {
    const ONE = 1;
    function f() { return __FUNCTION__; }
    echo f(), ' ', ONE, ' ', \defined('First\ONE') ? 'yes' : 'no', "\n";
}

namespace {
    use First as F;
    echo F\f(), "\n";

    $x = 1;
    $y = &$x;
    $y++;
    var_dump($x, $GLOBALS['x']);

    function readGlobal() {
        global $x;
        return $x;
    }
    var_dump(readGlobal());

    global $z;
    var_dump($z);
    $z = 'set';
    var_dump($GLOBALS['z']);

    static $counter = 5;
    $counter++;
    var_dump($counter);

    $captured = 'by value';
    $byRef = 'before';
    $closure = function () use ($captured, &$byRef) {
        $byRef = 'after';
        return $captured;
    };
    var_dump($closure(), $byRef);
    $arrow = fn() => [$captured, isset($notDefinedYet)];
    var_dump($arrow());

    try {
        throw new Exception('caught');
    } catch (Exception $caught) {
    }
    var_dump($caught->getMessage());

    [$a, [$b, $c]] = [1, [2, 3]];
    var_dump($a + $b + $c);
    foreach (['k' => 'v'] as $key => $value) {
    }
    var_dump($key, $value);
    foreach ([[1, 2]] as [$p, $q]) {
    }
    var_dump($p, $q);
    $list = [1, 2, 3];
    foreach ($list as &$item) {
        $item *= 10;
    }
    unset($item);
    var_dump($list);

    var_dump(defined('ALPHA'));
    define('ALPHA', 1);
    var_dump(defined('ALPHA'), defined('true'), defined('PHP_EOL'));
    const BETA = ALPHA + 1, GAMMA = [BETA];
    var_dump(BETA, GAMMA);

    var_dump(compact('a', 'b'));
    extract(['extracted' => 'yes']);
    var_dump($extracted);
    $name = 'dynamic';
    $$name = 'dynamic value';
    var_dump($dynamic, ${'na' . 'me'});
    unset($dynamic);
    var_dump(isset($dynamic));

    var_dump(EarlyChild::class, (new EarlyChild)->hello());
    class EarlyChild extends EarlyParent {
    }
    class EarlyParent {
        function hello() {
            return 'hello';
        }
    }

    class Includer {
        private $own = 'private';
        const WHO = 'includer';

        function load() {
            $local = 'local';
            return include __DIR__ . '/top-level-bytecode.inc';
        }
    }
    var_dump((new Includer)->load());

    $n = 0;
    loop:
    $n++;
    if ($n < 3) {
        goto loop;
    }
    var_dump($n);

    $sum = 0;
    for ($i = 0; $i < 5; $i++) {
        $sum += $i;
    }
    var_dump($sum, $i);
    echo $undefinedAtTopLevel ?? 'quiet', "\n";
    var_dump(array_key_exists('undefinedAtTopLevel', $GLOBALS));
}
