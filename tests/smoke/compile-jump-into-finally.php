<?php
// Issue 006: a goto into a finally block, in a closure nobody calls: the script doesn't run (finally_goto_001).
echo "not printed\n";
$f = function () {
    goto test;
    try {
    } finally {
test:
    }
};
