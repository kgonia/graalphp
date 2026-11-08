<?php
// Issue 065: A lock held by a handle that went out of scope is released with it.
$file = sys_get_temp_dir() . '/smoke-flock-' . getmypid();
function f($file) { $fp = fopen($file, 'c'); var_dump(flock($fp, LOCK_EX)); }
f($file);
$fp = fopen($file, 'c');
var_dump(flock($fp, LOCK_EX | LOCK_NB, $wb), $wb);
fclose($fp);
unlink($file);
