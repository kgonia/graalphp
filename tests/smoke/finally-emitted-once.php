<?php
// Issue 067: A finally reached by return or continue and by falling through is one piece of code: one anonymous
// class, one compile-time deprecation.
function f($r) { try { if ($r) return; } finally { $GLOBALS['c'][] = get_class(new class {}); } }
f(true);
f(false);
var_dump($GLOBALS['c'][0] === $GLOBALS['c'][1]);
for ($i = 0; $i < 2; $i++) {
    try { if ($i) continue; } finally { if (false) `true`; }
}
echo "done\n";
