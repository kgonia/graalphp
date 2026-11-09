<?php
// Issue 068: A goto inside a finally, run while a goto out of its try waits: both labels' code runs.
function h() { try { goto L; } finally { goto M; if (false) { M: echo "M "; } } if (false) { L: echo "L "; } }
h(); echo "\n";
function h2() { try { echo "t "; } finally { goto M; if (false) { M: echo "M "; } } echo "after "; if (false) { L: echo "L "; } }
h2(); echo "\n";
function h3() { try { goto L; } finally { if (false) { M: echo "M "; } echo "f "; } echo "skipped "; if (false) { L: echo "L "; } }
h3(); echo "\n";
function h4() { try { try { goto L; } finally { goto M; if (false) { M: echo "M1 "; } } } finally { goto N; if (false) { N: echo "N "; } } if (false) { L: echo "L "; } }
h4(); echo "\n";
function h5() { foreach ([1, 2] as $i) { try { if ($i == 1) continue; echo "i$i "; } finally { goto M; if (false) { M: echo "M$i "; } } } }
h5(); echo "\n";
