<?php
class ThrowingCycle {
    public $peer, $id;
    function __construct($id) { $this->id = $id; }
    function __destruct() {
        echo "d", $this->id, "\n";
        throw new Exception("e" . $this->id);
    }
}
$a = new ThrowingCycle(1);
$b = new ThrowingCycle(2);
$a->peer = $b;
$b->peer = $a;
unset($a, $b);
try { gc_collect_cycles(); }
catch (Exception $e) { echo $e->getMessage(), "/", $e->getPrevious()->getMessage(), "\n"; }
echo gc_collect_cycles(), "\n";
