<?php
class Drop {
    public $tag;
    function __construct($tag) { $this->tag = $tag; }
    function __destruct() { echo "drop:$this->tag\n"; $tag = $this->tag; throw new Exception($tag); }
}
class Pair { public $first; public $second; }
function snapshot($a, $b) { return new Exception("trace"); }
function report($e) {
    $p = $e->getPrevious();
    echo $e->getMessage() . "/" . $p->getMessage() . "\n";
}
$pair = new Pair();
$pair->first = new Drop("a");
$pair->second = new Drop("b");
try { $pair = 0; } catch (Exception $e) { report($e); }
$trace = snapshot(new Drop("c"), new Drop("d"));
try { $trace = 0; } catch (Exception $e) { report($e); }
echo "done\n";
