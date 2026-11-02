<?php
// try / catch / finally and throw in functions on bytecode (-Dpolyglot.php.Interpreter=bytecode; generators run
// on bytecode in either mode): catch matching, a runtime error caught as its Error, finally on every way out
// (end, exception, return, break, continue), a return in finally replacing what was pending, an exception thrown in
// finally taking the pending one as previous, throw as an expression, and generators destroyed in a try.
// The helpers that make and print exceptions stay on the tree (they create objects and call methods).

function make($class, $message, $previous = null) {
    return new $class($message, 0, $previous);
}

function show($label, $e) {
    echo $label, ': ', get_class($e), ' "', $e->getMessage(), '" line ', $e->getLine();
    for ($p = $e->getPrevious(); $p !== null; $p = $p->getPrevious()) {
        echo ' <- ', get_class($p), ' "', $p->getMessage(), '"';
    }
    echo "\n";
}

function thrower($class, $message) {
    throw make($class, $message);
}

function catches($class) {
    try {
        echo 'try ', $class, "\n";
        thrower($class, 'from ' . $class);
        echo "not reached\n";
    } catch (InvalidArgumentException | DomainException $e) {
        show('invalid or domain', $e);
    } catch (LogicException $e) {
        show('logic', $e);
    } catch (RuntimeException) {
        echo "runtime, no variable\n";
    }
    echo 'after ', $class, "\n";
}
catches('InvalidArgumentException');
catches('DomainException');
catches('LogicException');
catches('LengthException');
catches('RuntimeException');
try {
    catches('Exception');
} catch (Exception $e) {
    show('outside', $e);
}

function runtimeErrors($divisor) {
    try {
        return 1 % $divisor;
    } catch (DivisionByZeroError $e) {
        show('division', $e);
    }
    try {
        throw 1;
    } catch (Error $e) {
        show('throw 1', $e);
    }
    try {
        thrower('Exception', 'any');
    } catch (Throwable $e) {
        show('throwable', $e);
    }
    return -1;
}
echo runtimeErrors(0), "\n";
echo runtimeErrors(3), "\n";

function finallyOnReturn() {
    try {
        echo "try\n";
        return "from try";
    } finally {
        echo "finally after return\n";
    }
}
echo finallyOnReturn(), "\n";

function finallyOnReturnFromCatch() {
    try {
        thrower('Exception', 'x');
    } catch (Exception $e) {
        return "from catch";
    } finally {
        echo "finally after the catch's return\n";
    }
    return "not reached";
}
echo finallyOnReturnFromCatch(), "\n";

function returnInFinally($throw) {
    try {
        if ($throw) {
            thrower('RuntimeException', 'dropped');
        }
        return "try";
    } finally {
        return "finally";
    }
}
echo returnInFinally(false), " ", returnInFinally(true), "\n";

function returnValueBeforeFinally() {
    $x = 1;
    try {
        return $x;
    } finally {
        $x = 2;
        echo 'x is ', $x, " in finally\n";
    }
}
echo returnValueBeforeFinally(), "\n";

function arrayReturnedBeforeFinally() {
    $a = [1];
    try {
        return $a;
    } finally {
        $a[] = 2;
        echo 'count in finally ', count($a), "
";
    }
}
echo json_encode(arrayReturnedBeforeFinally()), "
";

function loopsInFinally() {
    try {
        echo "try
";
    } finally {
        foreach ([1, 2, 3, 4] as $i) {
            if ($i === 2) {
                continue;
            }
            if ($i === 4) {
                break;
            }
            echo 'finally loop ', $i, "
";
        }
    }
    foreach ([1, 2] as $i) {
        try {
            if ($i === 1) {
                continue;
            }
            return 'returned ' . $i;
        } finally {
            for ($j = 0; $j < 3; $j++) {
                if ($j === 2) {
                    break;
                }
                echo 'nested ', $i, ' ', $j, "
";
            }
        }
    }
    return 'not reached';
}
echo loopsInFinally(), "
";

function loops() {
    foreach ([1, 2, 3, 4] as $i) {
        try {
            if ($i === 1) {
                continue;
            }
            if ($i === 3) {
                break;
            }
            echo 'body ', $i, "\n";
        } finally {
            echo 'finally ', $i, "\n";
        }
    }
    $n = 0;
    while (true) {
        $n++;
        try {
            for ($j = 0; $j < 5; $j++) {
                try {
                    if ($j === 1) {
                        break 2;
                    }
                    echo 'inner ', $n, ' ', $j, "\n";
                } finally {
                    echo 'inner finally ', $j, "\n";
                }
            }
        } finally {
            echo 'outer finally ', $n, "\n";
        }
    }
    echo 'after loops ', $n, "\n";
}
loops();

function chained() {
    try {
        try {
            thrower('LogicException', 'first');
        } finally {
            thrower('RuntimeException', 'second');
        }
    } catch (Exception $e) {
        show('chained', $e);
    }
    try {
        try {
            thrower('LogicException', 'in try');
        } catch (LogicException $e) {
            thrower('RuntimeException', 'in catch');
        } finally {
            echo "finally after a throwing catch\n";
        }
    } catch (RuntimeException $e) {
        show('from catch', $e);
    }
    try {
        try {
            try {
                thrower('LogicException', 'a');
            } finally {
                thrower('RuntimeException', 'b');
            }
        } finally {
            thrower('DomainException', 'c');
        }
    } catch (Exception $e) {
        show('three', $e);
    }
    try {
        try {
            return 1;
        } finally {
            thrower('RuntimeException', 'over a return');
        }
    } catch (Exception $e) {
        show('over return', $e);
    }
    $e = make('RuntimeException', 'same');
    try {
        try {
            throw $e;
        } finally {
            throw $e;
        }
    } catch (Exception $caught) {
        show('same object', $caught);
    }
    try {
        try {
            1 % 0;
        } finally {
            echo "finally after a runtime error\n";
        }
    } catch (Error $error) {
        show('error', $error);
    }
}
chained();

function throwExpressions($value) {
    $result = $value !== null ? $value : throw make('InvalidArgumentException', 'null value');
    $other = $value ? "truthy" : throw make('DomainException', 'falsy value');
    return $result . ' ' . $other;
}
foreach (['x', null, 0] as $v) {
    try {
        echo throwExpressions($v), "\n";
    } catch (Exception $e) {
        show('expression', $e);
    }
}

function catchVariable() {
    $e = 'before';
    try {
        echo "nothing thrown\n";
    } catch (Exception $e) {
        echo "not reached\n";
    }
    echo 'e is still ', $e, "\n";
    try {
        thrower('Exception', 'into the variable');
    } catch (Exception $e) {
    }
    show('the variable', $e);
}
catchVariable();

function uncaughtThrough() {
    try {
        thrower('LogicException', 'passes');
    } catch (RuntimeException $e) {
        echo "not reached\n";
    } finally {
        echo "finally while passing\n";
    }
}
try {
    uncaughtThrough();
} catch (LogicException $e) {
    show('passed', $e);
}

// a catch around a yield takes throw()'s exception
function catchingGenerator() {
    while (true) {
        try {
            $sent = yield;
            echo 'sent ', $sent, "\n";
        } catch (LogicException $e) {
            show('generator caught', $e);
            yield 'after catch';
        } finally {
            echo "generator finally\n";
        }
    }
}
$g = catchingGenerator();
$g->current();
$g->send('a');
echo $g->throw(make('LogicException', 'thrown in')), "\n";
$g->send('b');
try {
    $g->throw(make('RuntimeException', 'not caught'));
} catch (RuntimeException $e) {
    show('generator passed', $e);
}
var_dump($g->valid());

function returnInGeneratorFinally() {
    try {
        yield 1;
        thrower('RuntimeException', 'dropped by the return');
    } finally {
        return 'finally';
    }
}
$g = returnInGeneratorFinally();
foreach ($g as $v) {
    echo "yielded $v\n";
}
echo $g->getReturn(), "\n";

function uncaught() {
    try {
        thrower('RuntimeException', 'uncaught');
    } finally {
        echo "last finally\n";
    }
}
try {
    uncaught();
} catch (Exception $e) {
    show('through finally', $e);
}

// a generator destroyed while suspended runs the finally blocks around the yield, and no catch block takes the
// destruction
function guarded() {
    try {
        try {
            echo "yielding\n";
            yield 1;
            echo "resumed\n";
            yield 2;
        } catch (Throwable $e) {
            echo "never: destruction is not catchable\n";
        } finally {
            echo "inner finally\n";
        }
    } catch (Exception $e) {
        echo "never\n";
    } finally {
        echo "outer finally\n";
    }
}
// here at the end of the script, as php-src's shutdown destroys the objects still alive
$g = guarded();
$g->current();
echo "end of script\n";

