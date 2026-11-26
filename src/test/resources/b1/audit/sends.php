<?php
class Box {}
function f(int &$x, $y) { echo "f:$x\n"; $x = $x + 1; }
function boom() { echo "boom ran\n"; throw new Exception("boom"); }
function number() { return "21"; }
try { f(1, boom()); } catch (Error $e) { echo $e->getMessage() . "\n"; }
f(number(), 0);
$x = "9007199254740993";
f($x, 0);
echo "$x\n";
try { f($x); } catch (ArgumentCountError $e) { echo $e->getMessage() . "\n"; }
$x = new Box();
try { f($x, 0); } catch (TypeError $e) { echo $e->getMessage() . "\n"; }
