<?php
// The Closure class (zend_closures.c): bind, bindTo, call and fromCallable, with their binding rules.
set_error_handler(function ($type, $message) {
    echo "[$type] $message\n";
    return true;
});

class Counter {
    private $count = 0;
    protected static $label = 'counter';

    public function increment() {
        return ++$this->count;
    }

    public static function make() {
        return new static();
    }
}
class Other {
    private $count = 100;
}

$peek = function () {
    return $this->count;
};
$counter = new Counter;
$counter->increment();
var_dump(Closure::bind($peek, $counter, Counter::class)());
var_dump($peek->bindTo($counter, $counter)());
var_dump($peek->call($counter), $peek->call(new Other));
var_dump(Closure::bind(fn() => static::$label, null, Counter::class)());
$add = function ($a, $b) {
    return $this->count + $a + $b;
};
var_dump($add->call($counter, 2, 3));

// a captured variable stays shared with the rebound closure
$total = 0;
$sum = function ($n) use (&$total) {
    $total += $n;
    return $total;
};
$bound = $sum->bindTo(new Other);
$bound(5);
$sum(1);
var_dump($total);

// what can't be bound
$static = static function () {
    return 1;
};
var_dump(Closure::bind($static, new Counter));
var_dump($peek->bindTo(new Counter, 'NoSuchClass'));
var_dump(Closure::bind($peek, null, 'stdClass'));
$usesThis = Closure::bind(function () {
    return $this;
}, new Counter, Counter::class);
var_dump($usesThis->bindTo(null));
$noThis = function () {
    return 42;
};
var_dump($noThis->bindTo(null)(), Closure::bind($noThis, new Other, null)());

// fromCallable
$strlen = Closure::fromCallable('strlen');
var_dump($strlen('four'), $strlen instanceof Closure, Closure::fromCallable($noThis) === $noThis);
$method = Closure::fromCallable([$counter, 'increment']);
var_dump($method(), $method->bindTo(new Counter)(), get_class(Closure::fromCallable('Counter::make')()));
var_dump($method->bindTo(new Other));
var_dump($method->bindTo($counter, Other::class));
var_dump($strlen->bindTo(null, Counter::class));
try {
    Closure::fromCallable('no_such_function');
} catch (TypeError $e) {
    echo $e->getMessage(), "\n";
}
try {
    new Closure();
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
try {
    Closure::bind('strlen', null);
} catch (TypeError $e) {
    echo $e->getMessage(), "\n";
}
var_dump(class_exists('Closure'), $peek instanceof Closure, get_class($peek));
