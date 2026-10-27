<?php
// Finding T2 (open), as seen by the PHP stream: libcurl writes a CURLOPT_FILE transfer through the C FILE* that
// php_stream_cast made of the stream, so the stream's own position doesn't count those bytes (ftell() gives 8).
// Truffle PHP writes them through the PHP stream (13).
$out = sys_get_temp_dir() . '/curl-order-' . bin2hex(random_bytes(4));
$src = sys_get_temp_dir() . '/curl-src-' . bin2hex(random_bytes(4));
file_put_contents($src, "BODY\n");
$fp = fopen($out, 'w');
$ch = curl_init('file://' . (DIRECTORY_SEPARATOR === '\\' ? '/' : '') . str_replace('\\', '/', $src));
curl_setopt($ch, CURLOPT_FILE, $fp);
$mh = curl_multi_init();
curl_multi_add_handle($mh, $ch);
do {
    curl_multi_exec($mh, $running);
} while ($running);
fwrite($fp, "TRAILER\n");
var_dump(ftell($fp));
fclose($fp);
echo file_get_contents($out);
unlink($out);
unlink($src);
