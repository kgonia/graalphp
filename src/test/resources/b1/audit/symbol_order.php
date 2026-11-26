<?php
class Drop {
    function __construct(public $tag) {}
    function __destruct() { echo "$this->tag\n"; }
}
if (false) { $first = 0; }
$second = new Drop("second");
$first = new Drop("first");
unset($first);
$first = new Drop("first again");
function dynamic() { $GLOBALS["dynamic"] = new Drop("dynamic"); }
dynamic();
