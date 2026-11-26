<?php
class Drop {
    public $tag;
    function __construct($tag) { $this->tag = $tag; }
    function __destruct() { echo "drop:$this->tag\n"; }
}
class Throws { function __destruct() { echo "throw\n"; throw new Exception("shutdown"); } }
function aliasIt(&$a) {}
function handle($e) { echo "handled:" . $e->getMessage() . "\n"; }
set_exception_handler("handle");
$a = new Drop("former-ref");
aliasIt($a);
$b = new Drop("ordinary");
$t = new Throws();
$c = new Drop("last");
