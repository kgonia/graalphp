<?php
// ext/standard/dns.c: WordPress's wp_http_validate_url() resolves a URL's host with gethostbyname() (WooCommerce's
// marketplace promotions, in WP-Cron)
set_error_handler(function ($type, $message, $file, $line) {
    echo "warning $type: $message (line $line)\n";
    return true;
});
var_dump(gethostbyname('localhost'), gethostbyname('127.0.0.1'), gethostbyname('10.1.2.3'));
var_dump(gethostbyname('no-such-host.invalid'), gethostbyname(''));
var_dump(gethostbynamel('no-such-host.invalid'), gethostbynamel('192.168.0.1'));
var_dump(gethostbyname(str_repeat('a', 256)) === str_repeat('a', 256), gethostbynamel(str_repeat('a', 256)));
var_dump(gethostbyaddr('not an address'), gethostbyaddr('1.2.3'), gethostbyaddr('01.2.3.4'), gethostbyaddr('1:2:3'));
var_dump(gethostbyaddr('192.0.2.1'), gethostbyaddr('2001:db8::1'));
foreach (['gethostbyname', 'gethostbynamel', 'gethostbyaddr'] as $f) {
    try {
        $f("a\0b");
    } catch (ValueError $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}
