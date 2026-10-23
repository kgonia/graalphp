<?php
// flock(): shared and exclusive locks per open stream, LOCK_NB and $would_block, conversions, release on fclose;
// file_put_contents with LOCK_EX and FILE_APPEND.
$dir = sys_get_temp_dir() . '/truffle-smoke-flock-' . bin2hex(random_bytes(4));
mkdir($dir);
$f = "$dir/lock.txt";
file_put_contents($f, "0123456789");

$a = fopen($f, 'r');
$b = fopen($f, 'r');
$c = fopen($f, 'r+');
var_dump(flock($a, LOCK_SH), flock($b, LOCK_EX | LOCK_NB, $wb), $wb);
var_dump(flock($b, LOCK_SH | LOCK_NB, $wb), $wb, flock($c, LOCK_SH | LOCK_NB));
// an upgrade that would block drops the shared lock it had (Linux flock)
var_dump(flock($a, LOCK_EX | LOCK_NB, $wb), $wb);
var_dump(flock($b, LOCK_UN), flock($c, LOCK_UN), flock($c, LOCK_EX | LOCK_NB, $wb), $wb);
var_dump(flock($a, LOCK_SH | LOCK_NB, $wb), $wb, flock($c, LOCK_EX | LOCK_NB), flock($c, LOCK_SH | LOCK_NB));
var_dump(flock($a, LOCK_EX | LOCK_NB, $wb), $wb, flock($c, LOCK_UN), flock($a, LOCK_EX | LOCK_NB, $wb), $wb);
// the data stays readable and writable while locked
var_dump(fread($b, 4), fwrite($c, "AB"), file_get_contents($f));
fclose($a);
var_dump(flock($b, LOCK_EX | LOCK_NB, $wb), $wb, flock($c, LOCK_SH | LOCK_NB, $wb), $wb);
var_dump(flock($b, LOCK_UN), flock($b, LOCK_UN), flock($b, 7), flock($c, LOCK_SH));
fclose($b);
fclose($c);
try { flock(fopen($f, 'r'), 0); } catch (ValueError $e) { echo $e->getMessage(), "\n"; }
try { flock(fopen($f, 'r'), LOCK_NB); } catch (ValueError $e) { echo $e->getMessage(), "\n"; }
$w = fopen($f, 'w');
var_dump(flock($w, LOCK_SH), flock($w, LOCK_EX), flock($w, LOCK_UN));
fclose($w);
var_dump(flock(fopen('php://memory', 'r+'), LOCK_SH), flock(fopen('php://output', 'w'), LOCK_EX));
var_dump(flock(fopen('php://temp', 'r+'), LOCK_SH), flock(fopen('php://input', 'r'), LOCK_SH));

echo "-- file_put_contents\n";
var_dump(file_put_contents($f, "first", LOCK_EX), file_get_contents($f));
var_dump(file_put_contents($f, "+more", FILE_APPEND | LOCK_EX), file_get_contents($f));
var_dump(file_put_contents($f, ['x', 'y'], LOCK_EX), file_get_contents($f));
var_dump(file_put_contents("$dir/new.txt", "created", LOCK_EX), file_get_contents("$dir/new.txt"));
var_dump(@file_put_contents('php://memory', "m", LOCK_EX), @file_put_contents('php://memory', "m", FILE_APPEND | LOCK_EX));
var_dump(@file_put_contents('php://output', "o", LOCK_EX));
// a shared lock elsewhere doesn't stop a plain write, only another lock
$s = fopen($f, 'r');
var_dump(flock($s, LOCK_SH), file_put_contents($f, "plain"), file_get_contents($f));
fclose($s);
var_dump(file_put_contents($f, "after", LOCK_EX), file_get_contents($f));

unlink($f);
unlink("$dir/new.txt");
var_dump(rmdir($dir));
