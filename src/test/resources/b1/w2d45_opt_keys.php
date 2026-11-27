<?php
// Pass 1 review P1-9 and P2-10: float keys losing precision report it on every evaluation; a parameter skipped by
// named arguments reports its default in func_get_args() and in traces.
echo "start\n";
function floatKeys() { return [1.5 => 'a', 2.0 => 'b', 'c']; }
var_dump(floatKeys());
var_dump(floatKeys());
function nested() { return ['outer' => [2.5 => 'x']]; }
var_dump(nested());

function named($a = 1, $b = 2) { var_dump(func_get_args()); throw new Exception("trace"); }
try { call_user_func_array('named', ['b' => 10]); }
catch (Exception $e) { var_dump($e->getTrace()[0]['args']); }
echo "end\n";
