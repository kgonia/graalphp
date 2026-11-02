<?php
// Paths rewritten to allocate less (wiki/gc.md): trim's character lists, joins with ints, whole-file reads.
set_error_handler(function ($no, $message) { echo "warning: $message
"; return true; });

// trim, ltrim, rtrim: short lists (searched), ranges and '.' (masked), the default
foreach (["/a/b//", "\/x/\\", "..a..", " \t\n x \0\x0B", "abcxyz", "", "////", "a.b.c"] as $s) {
    var_dump(trim($s, "/"), rtrim($s, "/\\"), ltrim($s, "./"), trim($s, "a..c"), trim($s, "."), trim($s, ".."),
        trim($s, "a..cx..z"), trim($s, " \t\n\r\0\x0B"), trim($s), trim($s, ""), rtrim($s, "z..a"),
        trim($s, "a.."), ucwords($s, "..-"), addcslashes($s, "a..c.."));
}
var_dump(trim("\xff\xfeA\xff", "\xff"), trim("\x80abc\x80", "\x80..\xff"));

// interpolation and implode with ints
$n = -42; $m = PHP_INT_MIN; $z = 0; $big = PHP_INT_MAX;
var_dump("a{$n}b{$m}c{$z}d{$big}", "$z", "x$n", implode(",", [1, -1, 0, PHP_INT_MIN, PHP_INT_MAX, "s"]),
    implode("", [10, 20]), implode(", ", ["only"]), implode("--", []), "{$n}{$n}");
$parts = [];
for ($i = -12; $i <= 12; $i += 3) { $parts[] = $i * 1000003; }
var_dump(implode("|", $parts));

// file_get_contents of a whole file, at offsets, with a length; the stream's end after stream_get_contents
$f = tempnam(sys_get_temp_dir(), "ap");
$data = str_repeat("0123456789abcdef", 1500) . "tail";
file_put_contents($f, $data);
var_dump(file_get_contents($f) === $data, strlen(file_get_contents($f)), file_get_contents($f, false, null, 5, 7),
    strlen(file_get_contents($f, false, null, -10)), file_get_contents($f, false, null, 0, 0));
file_put_contents($f, "");
var_dump(file_get_contents($f));
file_put_contents($f, "abc");
$h = fopen($f, "rb");
var_dump(fread($h, 1), stream_get_contents($h), feof($h), ftell($h), fread($h, 10), feof($h));
fclose($h);
$h = fopen($f, "rb");
var_dump(stream_get_contents($h), feof($h), ftell($h));
fclose($h);
unlink($f);

// array_merge: made at its size; the lone non-empty array itself (shared: writes through either copy), its pointer
$list = [1, 2, 3]; $strs = ["a" => 1, "b" => [2]]; $mixed = ["a" => 1, 5 => 2]; $empty = [];
next($strs); next($list);
$m1 = array_merge($strs, []); $m2 = array_merge([], $list); $m3 = array_merge($mixed, []); $m4 = array_merge($empty, $empty);
var_dump(current($m1), current($m2), current($m3), $m4, array_merge(), array_merge($list), array_merge($strs));
$m1["a"] = 9; $m1["b"][] = 3; $m2[] = 4;
var_dump($strs, $m1, $list, $m2);
var_dump(array_merge([1, 2], [3.5, "x"], ["k" => 1, 7 => 2], [[1]]), array_merge([1, 2], [3, 4]),
    array_merge(["x" => 1], ["x" => 2, 3]));
try { array_merge([1], 2); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
// ints, then a string key: the hash takes the ints over
$ints = [1, 2, 3]; $ints["s"] = 4; $none = []; $none["s"] = 1; $none[] = 2;
var_dump($ints, $none);
