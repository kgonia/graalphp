<?php
// Included by bytecode-include-scope.php into kinds(): its int, float and text get other types
$was = [$int, $float, $text];
$int = "int was $int";
$float = (int) $float;
$text = 2.25;
$madeThere = "made by kinds.inc";
return $was;
