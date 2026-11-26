<?php
class Part { function __destruct() { echo "part\n"; } }
class Box {
    public $part;
    function __construct($x) { $this->part = new Part(); throw new Exception("body"); }
    function __destruct() { echo "failed box destructor\n"; }
}
function fail() { throw new Exception("argument"); }
try { new Box(fail()); } catch (Exception $e) { echo $e->getMessage() . "\n"; }
try { new Box(x: 1); } catch (Exception $e) { echo $e->getMessage() . "\n"; }
try { new Box(...[1]); } catch (Exception $e) { echo $e->getMessage() . "\n"; }
echo "done\n";
