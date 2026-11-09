<?php
// Issue 055: a handler that fails (false, an exception) is disabled: not called again, what is written passes
// on. Output from a handler is lost, Deprecated. The ob_* functions' notices.
function h($b) { return $b; }
class K { static function m($b) { return $b; } function i($b) { return $b; } }
ob_start(function ($b) { return $b; }); ob_start('h'); ob_start('K::m'); ob_start([new K, 'i']); ob_start(['K', 'm']);
$l = ob_list_handlers(); while (ob_get_level()) ob_end_flush(); print_r($l);
$e = '';
ob_start(function ($b) use ($e) { echo $e; return $b; }); echo 'z'; ob_end_flush(); echo "\n";
ob_start(function ($b) { return 42; }); echo 'z'; ob_end_flush(); echo "\n";
ob_start(function ($b) { return []; }); echo 'z'; ob_end_flush(); echo "\n";
ob_start(function ($b) { return true; }); echo 'z'; ob_end_flush(); echo "|\n";
ob_start(function ($b) { return null; }); echo 'z'; ob_end_flush(); echo "|\n";
ob_start(function ($b) { return ''; }); echo 'z'; ob_end_flush(); echo "|\n";
$n = 0;
ob_start(function ($b) use (&$n) { $n++; throw new Exception("h$n"); });
echo 'y';
try { ob_flush(); } catch (Exception $e) { echo '[caught ', $e->getMessage(), ']'; }
echo 'w';
try { ob_end_flush(); } catch (Exception $e) { echo '[caught2 ', $e->getMessage(), ']'; }
echo ' level=', ob_get_level(), " n=$n\n";
// false: disabled, the buffer passes on raw; later writes go through
ob_start(); ob_start(function ($b, $m) { static $n = 0; $n++; echo ""; return $n == 1 ? false : "[$b]"; });
echo 'a'; ob_flush(); echo 'b'; ob_flush(); echo 'c';
$l = ob_get_level(); ob_end_flush(); $o = ob_get_clean(); echo "<$o> $l ", ob_get_level(), "\n";
// output inside the handler
ob_start(function ($b) { echo "noise"; return strtoupper($b); }, 0, PHP_OUTPUT_HANDLER_STDFLAGS);
echo 'q'; ob_end_flush(); echo "\n";
ob_start(function ($b) { echo "noise"; return strtoupper($b); });
echo 'q2'; ob_flush(); echo 'q3'; ob_end_flush(); echo "\n";
// notices
var_dump(ob_end_clean(), ob_end_flush(), ob_clean(), ob_flush(), ob_get_clean(), ob_get_flush());
// notices written into the buffer, without the script's path
set_error_handler(function ($n, $m) { echo "[$n] $m\n"; return true; });
ob_start(null, 0, 0); echo 'nr';
var_dump(ob_end_clean(), ob_clean(), ob_flush());
$c = ob_get_clean(); var_dump($c, ob_get_level());
$c = ob_get_flush(); var_dump($c, ob_get_level());
print_r(ob_list_handlers());
