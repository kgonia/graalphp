<?php
// Finding T1 (open): php-src destroys a CurlHandle when its last reference goes, and curl_easy_cleanup writes
// CURLOPT_COOKIEJAR there. Truffle PHP frees the handle after the GC finds it unreachable, at the context's next curl
// call or at its end, so the jar isn't there yet.
$jar = sys_get_temp_dir() . '/curl-jar-' . bin2hex(random_bytes(4));
$ch = curl_init();
curl_setopt($ch, CURLOPT_COOKIEJAR, $jar);
curl_setopt($ch, CURLOPT_COOKIELIST, "Set-Cookie: a=b; domain=example.com; path=/");
var_dump(file_exists($jar));
unset($ch);
var_dump(file_exists($jar));
if (file_exists($jar)) {
    echo preg_replace('/^#.*\n/m', '', file_get_contents($jar));
    unlink($jar);
}
