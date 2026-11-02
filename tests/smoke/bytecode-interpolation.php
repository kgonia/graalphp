<?php
// String interpolation and heredoc in functions on bytecode (-Dpolyglot.php.Interpreter=bytecode): each part is
// converted to a string as soon as it is evaluated (php-src's ROPE_ADD: a __toString() runs before the next part),
// then the parts are joined; elements, properties, method calls and every kind of value as a part.

class Loud {
    public $name;
    public $inner;

    function __construct($name) {
        $this->name = $name;
    }

    function __toString(): string {
        echo "[toString {$this->name}]";
        return "<{$this->name}>";
    }

    function side($text) {
        echo "[side $text]";
        return $text;
    }
}

class Bad {
    function __toString(): string {
        throw new Exception('no string');
    }
}

function parts($a, $i, $f, $o) {
    $list = [10, 20, 'k' => 'kv', 'nested' => ['x' => 'deep']];
    $key = 'k';
    echo "plain $a and {$a}\n";
    echo "int $i, float $f, negative {$i}-{$f}\n";
    echo "list $list[0] $list[1] $list[k] {$list['k']} {$list[$key]} {$list['nested']['x']}\n";
    echo "prop $o->name {$o->name} {$o->inner->name}\n";
    echo "call {$o->side('x')} done\n";
    echo "bools [" . true . "] [" . false . "] {$a}\n";
    $t = true;
    $n = null;
    $no = false;
    echo "values [$t] [$n] [$no]\n";
    echo "$i$i$i\n";
    echo "{$i}{$f}{$a}\n";
    return "returned $a $i";
}

function order() {
    $first = new Loud('first');
    $second = new Loud('second');
    echo "order: $first then {$second->side('call')} then $second\n";
    $s = "kept $first";
    echo "\n", $s, "\n";
}

function failing() {
    $o = new Loud('before');
    try {
        echo "fails: $o " . "and " . new Bad() . "\n";
    } catch (Exception $e) {
        echo "\ncaught ", $e->getMessage(), "\n";
    }
    try {
        $x = "fails in the middle: $o {$o->side('side')} " . new Bad();
    } catch (Exception $e) {
        echo "\ncaught ", $e->getMessage(), "\n";
    }
    try {
        $b = new Bad();
        $x = "fails: $o {$b} {$o->side('never')}";
        echo $x;
    } catch (Exception $e) {
        echo "\ncaught ", $e->getMessage(), "\n";
    }
}

function heredoc($name, $items) {
    $count = count($items);
    $text = <<<EOT
    Hello $name,
      you have $count items: {$items[0]} and {$items[1]}.
    Literal \$name and {\$name}; tab\tend
    EOT;
    echo $text, "\n";
    $now = <<<'EOT'
    nowdoc $name {$items[0]}
    EOT;
    echo $now, "\n";
    echo <<<EOT
    inline $name
    EOT . "!\n";
}

function escapes($v) {
    echo "esc: \x41\101\u{1F600} \$v $v {$v}s\n";
}

function gen($prefix) {
    $n = 0;
    while ($n < 3) {
        $got = yield "$prefix-$n";
        echo "got $got for $prefix #$n\n";
        $n++;
    }
    return "done $prefix after $n";
}

function local_interpolation() {
    $parts = [];
    for ($i = 0; $i < 3; $i++) {
        $parts[] = "p$i";
    }
    return "{$parts[0]},{$parts[1]},{$parts[2]}";
}

$o = new Loud('obj');
$o->inner = new Loud('inner');
echo parts('str', 42, 1.5, $o), "\n";
echo parts('', -7, 0.1 + 0.2, $o), "\n";
order();
failing();
heredoc('Ann', ['a', 'b']);
escapes('v');
$g = gen('g');
foreach ($g as $k => $v) {
    echo "$k => $v\n";
    $g->send("sent $k");
}
echo $g->getReturn(), "\n";
echo local_interpolation(), "\n";
