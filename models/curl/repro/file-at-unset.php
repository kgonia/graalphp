<?php
// Finding T2 (open): php-src writes a CURLOPT_FILE transfer through a C FILE* (php_stream_cast to stdio), whose
// buffer curl_exec flushes but curl_multi_exec doesn't; the handle holds the stream, so the bytes reach the file when
// the handle (the stream's last reference) is destroyed. Truffle PHP writes each chunk to the PHP stream at once.
$out = sys_get_temp_dir() . '/curl-out-' . bin2hex(random_bytes(4));
$fp = fopen($out, 'w');
$ch = curl_init('file://' . (DIRECTORY_SEPARATOR === '\\' ? '/' : '') . str_replace('\\', '/', __FILE__));
curl_setopt($ch, CURLOPT_FILE, $fp);
$mh = curl_multi_init();
curl_multi_add_handle($mh, $ch);
do {
    curl_multi_exec($mh, $running);
} while ($running);
clearstatcache();
var_dump(filesize($out) === filesize(__FILE__));
curl_multi_remove_handle($mh, $ch);
unset($fp);
clearstatcache();
var_dump(filesize($out) === filesize(__FILE__));
unset($ch);
clearstatcache();
var_dump(filesize($out) === filesize(__FILE__));
unlink($out);
