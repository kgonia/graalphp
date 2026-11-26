<?php
require __DIR__ . '/mixed_caller.php';
class Drop { public $tag = "original object"; function __destruct() { echo "drop\n"; } }
class Provider {
    public $value = "original";
    function install() { $this->value = new Drop(); }
    function items() { return [new Drop()]; }
    function &slot() { return $this->value; }
}
runMixed(new Provider());
