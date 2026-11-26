<?php
class Part { function __destruct() { echo "part\n"; } }
class Box {
    public $part;
    function __construct($x) { $this->part = new Part(); throw new Exception("body"); }
    function __destruct() { echo "failed box destructor\n"; }
}
class ReturnsValue {
    function __construct() { return new Part(); }
    function __destruct() { echo "complete\n"; }
}
function throwAtLine() { throw new Exception("argument"); }
try { new Box(throwAtLine()); } catch (Exception $e) { echo $e->getMessage() . "\n"; }
try { new Box(1); } catch (Exception $e) { echo $e->getMessage() . "\n"; }
new ReturnsValue();
