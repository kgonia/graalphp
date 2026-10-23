<?php
// file_get_contents with php-src's offset (from the end when negative) and length, and the path tests and
// realpath(), all through the context's file system.
$dir = sys_get_temp_dir() . '/fgc-' . getmypid();
mkdir($dir);
$f = "$dir/ten.txt";
file_put_contents($f, 'abcdefghij');
var_dump(file_get_contents($f), file_get_contents($f, false, null, 3), file_get_contents($f, false, null, -4),
    file_get_contents($f, false, null, 2, 3), file_get_contents($f, false, null, 0, 0),
    @file_get_contents($f, false, null, 20), @file_get_contents("$dir/none.txt"),
    file_get_contents($f, false, null, 0, null));
try {
    file_get_contents($f, false, null, 0, -1);
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}
var_dump(file_exists($f), file_exists($dir), is_file($dir), is_dir($dir), is_file($f),
    realpath("$dir/../" . basename($dir) . "/./ten.txt") === realpath($f), realpath("$dir/nope"),
    realpath('') === getcwd(), file_exists(''), is_dir('.'));
unlink($f);
rmdir($dir);
var_dump(file_exists($dir));
