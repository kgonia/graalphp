<?php
// "Array to string conversion" (zval_get_string_func): every conversion of an array to a string warns and gives
// "Array": echo, print, concatenation, .=, interpolation, (string), strval(), settype(), built-ins that convert
// their values (implode per element, printf's %s). Comparisons with an array don't convert it. (A built-in's string
// parameter rejects an array with a TypeError: not tested here, see issue 023.)

set_error_handler(function ($type, $message, $file, $line) {
    echo "[$type] $message (line $line)\n";
    return true;
});

function conversions() {
    $a = [1];
    echo $a, "\n";
    print $a;
    print "\n";
    echo 'concat: ' . $a . "\n";
    echo 'left: ' . $a . $a . "\n";
    $s = 'compound: ';
    $s .= $a;
    echo $s, "\n";
    $t = $a;
    $t .= 'x';
    echo $t, "\n";
    echo "interpolation: $a {$a}\n";
    var_dump((string) $a, strval($a));
    $c = $a;
    settype($c, 'string');
    var_dump($c);
    echo implode(',', [[1], 2, [3]]), "\n";
    echo join('-', [[]]), "\n";
    printf("%s|%d\n", $a, $a);
    echo sprintf('%s', $a), "\n";
    var_dump($a == 'Array', 'Array' == $a, $a < 'x');
    echo "$a[0]\n";
    $nested = ['k' => [1]];
    echo "nested: {$nested['k']}\n";
    echo strtoupper(implode(' ', [$a])), "\n";
}
conversions();

class Holder {
    public $arr = [1];

    function method() {
        return 'method: ' . $this->arr;
    }
}
echo (new Holder())->method(), "\n";

$closure = function () {
    return "closure: " . [];
};
echo $closure(), "\n";

function gen() {
    yield 'gen: ' . [2];
}
foreach (gen() as $v) {
    echo $v, "\n";
}

class Sleeps {
    public $a = 1;

    function __sleep() {
        return [['a']];
    }
}
echo serialize(new Sleeps()), "
";

// file level
$top = [];
echo $top, "\n";
echo "top: $top\n";
$topStr = 'x';
$topStr .= $top;
echo $topStr, "\n";

// a handler that throws: the conversion stops there
set_error_handler(function ($type, $message, $file, $line) {
    throw new RuntimeException("$message (line $line)");
});
function throws() {
    $s = 'start';
    try {
        $s .= [];
        echo "not reached\n";
    } catch (RuntimeException $e) {
        echo "caught: ", $e->getMessage(), "\n";
    }
    var_dump($s);
    try {
        echo implode(',', [1, [2], 3]);
        echo "not reached\n";
    } catch (RuntimeException $e) {
        echo "\ncaught: ", $e->getMessage(), "\n";
    }
}
throws();
restore_error_handler();
restore_error_handler();
echo "done\n";
