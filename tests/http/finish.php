<?php
// fastcgi_finish_request(): php-fpm's only (tests/http/fpm.sh). The output buffers end and the response goes out
// with what was written and the headers set; the script goes on, its later output and headers go nowhere (its
// return values: FastCgiConnectionTest).
header('X-Exists: ' . var_export(function_exists('fastcgi_finish_request'), true));
if (!function_exists('fastcgi_finish_request')) {
    return;
}
header('X-Before: 1');
ob_start();
echo "buffered\n";
fastcgi_finish_request();
header('X-After: 1');
echo "after\n";
fastcgi_finish_request();
