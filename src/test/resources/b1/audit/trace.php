<?php
function allocateException($tag) {
    $prior = new Exception("prior");
    return new Exception($tag, 37, $prior);
}
class Caller {
    function run() {
        return allocateException("message");
    }
}
$o = new Caller();
$e = $o->run();
$p = $e->getPrevious();
echo $e->getFile() . ":" . $e->getLine() . ":" . $e->getCode() . ":" . $p->getMessage() . "\n";
echo $e->getTraceAsString() . "\n";
$trace = $e->getTrace();
