<?php
// zend_generator_resume links the generators of a `yield from` chain into the stack, and a generator's own frame
// while its Iterator gives the values: traces show each at its yield from (php-src's gh8289 and its neighbours).
function show($e) {
    echo strtr($e instanceof Throwable ? (string) $e : $e, [__DIR__ . '/' => '', __DIR__ . '\\' => '']), "\n";
}

// gh8289: an exception from the Iterator's key() is thrown at the yield from
function yieldFromIteratorGeneratorThrows() {
    try {
        yield from new class(new ArrayIterator([1, -2])) extends IteratorIterator {
            public function key(): mixed {
                if ($k = parent::key()) {
                    throw new Exception;
                }
                return $k;
            }
        };
    } catch (Exception $e) {
        show($e);
        yield 2;
    }
}
foreach (yieldFromIteratorGeneratorThrows() as $k => $v) {
    var_dump($v);
}

// a delegate generator's trace shows the generators delegating to it
function inner() {
    yield 1;
    throw new Exception("in");
}
function outer() {
    yield 0;
    yield from inner();
}
try {
    foreach (outer() as $v) {
        echo $v, "\n";
    }
} catch (Exception $e) {
    show($e->getTraceAsString());
}

// a user Iterator's methods, also two delegations deep, and debug_backtrace()
class I implements Iterator {
    private $i = 0;
    function current(): mixed {
        show((new Exception)->getTraceAsString());
        return $this->i;
    }
    function key(): mixed {
        $t = debug_backtrace();
        echo count($t), ' ', $t[1]['function'], ' ', $t[1]['line'] ?? '-', ' ', $t[2]['function'] ?? '-', "\n";
        return $this->i;
    }
    function next(): void { $this->i++; }
    function rewind(): void { show((new Exception)->getTraceAsString()); }
    function valid(): bool { return $this->i < 1; }
}
function g2($a) {
    $x = yield from new I;
}
foreach (g2(5) as $v) {}
function g3() {
    yield 1;
    yield from g2(6);
}
foreach (g3() as $v) {}
$g = g3();
$g->current();
$g->next();
var_dump($g->current());

// "passed in ... on line" names the generator's line, from its body and from its Iterator
function f($a) {}
function g() {
    yield 1;
    try {
        f();
    } catch (ArgumentCountError $e) {
        show($e->getMessage());
    }
}
foreach (g() as $v) {}
class K extends ArrayIterator {
    function key(): string|int|null {
        try {
            f();
        } catch (ArgumentCountError $e) {
            show($e->getMessage());
        }
        return 0;
    }
}
function h() {
    yield from new K([1]);
}
foreach (h() as $v) {}
