<?php
// Issue 060: A generator destroyed while suspended inside @: PHP restores error_reporting (live range ZEND_LIVE_SILENCE).
// Destroyed at unset only with -Dphp.rc.destructors=true until destructors are on by default; else at request end.
$g = (function () { $x = @(yield 1); })(); $g->current(); unset($g); echo error_reporting(), "\n";
$g = (function () { foreach ([1,2] as $v) { $x = @(yield $v); } })(); $g->current(); unset($g); echo error_reporting(), "\n";
$g = (function () { try { $x = @(yield 1); } finally { echo "fin ", error_reporting(), "\n"; } })(); $g->current(); unset($g); echo error_reporting(), "\n";
$g = (function () { $x = @(@(yield 1)); })(); $g->current(); unset($g); echo error_reporting(), "\n";
$g = (function () { $x = @(yield 1); echo "after\n"; })(); $g->current(); $g->next(); echo error_reporting(), "\n"; unset($g); echo error_reporting(), "\n";
