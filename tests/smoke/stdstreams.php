<?php
// php://stdout, php://output, php://memory, php://temp and php://input. (What goes to stderr isn't compared: the
// reference container delivers it apart from stdout.)
echo "start\n";
$out = fopen('php://stdout', 'w');
$err = fopen('php://stderr', 'w');
$output = fopen('php://output', 'w');
var_dump(fwrite($out, "to stdout\n"), fwrite($output, "to output\n"));
ob_start();
echo "A";
fwrite($out, "B");
fwrite($output, "C");
$buffered = ob_get_clean();
echo "\n[$buffered]\n";
var_dump(file_put_contents('php://output', "put output\n"), file_put_contents('php://stdout', "put stdout\n"));
var_dump(fwrite($err, ""), @fread($out, 1), @fread($output, 3), feof($output), ftell($output));
var_dump(fseek($output, 0, SEEK_CUR), fseek($output, 2, SEEK_CUR), @ftruncate($output, 0), fstat($output));
var_dump(flock($output, LOCK_SH), flock($out, LOCK_SH));
$meta = stream_get_meta_data($output);
var_dump($meta);

echo "-- memory\n";
$m = fopen('php://memory', 'r+');
fwrite($m, "abcdef");
var_dump(fseek($m, -1), ftell($m), fwrite($m, "W"), fseek($m, 10), ftell($m), fwrite($m, "Z"));
rewind($m);
var_dump(bin2hex(stream_get_contents($m)), feof($m));
var_dump(fseek($m, -100, SEEK_END), ftell($m), fwrite($m, "V"), fseek($m, 2, SEEK_END), ftell($m), fseek($m, 1, 7));
rewind($m);
var_dump(fgets($m, 2), ftell($m), fwrite($m, "Q"));
rewind($m);
var_dump(bin2hex(stream_get_contents($m)));
var_dump(ftruncate($m, 2), ftell($m), fread($m, 5), feof($m), ftruncate($m, 5));
rewind($m);
var_dump(bin2hex(fread($m, 10)), feof($m), fstat($m), stream_get_meta_data($m), flock($m, LOCK_SH));
$r = fopen('php://memory', 'r');
var_dump(@fwrite($r, "x"), fstat($r)['mode'], ftruncate($r, 3), stream_get_meta_data($r)['mode']);
$a = fopen('php://memory', 'a');
fwrite($a, "12");
rewind($a);
fwrite($a, "34");
rewind($a);
var_dump(stream_get_contents($a), stream_get_meta_data($a)['mode']);
$t = fopen('php://temp/maxmemory:100', 'r+');
fwrite($t, "line 1\nline 2\n");
rewind($t);
var_dump(fgets($t), fgets($t), fgets($t), feof($t), stream_get_meta_data($t));
var_dump(fopen('PHP://Memory', 'r+') !== false, @fopen('php://nothing', 'r'));

echo "-- input\n";
$in = fopen('php://input', 'r');
var_dump(fread($in, 5), feof($in), @fwrite($in, "x"), fseek($in, 3), ftell($in), fstat($in), @ftruncate($in, 0));
var_dump(stream_get_meta_data($in));
var_dump(file_get_contents('php://input'));

$tmp = tmpfile();
var_dump(fwrite($tmp, "temporary"), rewind($tmp), fread($tmp, 4), fstat($tmp)['size']);
$meta = stream_get_meta_data($tmp);
unset($meta['uri']);
var_dump($meta);
fclose($tmp);
try { fwrite($tmp, "x"); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
echo "end\n";
