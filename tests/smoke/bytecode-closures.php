<?php
// Closures and arrow functions declared in functions on bytecode (-Dpolyglot.php.Interpreter=bytecode): captures
// by value (a captured array is held) and by reference, arrow functions' automatic captures (nested ones too),
// $this and the class scope, static closures, Closure::bind and call(), closures made in loops and generators, and
// generators that are closures.

set_error_handler(function ($type, $message, $file, $line) {
    echo "[$type] $message (line $line)\n";
    return true;
});

class Account {
    private $balance = 10;
    public $name = 'acct';

    function adder() {
        return function ($amount) {
            $this->balance += $amount;
            return $this->balance;
        };
    }

    function arrow() {
        return fn($x) => $this->name . ":" . $x;
    }

    function staticOne() {
        return static function () {
            return isset($this) ? 'has this' : 'no this';
        };
    }

    function scope() {
        return function () {
            return self::class . ' ' . static::class;
        };
    }

    function peek() {
        return fn(Account $other) => $other->balance;
    }
}

class Savings extends Account {
}

function captures() {
    $a = 1;
    $b = [1, 2];
    $byValue = function () use ($a, $b) {
        $b[] = 3;
        return [$a, $b];
    };
    $a = 2;
    $b[] = 'outer';
    var_dump($byValue(), $a, $b);
    $count = 0;
    $byRef = function ($n) use (&$count) {
        $count += $n;
        return $count;
    };
    $byRef(5);
    $byRef(6);
    var_dump($count);
    $new = function () use (&$created) {
        $created = 'made inside';
    };
    $new();
    var_dump($created);
    $fib = function ($n) use (&$fib) {
        return $n < 2 ? $n : $fib($n - 1) + $fib($n - 2);
    };
    var_dump($fib(10));
    $mixed = function ($x) use ($a, &$count) {
        $count++;
        return $x + $a + $count;
    };
    var_dump($mixed(100), $count);
}
captures();

function arrows() {
    $x = 10;
    $y = 20;
    $add = fn($z) => $x + $y + $z;
    $x = 1000;
    var_dump($add(1));
    $nested = fn($a) => fn($b) => $a + $b + $y;
    var_dump($nested(1)(2));
    $arr = [1, 2];
    $append = fn() => array_merge($arr, [3]);
    $arr[] = 'later';
    var_dump($append(), $arr);
    $shadow = fn($x) => $x * 2;
    var_dump($shadow(4));
    $makers = [];
    for ($i = 0; $i < 3; $i++) {
        $makers[] = fn() => $i * 10;
    }
    var_dump(array_map(fn($f) => $f(), $makers));
    $inner = fn() => function () use ($y) {
        return $y;
    };
    var_dump($inner()());
    // an arrow function writes its own copy
    $counter = 0;
    $bump = fn() => ++$counter;
    var_dump($bump(), $bump(), $counter);
}
arrows();

function objects() {
    $acct = new Account;
    $add = $acct->adder();
    var_dump($add(5), $add(1));
    var_dump($acct->arrow()('x'));
    var_dump($acct->staticOne()());
    var_dump((new Savings)->scope()());
    var_dump($acct->peek()(new Savings));
    $unbound = function () {
        return $this->name;
    };
    $bound = Closure::bind($unbound, $acct, Account::class);
    var_dump($bound());
    $reader = function () {
        return $this->balance;
    };
    var_dump($reader->call($acct));
    $static = static fn() => 'static arrow';
    var_dump($static());
}
objects();

function generators() {
    $base = 100;
    $gen = function ($n) use ($base) {
        for ($i = 0; $i < $n; $i++) {
            $sent = yield $base + $i;
            if ($sent !== null) {
                echo "sent $sent\n";
            }
        }
        return 'done';
    };
    $g = $gen(3);
    foreach ($g as $k => $v) {
        echo "$k => $v\n";
    }
    var_dump($g->getReturn());
    $made = (function () {
        $closures = [];
        foreach ([1, 2] as $n) {
            $closures[] = function () use ($n) {
                return $n * 2;
            };
            yield $n;
        }
        return $closures;
    })();
    foreach ($made as $v) {
    }
    var_dump(array_map(fn($c) => $c(), $made->getReturn()));
    $delegate = function () {
        $r = yield from (function () {
            yield 'inner';
            return 'inner result';
        })();
        yield $r;
    };
    var_dump(iterator_to_array($delegate(), false));
}
generators();
