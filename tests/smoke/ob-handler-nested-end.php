<?php
// Issue 055: An output handler that ends its own buffer: PHP refuses output-buffer calls while a handler
// runs (a fatal error).
ob_start();
ob_start(function ($s) { static $n = 0; if (!$n++) @ob_end_clean(); return $s; });
echo 'x';
ob_end_flush();
$l = ob_get_level();
while (ob_get_level()) ob_end_flush();
echo " level=$l\n";
