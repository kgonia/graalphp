<?php
// Issue 006: a try without catch or finally, in a method nobody calls (try_finally_004).
echo "not printed\n";
class NeverUsed {
    function m() {
        try {
            echo "3";
        }
    }
}
