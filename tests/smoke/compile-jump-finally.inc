<?php
// included by compile-jump-finally.php: a break out of a finally block, in a function nobody calls (try_finally_008)
echo "not printed: the file doesn't run\n";
function never_called() {
    do {
        try {
            try {
            } finally {
                break;
            }
        } catch (Exception $e) {
        } finally {
        }
    } while (0);
}
