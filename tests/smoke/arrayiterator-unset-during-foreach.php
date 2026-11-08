<?php
// Issue 061: offsetUnset and offsetSet on an ArrayIterator while foreach walks it.
$it = new ArrayIterator(['a' => 1, 'b' => 2, 'c' => 3]);
foreach ($it as $k => $v) { echo $k; if ($k === 'a') $it->offsetUnset('b'); }
echo "\n";
$it = new ArrayIterator(['a' => 1, 'b' => 2, 'c' => 3]);
foreach ($it as $k => $v) { echo $k; if ($k === 'b') { $it->offsetUnset('a'); $it->offsetSet('d', 4); } }
echo "\n";
