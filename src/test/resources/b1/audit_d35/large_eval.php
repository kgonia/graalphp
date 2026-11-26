<?php
$text = str_repeat('x', 70000);
$binary = str_repeat("\xff", 70000);
echo strlen(eval("return '$text';")), '/', strlen(eval("return '$binary';")), "\n";
$nested = eval("return ['$text', ['$binary']];");
echo strlen($nested[0]), '/', strlen($nested[1][0]), "\n";
$uses = '&$v0';
for ($i = 1; $i < 1000; $i++) $uses .= ", &\$v$i";
$f = eval("return static function () use ($uses) { \$v999 = 19; };");
$f();
echo $v999, "\n";
