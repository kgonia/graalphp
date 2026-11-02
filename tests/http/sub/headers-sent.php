<?php
// The SAPI's headers: unsent until the first byte of output (an empty echo sends nothing), as WordPress's
// wp-cron.php asks headers_sent() (its update checks' warnings are notices until the headers are out); after it,
// header(), header_remove(), setcookie() and http_response_code() warn where the output started, and change nothing.
set_error_handler(function ($type, $message, $file, $line) {
    echo "warning $type: ", str_replace(__FILE__, 'FILE', $message), " (line $line)\n";
    return true;
});
$seen = [headers_sent()];
ignore_user_abort(true);
header('Expires: Wed, 11 Jan 1984 05:00:00 GMT');
header('Cache-Control: no-cache, must-revalidate, max-age=0');
echo '';
print '';
$seen[] = headers_sent($file, $line);
$seen[] = [$file, $line];
$seen[] = headers_list();
header_remove('X-Powered-By');
$seen[] = headers_list();
$seen[] = http_response_code(201);
echo "first output\n";
var_dump($seen);
var_dump(headers_sent($file, $line), $file === __FILE__, $line);
header('X-Late: 1');
header_remove('Expires');
var_dump(setcookie('late', '1'), http_response_code(404), http_response_code());
// the default Content-type joined the list when the headers went out
var_dump(headers_list());
