<?php
// The Generator API as php-src has it: keys, send/throw/getReturn/current/key/next/valid/rewind, their errors,
// exceptions thrown into and out of a generator, yield from (arrays, Traversables, generators, chains, keys,
// several delegating to one), by-reference generators, generators in methods and closures, finally blocks.

function show($label, callable $f)
{
    try {
        $result = $f();
        echo $label, ": ", json_encode($result), "\n";
    } catch (Throwable $e) {
        echo $label, ": ", get_class($e), ": ", $e->getMessage(), "\n";
    }
}

// auto keys: after the largest integer key, string keys don't count, yield from doesn't move them
function keys() {
    yield 'a';
    yield 5 => 'b';
    yield 'c';
    yield 'k' => 'd';
    yield 2 => 'e';
    yield 'f';
    yield from [100 => 'g', 'h'];
    yield 'i';
    yield -10 => 'j';
    yield 'l';
}
foreach (keys() as $k => $v) {
    echo json_encode($k), "=>$v ";
}
echo "\n";

// the API before, during and after the run
function api() {
    echo "started\n";
    $x = yield 1;
    echo "got ", json_encode($x), "\n";
    $y = yield 2;
    echo "got ", json_encode($y), "\n";
    return 'ret';
}
$g = api();
show('getReturn before start', fn() => $g->getReturn());
show('key', fn() => $g->key());
show('rewind at first yield', fn() => $g->rewind());
show('current', fn() => $g->current());
show('next', fn() => $g->next());
show('current', fn() => $g->current());
show('rewind after first yield', fn() => $g->rewind());
show('send', fn() => $g->send('s'));
show('valid', fn() => $g->valid());
show('getReturn', fn() => $g->getReturn());
show('send after end', fn() => $g->send('late'));
show('current after end', fn() => $g->current());
show('key after end', fn() => $g->key());
show('foreach after end', function () use ($g) { foreach ($g as $v) {} });
show('throw after end', fn() => $g->throw(new LogicException('late')));

// send as the first call runs to the first yield, then sends
$g = api();
show('send first', fn() => $g->send('first'));

// exceptions out of a generator end it; thrown in, they are caught or end it
function thrower() {
    yield 1;
    throw new RuntimeException('out');
}
$g = thrower();
show('current', fn() => $g->current());
show('next', fn() => $g->next());
show('valid after throw', fn() => $g->valid());

function catcher() {
    while (true) {
        try {
            $v = yield 'waiting';
            echo "received ", json_encode($v), "\n";
        } catch (InvalidArgumentException $e) {
            echo "caught ", $e->getMessage(), "\n";
            yield 'after catch';
        } finally {
            echo "finally of the loop\n";
        }
    }
}
$g = catcher();
show('current', fn() => $g->current());
show('throw caught', fn() => $g->throw(new InvalidArgumentException('in')));
show('send', fn() => $g->send('v'));
show('throw uncaught', fn() => $g->throw(new DomainException('through')));
show('valid', fn() => $g->valid());

// throw() before the first run raises at the start, outside every try
function neverStarted() {
    try {
        yield 1;
    } catch (Exception $e) {
        echo "not here\n";
    }
}
show('throw unstarted', fn() => neverStarted()->throw(new Exception('early')));

// a running generator can't be resumed
function selfResume() {
    global $self;
    yield 1;
    $self->next();
    yield 2;
}
$self = selfResume();
show('current', fn() => $self->current());
show('resume itself', fn() => $self->next());
show('valid', fn() => $self->valid());

// yield from: arrays, Iterators, IteratorAggregates, generators; their keys; the return value
class Numbers implements Iterator
{
    private $i = 0;
    public function __construct(private array $items) {}
    public function current(): mixed { echo "[current]"; return $this->items[$this->i]; }
    public function key(): mixed { echo "[key]"; return "n{$this->i}"; }
    public function next(): void { echo "[next]"; $this->i++; }
    public function rewind(): void { echo "[rewind]"; $this->i = 0; }
    public function valid(): bool { echo "[valid]"; return $this->i < count($this->items); }
}
class Bag implements IteratorAggregate
{
    public function getIterator(): Iterator { return new ArrayIterator(['p' => 'P', 'q' => 'Q']); }
}
function inner($label) {
    $sent = yield "$label 1";
    echo "inner $label got ", json_encode($sent), "\n";
    yield 'ik' => "$label 2";
    return "$label returned";
}
function outer() {
    $r = yield from ['x' => 1, 'y' => 2];
    echo "array gives ", json_encode($r), "\n";
    yield from new Numbers([10, 20]);
    echo "\n";
    yield from new Bag;
    $r = yield from inner('first');
    echo "generator gives ", json_encode($r), "\n";
    $r = yield from (function () { return yield from inner('nested'); })();
    echo "nested gives ", json_encode($r), "\n";
    yield from [];
    return 'outer returned';
}
$g = outer();
foreach ($g as $k => $v) {
    echo json_encode($k), " => ", json_encode($v), "\n";
    if ($v === 'first 1') {
        echo "send: ", json_encode($g->send('to inner')), "\n";
    }
}
show('outer getReturn', fn() => $g->getReturn());

// a chain of delegations; an exception thrown into the outer one reaches the innermost
function level($n) {
    try {
        if ($n === 0) {
            $v = yield 'leaf';
            echo "leaf got $v\n";
            yield 'leaf again';
            return 'leaf done';
        }
        $r = yield from level($n - 1);
        echo "level $n: $r\n";
        return "level $n done";
    } catch (Exception $e) {
        echo "level $n caught ", $e->getMessage(), "\n";
        return "level $n recovered";
    }
}
$g = level(3);
show('current', fn() => $g->current());
show('send', fn() => $g->send('hi'));
show('throw', fn() => $g->throw(new Exception('deep')));
show('valid', fn() => $g->valid());
show('getReturn', fn() => $g->getReturn());

// yield from a generator that already returned gives its value; one that failed can't be delegated to
function done() { return 'early value'; yield; }
function failed() { throw new Exception('failed'); yield; }
$finished = done();
$finished->current();
show('from returned', function () use ($finished) {
    $g = (function () use ($finished) { $r = yield from $finished; echo "got $r\n"; yield 'after'; })();
    return $g->current();
});
$broken = failed();
show('broken', fn() => $broken->current());
show('from failed', function () use ($broken) {
    $g = (function () use ($broken) { yield from $broken; })();
    return $g->current();
});
show('from a scalar', fn() => (function () { yield from 42; })()->current());

// two generators delegating to one: each sees it where the other left it
function shared() {
    for ($i = 0; $i < 4; $i++) {
        yield "shared $i";
    }
    return 'shared done';
}
$s = shared();
$a = (function () use ($s) { $r = yield from $s; echo "a: $r\n"; })();
$b = (function () use ($s) { $r = yield from $s; echo "b: $r\n"; })();
foreach ([$a, $b, $a, $b, $a, $b] as $i => $gen) {
    echo "$i: ", json_encode($gen->current()), "\n";
    $gen->next();
}
show('a valid', fn() => $a->valid());
show('b valid', fn() => $b->valid());

// by-reference generators
function &refs(array &$data) {
    foreach ($data as $k => &$v) {
        yield $k => $v;
    }
}
$data = [1, 2, 3];
foreach (refs($data) as &$value) {
    $value *= 10;
}
unset($value);
var_dump($data);
function &notAVariable() {
    yield 42;
}
// the notice without the file, whose path differs between the runs
set_error_handler(function ($type, $message, $file, $line) {
    echo "[$type] $message (line $line)\n";
    return true;
});
foreach (notAVariable() as $v) {
    var_dump($v);
}
restore_error_handler();
show('by ref over a by-value generator', function () {
    foreach (api() as &$v) {
    }
});

// generators in methods and closures: $this, static, self
class Counter
{
    protected $label = 'counter';
    public static function create() { return new static; }
    public function count($n) {
        for ($i = 1; $i <= $n; $i++) {
            yield "{$this->label} $i";
        }
        return static::class;
    }
    public static function names() {
        yield self::class;
        yield static::class;
    }
    public function closure() {
        return function ($n) { yield "{$this->label} from a closure $n"; };
    }
}
class SubCounter extends Counter
{
    protected $label = 'sub';
}
$c = SubCounter::create();
$g = $c->count(2);
foreach ($g as $v) {
    echo $v, "\n";
}
echo $g->getReturn(), "\n";
foreach (SubCounter::names() as $v) {
    echo $v, "\n";
}
foreach (($c->closure())(7) as $v) {
    echo $v, "\n";
}
$static = static function () { yield isset($this) ? 'bound' : 'static closure'; };
foreach ($static() as $v) {
    echo $v, "\n";
}

// return in finally replaces the value; finally of a generator left by an exception
function returnInFinally() {
    try {
        yield 1;
        return 'from try';
    } finally {
        return 'from finally';
    }
}
$g = returnInFinally();
foreach ($g as $v) {
}
show('return in finally', fn() => $g->getReturn());

// a generator destroyed while suspended in a try runs its finally blocks; here at the end of the script, as
// php-src's shutdown destroys the objects still alive
function suspendedInFinally() {
    try {
        try {
            echo "suspended\n";
            yield 1;
            echo "not reached\n";
        } finally {
            echo "inner finally on destruction\n";
        }
    } finally {
        echo "outer finally on destruction\n";
    }
}
$kept = suspendedInFinally();
$kept->current();
echo "end of script\n";
