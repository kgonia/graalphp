<?php
class Printable { function __toString() { return "printable"; } }
class Box { public $café; }
echo $missing;
$b = new Box();
echo $b->missing;
$b->café = "café";
echo "$b->café\n";
$p = new Printable();
echo $p;
echo "\n";
try { echo $b; } catch (Error $e) { echo $e->getMessage() . "\n"; }
echo "9007199254740993" + 2;
echo "\n";
