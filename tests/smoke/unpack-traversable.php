<?php
// Argument and array unpacking of a Traversable (ZEND_SEND_UNPACK, ZEND_ADD_ARRAY_UNPACK): its iterator walked
// (rewind, then valid, current, key, next), int keys positional; a by-reference parameter takes a copy, with
// php-src's warning; a key of another type is an Error.
ob_start(function ($text) {
    // "/" after the directory: Truffle PHP runs the smoke tests on the host
    return str_replace([__DIR__ . "\\", __DIR__ . "/"], "DIR/", $text);
});
class Logged implements Iterator {
    private $i = 0;
    function __construct(private array $items) {}
    function rewind(): void { echo "rewind\n"; $this->i = 0; }
    function valid(): bool { echo "valid\n"; return $this->i < count($this->items); }
    function current(): mixed { echo "current\n"; return array_values($this->items)[$this->i]; }
    function key(): mixed { echo "key\n"; return array_keys($this->items)[$this->i]; }
    function next(): void { echo "next\n"; $this->i++; }
}
class Agg implements IteratorAggregate {
    function getIterator(): Iterator { return new ArrayIterator([7, 8]); }
}
function show(...$args) { echo json_encode($args), "\n"; }
function two($a, $b) { echo "$a $b\n"; }
function byRef(&$r, $v) { $r .= '!'; echo "$r $v\n"; }
function gen() { yield 1; yield 2; yield 3; }

show(...new ArrayIterator([1, 2]));
show(0, ...new Agg(), ...[9]);
show(...gen());
show(...new Logged([10, 20]));
two(...new Logged(['5' => 'x', 6 => 'y']));
byRef(...new ArrayIterator(['a', 'b']));
var_dump([...new ArrayIterator(['p' => 1, 2]), ...gen(), ...new ArrayIterator(['3' => 'n'])]);
function genKeys() { yield 1.5 => 'f'; }
try {
    show(...genKeys());
} catch (Error $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
try {
    var_dump([...genKeys()]);
} catch (Error $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
try {
    show(...new stdClass());
} catch (Error $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
try {
    $n = 42;
    var_dump([...$n]);
} catch (Error $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
function inFunction() {
    $it = new ArrayIterator([3, 4]);
    show(...$it);
    return [...$it, ...$it];
}
echo json_encode(inFunction()), "\n";
