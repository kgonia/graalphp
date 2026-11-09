<?php
// Issue 070: The body of a declare block runs.
declare(ticks=1) { echo "hi\n"; }
declare(ticks=1);
echo "after\n";
function f() { declare(ticks=1) { echo "in f\n"; return 5; } }
var_dump(f());
declare(ticks=1) { function g() { return "g"; } }
echo g(), "\n";
goto inside;
echo "skipped\n";
declare(ticks=1) { inside: echo "label\n"; }
for ($i = 0; $i < 2; $i++) { declare(ticks=1) { if ($i) break; echo "loop $i\n"; } }
try { echo h(), "\n"; } catch (Error $e) { echo $e->getMessage(), "\n"; }
declare(ticks=1) { function h() { return 'h'; } }
echo h(), "\n";
echo "end\n";
