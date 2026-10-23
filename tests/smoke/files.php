<?php
// Plain-file streams: fopen modes, php-src's read buffer (fgets/fread/fseek/ftell/feof), file(), directories.
$dir = sys_get_temp_dir() . '/truffle-smoke-files-' . bin2hex(random_bytes(4));
mkdir($dir);
$f = "$dir/a.txt";

echo "-- modes\n";
file_put_contents($f, "hello");
$h = fopen($f, 'r'); var_dump(fread($h, 10), @fwrite($h, "x"), feof($h)); fclose($h);
$h = fopen($f, 'r+'); var_dump(fwrite($h, "X"), fread($h, 2), fwrite($h, "Y"), ftell($h)); fclose($h);
var_dump(file_get_contents($f));
$h = fopen($f, 'w'); var_dump(@fread($h, 3), feof($h), fwrite($h, "new"), ftell($h)); fclose($h);
var_dump(file_get_contents($f));
$h = fopen($f, 'w+'); var_dump(fread($h, 2), fwrite($h, "w+"), rewind($h), fread($h, 9), feof($h)); fclose($h);
$h = fopen($f, 'a'); var_dump(ftell($h), fwrite($h, "ab"), ftell($h), fseek($h, 1), fwrite($h, "Z"), ftell($h));
fclose($h);
var_dump(file_get_contents($f));
$h = fopen($f, 'a+'); var_dump(ftell($h), fread($h, 3), fwrite($h, "Q"), ftell($h), fread($h, 100), feof($h));
fclose($h);
var_dump(file_get_contents($f));
file_put_contents($f, "hello");
$h = fopen($f, 'c'); var_dump(ftell($h), fwrite($h, "J")); fclose($h);
$h = fopen($f, 'c+'); var_dump(fread($h, 2), fwrite($h, "LL"), ftell($h)); fclose($h);
var_dump(file_get_contents($f));
$h = fopen("$dir/x.txt", 'x+'); var_dump(fwrite($h, "xx"), rewind($h), fread($h, 9)); fclose($h);
var_dump(@fopen("$dir/x.txt", 'x'), @fopen("$dir/x.txt", 'x+'), file_get_contents("$dir/x.txt"));
var_dump(@fopen("$dir/none.txt", 'r'), @fopen("$dir/none/x", 'w'), @fopen($f, 'z'), @fopen($f, ''));
var_dump(fopen("file://$f", 'rb') !== false);

echo "-- reads\n";
file_put_contents($f, "a\n\nb\r\nlonger line\nc");
$h = fopen($f, 'r');
var_dump(fgets($h, 1), fgets($h, 2), ftell($h), fgets($h, 3), fgets($h), fgetc($h), ftell($h));
var_dump(fseek($h, 2, SEEK_CUR), ftell($h), fgets($h, 5), fread($h, 3), feof($h));
var_dump(fseek($h, -2, SEEK_END), fgets($h), feof($h), fgets($h), feof($h), fread($h, 1), fgetc($h));
var_dump(fseek($h, -100), ftell($h), fseek($h, 100), ftell($h), fread($h, 1), feof($h));
rewind($h);
while (($line = fgets($h)) !== false) {
    var_dump($line, feof($h));
}
var_dump(feof($h), stream_get_contents($h), stream_get_contents($h, 4, 1), stream_get_contents($h, -1, 15));
fclose($h);
try { fread($h, 1); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
$h = fopen($f, 'r');
try { fread($h, 0); } catch (ValueError $e) { echo $e->getMessage(), "\n"; }
try { fgets($h, 0); } catch (ValueError $e) { echo $e->getMessage(), "\n"; }
try { stream_get_contents($h, -2); } catch (ValueError $e) { echo $e->getMessage(), "\n"; }
try { ftruncate($h, -1); } catch (ValueError $e) { echo $e->getMessage(), "\n"; }
try { fread("x", 1); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
// the read buffer keeps what it read before the file changed
var_dump(fread($h, 2));
file_put_contents($f, "0123456789");
var_dump(fread($h, 3), ftell($h));
fclose($h);
$h = fopen($f, 'r+');
var_dump(fgets($h, 4), fwrite($h, "W"), ftell($h), fread($h, 2));
fseek($h, 3);
var_dump(ftruncate($h, 1), ftell($h), fwrite($h, "Z"));
fclose($h);
var_dump(bin2hex(file_get_contents($f)));
$h = fopen($f, 'r+'); var_dump(ftruncate($h, 6), ftruncate(fopen($f, 'r'), 1)); fclose($h);
var_dump(bin2hex(file_get_contents($f)));
$meta = stream_get_meta_data(fopen($f, 'rb'));
unset($meta['uri']);
var_dump($meta);
var_dump(fstat(fopen($f, 'r'))['size']);
$big = str_repeat("0123456789abcdef", 2000) . "\nend";
file_put_contents($f, $big);
$h = fopen($f, 'r');
var_dump(strlen(fgets($h)), fgets($h), feof($h), strlen(stream_get_contents($h, -1, 0)));
rewind($h);
var_dump(strlen(fread($h, 20000)), ftell($h), strlen(fread($h, 20000)), feof($h));
fclose($h);

echo "-- file()\n";
foreach (["", "\n", "\r\n", "x", "x\r", "a\r\n\r\nb\r", "\r\nq", "a\n\n", "\n\nz\r\n"] as $content) {
    file_put_contents($f, $content);
    echo json_encode([file($f), file($f, FILE_IGNORE_NEW_LINES), file($f, FILE_SKIP_EMPTY_LINES),
        file($f, FILE_IGNORE_NEW_LINES | FILE_SKIP_EMPTY_LINES)]), "\n";
}
var_dump(@file("$dir/none.txt"));

echo "-- whole files\n";
var_dump(file_put_contents($f, ['a', 'b', 1]), file_put_contents($f, "c", FILE_APPEND),
    file_get_contents($f), file_put_contents("$dir/e.txt", "", FILE_APPEND), file_exists("$dir/e.txt"));
$m = fopen('php://memory', 'w+'); fwrite($m, "from a stream"); rewind($m);
var_dump(file_put_contents($f, $m), file_get_contents($f));
var_dump(readfile($f), md5_file($f), sha1_file($f), @readfile("$dir/none"), @md5_file("$dir/none"));

echo "-- files and directories\n";
mkdir("$dir/sub");
var_dump(@unlink("$dir/sub"), @rmdir($f), @copy($f, "$dir/sub"), @copy("$dir/sub", "$dir/g"), @mkdir("$dir/sub"),
    @mkdir("$dir/sub", 0777, true), @mkdir("$dir/p/q"), mkdir("$dir/p/q", 0777, true), is_dir("$dir/p/q"));
var_dump(rmdir("$dir/p/q"), rmdir("$dir/p"), @unlink("$dir/none"), @rmdir("$dir/none"), file_exists("$dir/g"));
var_dump(rename($f, "$dir/b.txt"), file_exists($f), copy("$dir/b.txt", "$dir/c.txt"), file_get_contents("$dir/c.txt"));
var_dump(touch("$dir/t", 1000000000), filemtime("$dir/t"), filesize("$dir/t"), fileatime("$dir/t"));
var_dump(touch("$dir/t", 1000000000, 1100000000), filemtime("$dir/t"), fileatime("$dir/t"));
var_dump(@filesize("$dir/none"), @filemtime("$dir/none"), @stat("$dir/none"), is_readable("$dir/none"),
    is_writable("$dir/t"), is_readable("$dir/t"), is_link("$dir/t"), decoct(fileperms($dir) & 0170000));
$st = stat("$dir/c.txt");
var_dump(count($st), $st['size'], $st[7], filesize("$dir/c.txt"));
var_dump(scandir($dir), scandir($dir, 1));
$names = [];
$dh = opendir($dir);
while (($name = readdir($dh)) !== false) {
    $names[] = $name;
}
sort($names);
var_dump($names, readdir($dh), fread($dh, 1), get_resource_type($dh));
rewinddir($dh);
var_dump(readdir($dh) !== false);
closedir($dh);
try { readdir(fopen("$dir/t", 'r')); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
var_dump(glob("$dir/*.txt") == ["$dir/b.txt", "$dir/c.txt", "$dir/x.txt"]);

foreach (glob("$dir/*") as $path) {
    is_dir($path) ? rmdir($path) : unlink($path);
}
var_dump(rmdir($dir), file_exists($dir));
