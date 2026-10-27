<?php
// Run by tests/shared/run.sh in many contexts at once on one engine: every context writes to arrays that start
// as language-level data (a class's default property, a class constant, a constant array literal) and checks
// that it only ever sees its own writes. A global bound with `global` must be this context's own, too.
class Box
{
    const SEED = [1, 2, [3]];
    public $items = [10, 20];
    public static $log = ['start'];
}

$bad = 0;
for ($i = 0; $i < 20000; $i++) {
    $box = new Box();
    $box->items[] = $i;
    if (count($box->items) !== 3 || $box->items[0] !== 10) {
        $bad++;
    }
    $seed = Box::SEED;
    $seed[2][] = $i;
    $seed[] = $i;
    if (count(Box::SEED) !== 3 || count(Box::SEED[2]) !== 1 || count($seed[2]) !== 2) {
        $bad++;
    }
    $literal = ['a' => 1];
    $literal['b'] = $i;
    if (count(['a' => 1]) !== 1) {
        $bad++;
    }
    Box::$log[] = $i;
}
if (count(Box::$log) !== 20001) {
    $bad++;
}

function init_once()
{
    global $shared;
    if (isset($shared)) {
        return;
    }
    $shared = new stdClass();
}

init_once();
if (!isset($shared)) {
    $bad++;
}

// Regular expressions: the compiled patterns are shared by the engine's threads, each context matches on its own
// JIT stack. A backtracking pattern on a long subject uses the stack, so threads sharing one would corrupt it.
$subject = str_repeat('ab', 2000) . 'c' . uniqid();
for ($i = 0; $i < 300; $i++) {
    $word = 'w' . ($i % 7);
    if (preg_match('/^((?:a|ab)+?)(b*)c/', $subject, $m) !== 1 || strlen($m[1]) !== 3999 || $m[2] !== 'b') {
        $bad++;
    }
    if (preg_replace('/(\w)(\d)/', '$2$1', "$word-x9") !== strrev(substr($word, 0, 2)) . '-9x') {
        $bad++;
    }
    if (preg_match("/$word(\\d*)/", "a{$word}42", $m) !== 1 || $m[1] !== '42') {
        $bad++;
    }
}
// Classes declared differently in different contexts (as a conditional declaration is), so the contexts sharing
// the AST disagree about them: each context picks its own way, and the shared call and property sites must answer
// each context as it declared them (wiki/tla-engine.md).
$linked = random_int(0, 1) === 1;
class Root
{
    protected $p = 'root';
}
if ($linked) {
    class Middle extends Root
    {
    }
} else {
    class Middle
    {
    }
}
class Reader extends Middle
{
    private $own = 'own';

    // a protected property of Root: accessible from here only where Middle extends Root
    public function read($object)
    {
        try {
            return $object->p;
        } catch (Error $e) {
            return 'denied';
        }
    }

    public function own()
    {
        return $this->own;
    }
}
class Leaf extends Root
{
}
class Magic
{
    public function __call($name, $args)
    {
        return '__call';
    }

    public static function __callStatic($name, $args)
    {
        return '__callStatic';
    }
}
$unbound = function () {
    return call_user_func(['Magic', 'missing']);
};
$bound = Closure::bind($unbound, new Magic(), Magic::class);
$reader = new Reader();
for ($i = 0; $i < 300; $i++) {
    if ($reader->read(new Leaf()) !== ($linked ? 'root' : 'denied') || $reader->own() !== 'own') {
        $bad++;
    }
    // ['Magic', 'missing']: __call from code running on a Magic, __callStatic elsewhere, in either order
    $first = $i % 2 === 0;
    if (($first ? $unbound() : $bound()) !== ($first ? '__callStatic' : '__call')
        || ($first ? $bound() : $unbound()) !== ($first ? '__call' : '__callStatic')) {
        $bad++;
    }
}

echo $bad === 0 ? "isolated\n" : "corrupted $bad\n";
