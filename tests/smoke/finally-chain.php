<?php
// An exception thrown out of a finally block while another unwinds takes that one as its previous (at the end of
// its own chain, never in a cycle); a return out of the finally block drops it. In a generator, a yield inside a
// finally block resumes there and then carries on with how the try was left; exit() runs no finally block.
function chain(Throwable $e) {
    $messages = [];
    for (; $e !== null; $e = $e->getPrevious()) {
        $messages[] = get_class($e) . ":" . $e->getMessage();
    }
    echo implode(" <- ", $messages), "\n";
}

function run(callable $test) {
    try {
        var_dump($test());
    } catch (Throwable $e) {
        chain($e);
    }
}

run(function () {
    try {
        throw new Exception("try");
    } finally {
        throw new Exception("finally");
    }
});

run(function () {
    try {
        try {
            throw new LogicException("try");
        } catch (LogicException $e) {
            throw new RuntimeException("catch");
        } finally {
            throw new Exception("finally");
        }
    } catch (Exception $e) {
        chain($e);
        return "caught";
    }
});

// the inner return runs the middle finally first, whose exception still finds "1" pending
run(function () {
    try {
        throw new Exception("1");
    } finally {
        try {
            try {
            } finally {
                return 42;
            }
        } finally {
            throw new Exception("2");
        }
    }
});

// an exception caught inside the finally block takes nothing; the next one out of it does
run(function () {
    try {
        throw new Exception("1");
    } finally {
        try {
            try {
            } finally {
                throw new Exception("2");
            }
        } catch (Exception $e) {
            echo "caught ", $e->getMessage(), ", previous ", var_export($e->getPrevious(), true), "\n";
        }
        try {
        } finally {
            throw new Exception("3");
        }
    }
});

run(function () {
    try {
        throw new Exception("pending");
    } finally {
        return "finally wins";
    }
});

// the thrown one already has a chain: the pending one goes at its end
run(function () {
    try {
        throw new Exception("pending");
    } finally {
        throw new Exception("outer", 0, new Exception("inner"));
    }
});

// the same object again, and one whose chain already holds the pending one: no cycle
run(function () {
    $e = new Exception("same");
    try {
        throw $e;
    } finally {
        throw $e;
    }
});
run(function () {
    $first = new Exception("first");
    try {
        throw $first;
    } finally {
        throw new Exception("second", 0, $first);
    }
});

// runtime errors on either side
run(function () {
    try {
        return intdiv(1, 0);
    } finally {
        throw new Exception("finally");
    }
});
run(function () {
    try {
        throw new Exception("pending");
    } finally {
        intdiv(1, 0);
    }
});

// break and continue out of the try run the finally block, an exception from it unwinds alone
run(function () {
    foreach ([1] as $i) {
        try {
            break;
        } finally {
            throw new Exception("after break");
        }
    }
});

echo "-- generators\n";

function returnThenYield() {
    try {
        echo "before return\n";
        return "returned";
    } finally {
        echo "before yield\n";
        yield "from finally";
        echo "after yield\n";
    }
}
$gen = returnThenYield();
var_dump($gen->current());
$gen->next();
var_dump($gen->valid(), $gen->getReturn());

function yieldTwice() {
    try {
        echo "1";
        yield "2";
        echo "3";
    } finally {
        echo "4";
        yield "5";
        echo "6";
    }
    echo "7";
}
foreach (yieldTwice() as $x) {
    echo $x;
}
echo "\n";

function throwThenYield() {
    try {
        throw new Exception("from try");
    } finally {
        $sent = yield "in finally";
        echo "sent ", $sent, "\n";
    }
}
$gen = throwThenYield();
var_dump($gen->current());
try {
    $gen->send("value");
} catch (Exception $e) {
    chain($e);
}
var_dump($gen->valid());

function catchThenYield() {
    try {
        throw new LogicException("try");
    } catch (LogicException $e) {
        yield "in catch";
        throw new RuntimeException("catch");
    } finally {
        yield "in finally";
        throw new Exception("finally");
    }
}
$gen = catchThenYield();
try {
    foreach ($gen as $value) {
        echo $value, "\n";
    }
} catch (Exception $e) {
    chain($e);
}

function chainedInGenerator() {
    try {
        yield 1;
        throw new Exception("try");
    } finally {
        throw new Exception("finally");
    }
}
try {
    foreach (chainedInGenerator() as $value) {
        echo $value, "\n";
    }
} catch (Exception $e) {
    chain($e);
}

function returnInFinally() {
    try {
        throw new Exception("discarded");
    } finally {
        return 42;
    }
    yield;
}
var_dump(returnInFinally()->getReturn());

function throwInFinally() {
    try {
        return 42;
    } finally {
        throw new Exception("from finally");
    }
    yield;
}
$gen = throwInFinally();
try {
    var_dump($gen->getReturn());
} catch (Exception $e) {
    echo $e->getMessage(), "\n";
}
try {
    var_dump($gen->getReturn());
} catch (Exception $e) {
    echo $e->getMessage(), "\n";
}

function failing() {
    yield 0;
    throw new Exception("failed");
}
function delegating($inner) {
    $result = yield from $inner;
    echo "delegated ", var_export($result, true), "\n";
}
// a generator that is done can't be delegated to, whether it threw or returned
function returning() {
    return "returned";
    yield;
}
$inner = returning();
$inner->current();
try {
    foreach (delegating($inner) as $value) {
    }
} catch (Error $e) {
    echo get_class($e), ": ", $e->getMessage(), "
";
}
// one that was advanced is delegated to from where it is, without a rewind
function counting() {
    yield 1;
    yield 2;
    return "counted";
}
$inner = counting();
$inner->next();
foreach (delegating($inner) as $value) {
    echo "delegated value ", $value, "
";
}
$inner = failing();
try {
    foreach ($inner as $value) {
    }
} catch (Exception $e) {
    echo $e->getMessage(), "\n";
}
try {
    foreach (delegating($inner) as $value) {
    }
} catch (Error $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}

function exits() {
    try {
        yield 1;
        echo "exiting\n";
        exit;
    } finally {
        echo "finally on exit\n";
    }
}
foreach (exits() as $value) {
    echo $value, "\n";
}
