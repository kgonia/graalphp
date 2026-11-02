<?php
// Included by bytecode-include-scope.php into warns(): throws at this file's lines, from a nested call
function thrower_in_include() {
    throw new Exception("thrown in an included function");
}
$afterThrow = "before";
thrower_in_include();
$afterThrow = "after";
