<?php
// Where exceptions were made (file and line), for user and runtime errors, and Exception::__toString.

function make() {
    return new RuntimeException("made in a function");
}

class Custom extends Exception {
    public function __construct() {
        // no parent::__construct: the location is set when the object is made
    }
}

function where(Throwable $e) {
    echo get_class($e), " ", $e->getFile() === __FILE__ ? "FILE" : $e->getFile(), ":", $e->getLine(), "\n";
}

where(new Exception("top"));
where(make());
where(new Custom());

function single() {
    return intdiv(1, 0);
}

try {
    single();
} catch (DivisionByZeroError $e) {
    where($e);
}

try {
    if (true)
        intdiv(1, 0);
} catch (DivisionByZeroError $e) {
    where($e);
}

function fails() {
    $x = 1;
    return intdiv(1, 0);
}

try {
    fails();
} catch (DivisionByZeroError $e) {
    where($e);
}

try {
    if (true) {
        undefined_function();
    }
} catch (Error $e) {
    where($e);
    $first = $e;
}

// a runtime error keeps where it was raised through a catch that doesn't take it, and a rethrow keeps the object
try {
    try {
        json_decode("{", false, 512, JSON_THROW_ON_ERROR);
    } catch (TypeError $e) {
        echo "not this one\n";
    }
} catch (JsonException $e) {
    where($e);
    $caught = $e;
}
try {
    try {
        json_decode("{", false, 512, JSON_THROW_ON_ERROR);
    } catch (JsonException $e) {
        $caught = $e;
        throw $e;
    }
} catch (JsonException $e) {
    var_dump($e === $caught);
}

$previous = new LogicException("inner", 3);
$outer = new RuntimeException("outer", 0, $previous);
echo str_replace(__FILE__, "FILE", (string) $outer), "\n";
echo str_replace(__FILE__, "FILE", (string) new Exception()), "\n";
echo str_replace(__FILE__, "FILE", (string) $first), "\n";
