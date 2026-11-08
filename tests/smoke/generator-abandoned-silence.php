<?php
// Issue 060: A generator destroyed while suspended inside @: PHP restores error_reporting.
$g = (function () { $x = @(yield 1); })();
$g->current();
unset($g);
echo error_reporting(), "\n";
