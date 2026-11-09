<?php
// Issue 069: An arrow function with yield is a generator.
$f = fn() => yield 1;
foreach ($f() as $v) echo $v;
echo "\n";
$g = fn($x) => yield from [$x, $x + 1]; foreach ($g(5) as $k => $v) echo "$k=$v "; echo "\n";
$h = fn() => (yield 1) + (yield 2); $gen = $h(); echo $gen->current(); $gen->send(10); echo $gen->current(); $gen->send(20); var_dump($gen->getReturn());
$y = 3; $i = fn(): Generator => yield $y; var_dump(iterator_to_array($i()));
$n = fn() => fn() => yield 7; var_dump($n() instanceof Closure, iterator_to_array(($n())()));
