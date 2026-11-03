<?php
// Issue 006: a goto into a loop, and a goto out of a finally block to after it (try_finally_005).
echo "not printed\n";
function into_loop() {
    goto inside;
    while (true) {
        inside:
        break;
    }
}
