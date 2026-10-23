<?php
// base64_decode as php_base64_decode_impl, strict and not: padding anywhere, data after padding, whitespace,
// bytes outside the alphabet, truncated groups, wrong padding lengths; base64_encode round trips.
$inputs = ['', 'YQ==', 'YQ=', 'YQ', 'Y', 'YWI=', 'YWI', 'YWJj', 'YWJjZA==', 'YQ==YQ==', 'YQ=a', 'YQ==a', 'Y=Q=',
    '=YQ', '==', '=', '===', 'YQ===', 'YQ====', 'YWI==', 'YWJj=', 'YWJj==', 'Y Q = =', "YQ\n==", "Y\tQ\r\n==",
    "YQ\x0B==", "YQ\x0C==", '@@', 'YQ@@', 'Y-Q_', 'YW Jj ZA', '!YQ==', "YQ==\x00", "\x00YQ==", "YQ\xff==",
    'SGVsbG8gV29ybGQh', 'SGVsbG8gV29ybGQ', 'SGVsbG8gV29ybG', 'SGVsbG8gV29yb', 'SGVsbG8=V29ybGQh',
    '/+/+', '_-_-', '////', '++++', 'AAAA', 'AA==AA==', 'QUJD\nREVG\nR0hJ', '  QUJD  ', 'QUJD====', 'QU=JD'];
foreach ($inputs as $s) {
    echo json_encode($s), ' ', var_export(bin2hex((string) base64_decode($s)), true), ' ',
        var_export(base64_decode($s) === false, true), ' ';
    $strict = base64_decode($s, true);
    echo $strict === false ? 'false' : bin2hex($strict), "\n";
}
$bytes = '';
for ($i = 0; $i < 256; $i++) {
    $bytes .= chr($i);
}
for ($len = 0; $len < 20; $len++) {
    $chunk = substr($bytes, 100, $len);
    $encoded = base64_encode($chunk);
    echo $encoded, ' ', var_export(base64_decode($encoded, true) === $chunk, true), ' ',
        var_export(base64_decode(rtrim($encoded, '='), true) === $chunk, true), "\n";
}
echo base64_encode($bytes), "\n", bin2hex(base64_decode(str_repeat('ab*', 50))), "\n";
