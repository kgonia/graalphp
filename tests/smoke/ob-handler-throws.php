<?php
// Issue 055: A handler that throws: PHP removes the buffer and doesn't call the handler again.
$n = 0;
ob_start(function ($b) use (&$n) { $n++; throw new Exception("h$n"); });
echo 'y';
try { ob_end_flush(); } catch (Exception $e) { echo '[caught ', $e->getMessage(), ']'; }
echo ' level=', ob_get_level(), " n=$n\n";
