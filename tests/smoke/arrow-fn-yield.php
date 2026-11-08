<?php
// Issue 069: An arrow function with yield is a generator.
$f = fn() => yield 1;
foreach ($f() as $v) echo $v;
echo "\n";
