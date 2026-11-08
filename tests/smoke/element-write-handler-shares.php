<?php
// Issue 059: The error handler copies the array while an element write is under way ("Undefined array
// key"): PHP drops the write, and the copy doesn't see it either.
set_error_handler(function () { global $a, $c; $c = $a; return true; });
$a = ['x' => 1];
$a['y'] .= 'z';
var_dump($a, $c);
