<?php
global $absent;
echo $absent === null, "\n";
function bindMissing() { global $missing; echo $missing === null, "\n"; }
bindMissing();
echo $missing === null, "\n";
function rebind() { global $x; $y = 7; eval('global $x; $x = $y;'); }
$x = 1;
rebind();
echo $x, "\n";
$y = 9;
eval('$x = &$y;');
echo $x, "\n";
$y = 10;
echo $x, "\n";
