<?php
class GenBox {
    public $name;
    function __construct($name) { $this->name = $name; }
    function __destruct() { echo "drop ", $this->name, "\n"; }
}
function makeBox($name) { echo "make ", $name, "\n"; return new GenBox($name); }
function combine($box, $value) { echo $box->name, "+", $value, "\n"; }
function operands($initial): Generator {
    echo "enter ", $initial, "\n";
    $x = (yield 1) + (yield 2);
    echo "sum ", $x, "\n";
    combine(makeBox('op'), yield 'mid');
    yield 7 => 'seven';
    yield 'eight';
    return $x;
}
$seed = 'before';
$g = operands($seed);
$seed = 'after';
echo "created\n";
$g->rewind();
echo $g->key(), "=", $g->current(), "\n";
echo $g->send(10), "\n";
echo $g->send(20), "\n";
echo $g->send('sent'), "\n";
$g->next();
echo $g->key(), "=", $g->current(), "\n";
$g->next();
var_dump($g->valid(), $g->getReturn());

function child() {
    $local = makeBox('child');
    try {
        $sent = yield 'key' => 'child';
        echo "forward ", $sent, "\n";
        try { yield 'throw here'; }
        catch (Exception $e) { echo "caught ", $e->getMessage(), "\n"; }
        return 'returned';
    } finally { echo "inner finally\n"; }
}
function parentGen() {
    try {
        yield from ['a' => 'A', 4 => 'B'];
        $ret = yield from child();
        echo "inner return ", $ret, "\n";
        return 'parent';
    } finally { echo "outer finally\n"; }
}
$g = parentGen();
foreach ($g as $key => $value) {
    echo $key, "=", $value, "\n";
    if ($key === 'key') break;
}
echo $g->send('yes'), "\n";
$g->throw(new Exception('boom'));
echo $g->getReturn(), "\n";
$g = parentGen();
$g->next();
$g->next();
echo "abandon\n";
unset($g);
$g = operands('abandon');
$g->send(3);
$g->send(4);
unset($g);
echo "end\n";

function yieldedBox() {
    try {
        yield makeBox('yielded');
        echo "after yield\n";
    } finally { echo "value finally\n"; }
}
$g = yieldedBox();
$g->next();
echo "closed\n";
unset($g);

function lazyBox($box) {
    echo "lazy body\n";
    yield $box;
}
$g = lazyBox(makeBox('param'));
echo "still new\n";
unset($g);

$shutdown = parentGen();
$shutdown->next();
$shutdown->next();
echo "shutdown\n";
