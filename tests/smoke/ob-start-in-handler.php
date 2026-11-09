<?php
// Issue 055: ob_start() while a handler runs is a fatal error.
ob_start(function ($b) { ob_start(); return $b; });
echo 'x'; ob_end_flush(); echo "after\n";
