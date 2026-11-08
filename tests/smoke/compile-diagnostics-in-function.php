<?php
// Issue 071: Compile-time diagnostics in a function body come when the file compiles, also for a function never
// called.
function never() { if (false) `true`; }
function later() { if (false) `true`; }
echo "start\n";
later();
echo "end\n";
