<?php
class Box {}
$o = new Box();
try { $o->missingMethod(); } catch (Error $e) { echo $e->getMessage() . "\n"; }
try { new Exception("x", 0, 1); } catch (TypeError $e) { echo $e->getMessage() . "\n"; }
$ex = new Exception("ok");
try { $ex->getMessage(1); } catch (ArgumentCountError $e) { echo $e->getMessage() . "\n"; }
