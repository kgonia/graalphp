<?php
// Generators: yield in blocks, branches, loops, foreach and try; keys, send, return values, yield from.

function counter($from, $to)
{
    for ($i = $from; $i <= $to; $i++) {
        yield $i;
    }
    return "done";
}

$g = counter(1, 3);
foreach ($g as $k => $v) {
    echo "$k=$v ";
}
echo $g->getReturn(), "\n";

function keyed()
{
    yield 'a' => 1;
    yield 'b' => 2;
    yield 3;
    yield 10 => 4;
    yield 5;
}
foreach (keyed() as $k => $v) {
    echo "$k=$v ";
}
echo "\n";

function echoer()
{
    $received = [];
    while (true) {
        $value = yield count($received);
        if ($value === null) {
            break;
        }
        $received[] = $value;
    }
    return implode(',', $received);
}
$e = echoer();
echo $e->current(), ' ';
echo $e->send('x'), ' ';
echo $e->send('y'), ' ';
$e->next();
echo var_export($e->valid(), true), ' ', $e->getReturn(), "\n";

class Words implements IteratorAggregate
{
    private $text;

    public function __construct($text)
    {
        $this->text = $text;
    }

    public function words()
    {
        $at = strspn($this->text, ' ');
        $length = strlen($this->text);
        $seen = [];
        while ($at < $length) {
            $size = strcspn($this->text, ' ', $at);
            $word = substr($this->text, $at, $size);
            $at += $size;
            if (!in_array($word, $seen, true)) {
                $seen[] = $word;
                yield $word;
            }
            $at += strspn($this->text, ' ', $at);
        }
    }

    public function getIterator(): Iterator
    {
        return $this->words();
    }
}
$w = new Words('  one two one  three two ');
echo implode('|', iterator_to_array($w->words(), false)), ' ';
foreach ($w as $word) {
    if ($word === 'two') {
        echo "found ";
        break;
    }
}
echo "\n";

function nested($items)
{
    foreach ($items as $key => $item) {
        if (is_array($item)) {
            try {
                yield from nested($item);
            } finally {
                echo "[end $key] ";
            }
        } else {
            yield $key => $item;
        }
    }
}
foreach (nested(['a' => 1, 'b' => ['c' => 2, 'd' => [3, 4]], 'e' => 5]) as $k => $v) {
    echo "$k:$v ";
}
echo "\n";

function failing()
{
    try {
        yield 1;
        yield 2;
    } catch (Exception $e) {
        echo "caught ", $e->getMessage(), ' ';
        yield 99;
    }
}
$f = failing();
echo $f->current(), ' ';
echo $f->throw(new Exception('boom')), ' ';
$f->next();
echo var_export($f->valid(), true), "\n";

function fromArray()
{
    $result = yield from [10, 20];
    yield 30;
    return $result;
}
echo implode(',', iterator_to_array(fromArray(), false)), "\n";

$lazy = (function () {
    echo "started ";
    yield 1;
})();
echo "created ";
echo $lazy->current(), "\n";
