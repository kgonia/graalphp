<?php
// include and require (_once) inside functions on bytecode: the included file's top-level code runs in the call's
// variables (php-src's symbol table): it reads and writes the function's variables (ints and floats the function
// keeps unboxed, references, arrays shared copy-on-write), creates variables the function never names (kept for
// later includes, compact() and extract() of the same call, not for the next call), sees the call's $this, static
// class and calling scope; plus return values, _once, generators, closures, recursion, and the lines of errors,
// warnings and traces from inside included files.

function base($path) {
    return preg_replace('~.*[/\\\\]~', '', $path);
}
set_error_handler(function ($type, $message, $file, $line) {
    echo "[$type] $message (", base($file), ":$line)\n";
    return true;
});

function kinds() {
    $int = 1;
    $float = 1.5;
    $text = 'a';
    for ($i = 0; $i < 3; $i++) {
        $int += $i;
        $float *= 2;
    }
    // the include makes the int a string and the float an int, the text a float
    $result = include __DIR__ . '/bytecode-include-scope-kinds.inc';
    var_dump($result, $int, $float, $text, $int . '!', $float + 1, $text * 2);
    // and the function goes on with its own arithmetic
    for ($i = 0; $i < 2; $i++) {
        $float += $i;
    }
    var_dump($float, $madeThere);
}
kinds();
kinds();

function references() {
    $target = 1;
    $alias = &$target;
    $list = [1, 2];
    $copy = $list;
    include __DIR__ . '/bytecode-include-scope-refs.inc';
    // the include wrote through $alias, bound $bound to $list's element, and changed its own copy of $list
    var_dump($target, $alias, $list, $copy, $bound);
    $bound = 'via bound';
    var_dump($list);
    unset($alias);
    var_dump(isset($target), isset($gone));
}
references();

function only_included() {
    // $hidden, $counter: never named here; the later includes and compact() find them
    include __DIR__ . '/bytecode-include-scope-make.inc';
    include __DIR__ . '/bytecode-include-scope-make.inc';
    var_dump(compact('hidden', 'counter'));
    extract(['counter' => 100], EXTR_SKIP);
    include __DIR__ . '/bytecode-include-scope-make.inc';
    return compact(['hidden', ['counter']]);
}
var_dump(only_included());
// a new call starts without them
var_dump(only_included());

function once($file) {
    $before = 'before';
    $first = include_once $file;
    $second = include_once $file;
    $third = require_once $file;
    var_dump($first, $second, $third, $onceVar ?? 'not set again');
}
once(__DIR__ . '/bytecode-include-scope-once.inc');
once(__DIR__ . '/bytecode-include-scope-once.inc');

class Renderer {
    private $secret = 'private';
    protected $shared = 'protected';
    public $name = 'renderer';

    public function render($title) {
        $items = ['x', 'y'];
        $out = require __DIR__ . '/bytecode-include-scope-this.inc';
        return [$out, $items, $title];
    }

    public static function make() {
        return new static();
    }

    public static function staticRender() {
        $title = 'static';
        return include __DIR__ . '/bytecode-include-scope-static.inc';
    }
}
class FancyRenderer extends Renderer {
}
var_dump(FancyRenderer::make()->render('Title'));
var_dump(FancyRenderer::staticRender());

// a closure bound to another object and scope: the included code has the closure's
$closure = function ($title) {
    $items = [];
    return require __DIR__ . '/bytecode-include-scope-this.inc';
};
var_dump(Closure::bind($closure, new Renderer(), Renderer::class)('bound'));

function generate() {
    $step = 1;
    yield $step;
    include __DIR__ . '/bytecode-include-scope-gen.inc';
    yield $step;
    yield $fromGenerator;
}
foreach (generate() as $value) {
    var_dump($value);
}

function recurse($depth) {
    $mine = "depth $depth";
    if ($depth < 2) {
        recurse($depth + 1);
    }
    include __DIR__ . '/bytecode-include-scope-recurse.inc';
    var_dump($mine, $seenDepth);
}
recurse(0);

function warns() {
    $value = 'x';
    // a warning and an exception raised in the included file are placed there; the include's line in traces
    include __DIR__ . '/bytecode-include-scope-warn.inc';
    try {
        include __DIR__ . '/bytecode-include-scope-throw.inc';
    } catch (Exception $e) {
        echo get_class($e), ': ', $e->getMessage(), ' at ', base($e->getFile()), ':', $e->getLine(), "\n";
        foreach ($e->getTrace() as $frame) {
            echo '  ', base($frame['file'] ?? '-'), ':', $frame['line'] ?? '-', ' ', $frame['function'], "\n";
        }
    }
    var_dump($afterThrow ?? 'not reached');
}
warns();

function traced() {
    $local = 'traced';
    return include __DIR__ . '/bytecode-include-scope-trace.inc';
}
traced();

function in_loop() {
    $total = 0;
    foreach ([1, 2, 3] as $n) {
        include __DIR__ . '/bytecode-include-scope-loop.inc';
    }
    return [$total, $n, $lastSeen];
}
var_dump(in_loop());

function by_path($name) {
    $file = __DIR__ . "/bytecode-include-scope-$name.inc";
    $value = 5;
    return include $file;
}
var_dump(by_path('value'));

function missing_once() {
    $x = 1;
    // a file included already by the script: include_once gives true without running it
    return [include_once __DIR__ . '/bytecode-include-scope-once.inc', $x];
}
var_dump(missing_once());
