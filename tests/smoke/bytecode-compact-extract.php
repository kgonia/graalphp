<?php
// compact(), extract() and get_object_vars() inside functions on bytecode, by php-src's rules: compact() reads the
// call's symbol table (the function's variables, those an extract() or include made, $this for "this") and warns
// about undefined names and names that aren't strings; extract() writes the function's variables (ints and floats
// it keeps unboxed, references) or makes new ones by each mode; get_object_vars() sees what the calling scope may.

set_error_handler(function ($type, $message, $file, $line) {
    echo "[$type] $message (line $line)\n";
    return true;
});

function literal_names($a, $b = null) {
    $c = 3;
    $d = 1.5;
    for ($i = 0; $i < 3; $i++) {
        $c += $i;
        $d *= 2;
    }
    $ref = &$c;
    // a null counts; an undefined variable is warned about; a superglobal or $GLOBALS isn't a function's variable
    return compact('a', 'b', ['c', ['d']], 'ref', 'undefinedHere', '_GET', 'GLOBALS', 'a');
}
var_dump(literal_names(1));

function dynamic_names(...$names) {
    $one = 'first';
    $two = [1, 2];
    $list = ['one', 'two'];
    return compact($names, $list, 'one', 42, null, 1.5, true);
}
var_dump(dynamic_names('two', ['one', 'missing']));

class Holder {
    private $secret = 's';
    protected $guarded = 'g';
    public $open = 'o';

    public function names() {
        $x = 'x';
        return compact('this', 'x');
    }

    public static function noThis() {
        return compact('this');
    }

    public function visible() {
        return get_object_vars($this);
    }

    public function visibleOf($other) {
        return get_object_vars($other);
    }
}
class SubHolder extends Holder {
    private $own = 'own';

    public function fromSub() {
        return get_object_vars($this);
    }
}
$h = new Holder();
var_dump(array_keys(Holder::noThis()));
var_dump(count($h->names()), $h->names()['this'] === $h);
var_dump($h->visible(), $h->visibleOf(new SubHolder()), (new SubHolder())->fromSub());

function outside($object) {
    return get_object_vars($object);
}
var_dump(outside(new SubHolder()));
$o = new stdClass();
$o->dynamic = [1];
$o->{'0'} = 'zero';
var_dump(outside($o));
var_dump(Closure::bind(function ($object) {
    return get_object_vars($object);
}, null, Holder::class)(new Holder()));
try {
    outside('not an object');
} catch (TypeError $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

function extracting($mode, $values, $prefix = null) {
    $existing = 'old';
    $number = 10;
    $float = 0.5;
    for ($i = 0; $i < 2; $i++) {
        $number += $i;
        $float += $i;
    }
    $count = $prefix === null ? extract($values, $mode) : extract($values, $mode, $prefix);
    var_dump($count, $existing, $number, $float, $number + 1, $float * 2);
    return compact('fresh', 'p_existing', 'p_fresh', 'p_0', 'p_number');
}
$values = ['existing' => 'new', 'fresh' => 'created', 'number' => '20', 'float' => 7, 0 => 'zero',
    'bad name' => 1];
var_dump(extracting(EXTR_OVERWRITE, $values));
var_dump(extracting(EXTR_SKIP, $values));
var_dump(extracting(EXTR_PREFIX_SAME, $values, 'p'));
var_dump(extracting(EXTR_PREFIX_ALL, $values, 'p'));
var_dump(extracting(EXTR_PREFIX_INVALID, $values, 'p'));
var_dump(extracting(EXTR_IF_EXISTS, $values));
var_dump(extracting(EXTR_PREFIX_IF_EXISTS, $values, 'p'));

function extract_refs() {
    $target = 'before';
    $alias = &$target;
    extract(['alias' => 'through the reference', 'other' => [1, 2]]);
    $other[] = 3;
    return [$target, $alias, $other];
}
var_dump(extract_refs());

function extract_then_compact($args) {
    // the names extract() makes are only in compact()'s symbol table: the function never names them
    extract($args);
    $names = array_keys($args);
    return [compact($names), compact('alpha', 'beta')];
}
var_dump(extract_then_compact(['alpha' => 'a', 'beta' => ['b']]));
var_dump(extract_then_compact(['alpha' => 'again']));

function extract_errors() {
    $calls = [
        fn() => extract('not an array'),
        fn() => compact(),
        fn() => extract([], 0, 'p', 'four'),
        fn() => extract(),
        fn() => extract(['a' => 1], 99),
        fn() => extract(['a' => 1], EXTR_PREFIX_ALL),
        fn() => extract(['a' => 1], EXTR_PREFIX_ALL, '1x'),
        fn() => extract(['a' => 1], 'not a number'),
    ];
    foreach ($calls as $call) {
        try {
            var_dump($call());
        } catch (Throwable $e) {
            echo get_class($e), ': ', $e->getMessage(), "\n";
        }
    }
    // the variables before $this are made; a valid prefix makes it p_this
    try {
        extract(['before' => 'made', 'this' => 'no']);
    } catch (Error $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
    var_dump(compact('before'));
    var_dump(extract(['this' => 1, 'GLOBALS' => 2], EXTR_SKIP), extract(['this' => 1], EXTR_PREFIX_SAME, 'p'),
        extract(['this' => 1, 5 => 'five', '07' => 'x'], EXTR_PREFIX_INVALID, 'n'),
        compact('p_this', 'n_this', 'n_5', 'n_07', 'GLOBALS'));
}
extract_errors();

// a variable the function names but that is unset is in php-src's symbol table, undefined
function unset_names() {
    $a = 1;
    $b = 2;
    $c = null;
    unset($a, $b);
    var_dump(extract(['a' => 'a2', 'c' => 'c2', 'd' => 'd2'], EXTR_SKIP), $a, $c);
    unset($a);
    var_dump(extract(['a' => 'a3', 'b' => 'b3', 'c' => 'c3', 'e' => 'e3'], EXTR_PREFIX_IF_EXISTS, 'q'), $a, $b, $c);
    unset($a);
    var_dump(extract(['a' => 'a4', 'c' => 'c4'], EXTR_PREFIX_SAME, 'r'), $a, $c);
    unset($a);
    var_dump(extract(['a' => 'a5', 'c' => 'c5', 'e' => 'e5'], EXTR_IF_EXISTS), isset($a), $c);
    return compact('d', 'q_c', 'q_e', 'r_c', 'e');
}
var_dump(unset_names());

$closure = function ($values) {
    $kept = 'kept';
    extract($values, EXTR_SKIP);
    return compact('kept', 'added');
};
var_dump($closure(['kept' => 'replaced?', 'added' => 'added']));

function in_generator($values) {
    extract($values);
    yield compact('first');
    yield $first;
}
foreach (in_generator(['first' => 'from the generator']) as $value) {
    var_dump($value);
}
