<?php
// Issue 006: break outside a loop, in a try at the top level (try_finally_011), after a valid loop.
echo "not printed\n";
foreach ([1] as $x) {
    break 1;
}
try {
    break;
} finally {
}
