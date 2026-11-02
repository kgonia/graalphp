<?php
// The silence operator `@expr` in functions on bytecode (BEGIN_SILENCE / END_SILENCE): while the expression runs
// error_reporting keeps only the fatal errors, so a handler that honours it (as PHP's display does) sees the others
// as silenced, and error_get_last() still has them; error_reporting is restored however the expression is left,
// also by an exception, unless the expression set a value of its own; a generator suspended inside `@` stays silenced.

set_error_handler(function ($type, $message, $file, $line) {
    $silenced = (error_reporting() & $type) ? '' : ' silenced';
    echo "[$type$silenced] $message (line $line)\n";
    return true;
});

function show($label, $value) {
    echo $label, ': ', json_encode($value), "\n";
}

function warn($message) {
    trigger_error($message, E_USER_WARNING);
    return "warned $message";
}

function thrower() {
    echo "level inside: ", error_reporting(), "\n";
    throw new RuntimeException('thrown');
}

function setsLevel() {
    error_reporting(E_ALL & ~E_NOTICE);
    trigger_error('after the change', E_USER_WARNING);
    return 'set';
}

function reads() {
    $a = ['k' => 1];
    show('present key', @$a['k']);
    show('in a condition', @$a['k'] ?: 'default');
    show('call', @warn('one'));
    show('last error', error_get_last()['message'] ?? null);
    show('double', @@warn('two'));
    echo "level after: ", error_reporting(), "\n";
    // only the left operand is silenced
    echo @warn('quiet') . warn('loud'), "\n";
    echo warn('loud first') . @warn('quiet second'), "\n";
    $sum = @strlen(warn('argument')) + strlen(warn('outside'));
    show('sum', $sum);
}

function exceptions() {
    try {
        @thrower();
    } catch (RuntimeException $e) {
        echo "caught ", $e->getMessage(), ", level ", error_reporting(), "\n";
    }
    try {
        $x = @(intdiv(1, 0));
    } catch (DivisionByZeroError $e) {
        echo "caught ", $e->getMessage(), ", level ", error_reporting(), "\n";
    }
    for ($i = 0; $i < 3; $i++) {
        try {
            $v = @($i == 1 ? thrower() : warn("loop $i"));
            show("loop $i", $v);
        } catch (RuntimeException $e) {
            echo "loop $i caught, level ", error_reporting(), "\n";
        }
    }
    try {
        @(function () {
            try {
                warn('in a try');
                throw new LogicException('inner');
            } finally {
                echo "finally level ", error_reporting(), "\n";
            }
        })();
    } catch (LogicException $e) {
        echo "caught ", $e->getMessage(), ", level ", error_reporting(), "\n";
    }
}

function changed() {
    show('set inside', @setsLevel());
    echo "level kept: ", error_reporting(), "\n";
    error_reporting(E_ALL);
}

function generator() {
    $got = @yield @warn('in a generator');
    show('sent', $got);
    yield @warn('second');
}

function generators() {
    $g = generator();
    show('first', $g->current());
    echo "level between: ", error_reporting(), "\n";
    show('second', $g->send('value'));
    echo "level after the generator: ", error_reporting(), "\n";
}

function statement() {
    @warn('as a statement');
    show('assigned', $x = @warn('assigned'));
    echo "level: ", error_reporting(), "\n";
}

class Quiet {
    public $value = 'property';

    function method() {
        return @$this->value . ' ' . @warn('in a method');
    }

    static function make() {
        return @new static();
    }
}

error_reporting(E_ALL);
reads();
exceptions();
changed();
generators();
statement();
show('method', (new Quiet)->method());
show('static', get_class(Quiet::make()));
