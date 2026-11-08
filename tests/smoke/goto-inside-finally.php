<?php
// Issue 068: A goto inside a finally, run while a goto out of its try waits: both labels' code runs.
function h() { try { goto L; } finally { goto M; if (false) { M: echo "M "; } } if (false) { L: echo "L "; } }
h();
echo "\n";
