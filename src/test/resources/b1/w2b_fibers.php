<?php
class FiberBox {
    public $name;
    function __construct($name) { $this->name = $name; }
    function __destruct() { echo "drop ", $this->name, "\n"; }
}
function pauseDeep($label) {
    $current = Fiber::getCurrent();
    var_dump($current->isRunning());
    return Fiber::suspend($label);
}
function nestedPause($box) {
    echo 'resume ', pauseDeep($box->name), "\n";
    try { Fiber::suspend('again'); }
    catch (Exception $e) { echo "caught ", $e->getMessage(), "\n"; }
}
function fiberWork($a, $b) {
    $box = new FiberBox($a);
    try { nestedPause($box); } finally { echo "finally\n"; }
    return $b;
}
var_dump(Fiber::getCurrent());
foreach (['one', 'two'] as $name) {
    $f = new Fiber('fiberWork');
    var_dump($f->isStarted());
    echo "start ", $f->start($name, 'done'), "\n";
    var_dump($f->isStarted(), $f->isSuspended());
    echo $f->resume('ok'), "\n";
    $f->throw(new Exception('boom'));
    var_dump($f->isTerminated(), $f->isRunning());
    echo $f->getReturn(), "\n";
}
$f = new Fiber(function () {
    $local = new FiberBox('closed');
    try { Fiber::suspend(); } finally { echo "closed finally\n"; }
});
$f->start();
unset($f);
echo "end\n";
