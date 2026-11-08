<?php
// Issue 070: The body of a declare block runs.
declare(ticks=1) { echo "hi\n"; }
echo "end\n";
