<?php
// A script that ends with a stream open and locked: the stream closes at the end of the request, releasing the
// lock, so the next request (the next context, in the runner's three-context mode) can take it again.
$f = sys_get_temp_dir() . '/truffle-smoke-leftopen-' . md5(__FILE__) . '.lock';
$h = fopen($f, 'c+');
var_dump(flock($h, LOCK_EX | LOCK_NB, $wouldBlock), $wouldBlock);
$other = fopen($f, 'r');
var_dump(flock($other, LOCK_SH | LOCK_NB, $wouldBlock), $wouldBlock);
fwrite($h, "left open\n");
register_shutdown_function(function () use ($f) {
    unlink($f);
    echo "done\n";
});
