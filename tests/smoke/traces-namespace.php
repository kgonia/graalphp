<?php
// In a namespace, php-src compiles in only a fully qualified call (\count): an unqualified one has a frame.
namespace App;

ob_start(function ($text) {
    // "/" after the directory: Truffle PHP runs the smoke tests on the host
    return str_replace([__DIR__ . "\\", __DIR__ . "/"], "DIR/", $text);
});

class Items implements \Countable {
    public function count(): int {
        throw new \RuntimeException("no count");
    }
}

function unqualified($v) {
    return count($v);
}

function qualified($v) {
    return \count($v);
}

foreach (['unqualified', 'qualified'] as $f) {
    try {
        (__NAMESPACE__ . '\\' . $f)(new Items());
    } catch (\RuntimeException $e) {
        echo $f, ":\n", $e->getTraceAsString(), "\n";
    }
}
