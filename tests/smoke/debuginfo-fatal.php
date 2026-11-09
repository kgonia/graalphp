<?php
// Issue 064: __debugInfo returning null is deprecated; an exception out of it is a fatal error, the exception
// reported first as a warning, and the fatal error without a backtrace.
class N { function __debugInfo() { return null; } }
var_dump(new N);
print_r(new N); echo "\n";
class H { function __debugInfo() { throw new Exception("x"); } }
function f($o) { print_r($o); }
try { f(new H); } catch (Exception $e) { echo "caught\n"; }
echo "not reached\n";
