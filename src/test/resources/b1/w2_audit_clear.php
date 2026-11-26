<?php
class Leaf {
    public $id;
    function __construct($id) { $this->id = $id; }
    function __destruct() { echo "leaf", $this->id, "\n"; throw new Exception("leaf" . $this->id); }
}
class Cycle {
    public $self, $leaf, $id;
    function __construct($id) { $this->id = $id; $this->self = $this; }
    function __destruct() { $this->leaf = new Leaf($this->id); }
}
$a = new Cycle(1);
$b = new Cycle(2);
unset($a, $b);
try { gc_collect_cycles(); }
catch (Exception $e) { echo $e->getMessage(), "/", $e->getPrevious()->getMessage(), "\n"; }
echo gc_collect_cycles(), "\n";
