<?php
// parse_url as php-src's php_url_parse_ex2: schemes, host:port without a scheme, relative-scheme URLs, file:
// URLs and Windows drive letters, user info, IPv6 hosts, ports (limits, strtol quirks, non-ASCII digits), empty
// query and fragment, control characters, and each component on its own.
$urls = [
    'http://user:pass@example.com:8080/path/to/file.php?query=1&b[]=2#frag',
    'https://example.com', 'https://example.com/', 'http://example.com:80', 'http://example.com:',
    'http://example.com:65535/', 'http://example.com:65536/', 'http://example.com:0/', 'http://example.com:00080/x',
    'http://example.com:123456/', 'http://example.com:8a/', 'http://example.com:a8/', 'http://example.com:-1/',
    'http://example.com:+80/', 'http://example.com: 80/', "http://h:\u{663}/", 'http://h:٣٤/x',
    'host:80', 'host:80/path', 'host:8080?x=1', 'host:123456', 'host:', 'host:abc', 'example.com:80#f',
    '1a:rest', 'a1:rest', '1:2', '+a:b', 'a+b.c-d:x', 'ab_c:x', 'a b:c', 'mailto:user@example.com',
    'zlib:foo', 'urn:isbn:0451450523', 'news:comp.lang.php', 'tel:+1-816-555-1212', 'javascript:alert(1)',
    'file:///etc/passwd', 'file:///c:/dir/file.txt', 'file://localhost/etc/x', 'file:/etc/x', 'FILE:///C:/x',
    'file://', 'file:///', 'file:', 'http://', 'http:///', 'http:///example.com', 'http:', 'http:/', 'http://:80',
    'http://@host', 'http://user@host', 'http://user:@host', 'http://:pass@host', 'http://a@b@host/x',
    'http://u:p:q@host:81', 'http://[::1]', 'http://[::1]:8080/x', 'http://[2001:db8::7]/c=GB?objectClass?one',
    'http://[::1', 'http://::1]', '//example.com/path', '//example.com:81', '//', '///', '//:80', '/path?q#f',
    'path/only', '?q=1', '#f', '?', '#', '?#', '/p?#', '/p#?', '', ':', ':80', ':80/x', ':abc', '::', 'a:',
    'http://example.com/?', 'http://example.com/#', 'http://example.com?#', 'http://example.com#x?y',
    'http://example.com/p?q?r#f#g', "http://ex\x00ample.com/p\x01a\x7Fth?q\x1Fx#f\x0Ag", "ht\x01tp://x",
    'http://user:pass@:80', 'http://exa mple.com/a b', 'http://example.com:80:90/', 'http://example.com/:80',
    'x://h:12345', 'x://h:1234567', 's3://bucket/key', 'svn+ssh://host/repo', 'http://host/p:80',
    'example.com/:80', 'a:65535', 'a:65536', 'a:0', 'a:000001', 'a:1/b:2', 'C:\\Windows\\path', 'c:/x',
    'http://example.com/path%20with?q=%20#%20', "http://ex\xc3\xa9.com/\xff", '/:80', '//user@:80',
];
foreach ($urls as $url) {
    echo json_encode($url), ' => ';
    var_dump(parse_url($url));
}
$components = [PHP_URL_SCHEME, PHP_URL_HOST, PHP_URL_PORT, PHP_URL_USER, PHP_URL_PASS, PHP_URL_PATH,
    PHP_URL_QUERY, PHP_URL_FRAGMENT, -1, -5];
foreach (['http://u:p@h:81/p?q#f', 'host:80', '//h', 'http:///x', '/p', 'http://h:0'] as $url) {
    foreach ($components as $c) {
        echo $url, ' ', $c, ' ';
        var_dump(parse_url($url, $c));
    }
}
foreach ([8, 100, -2] as $bad) {
    try {
        var_dump(parse_url('http://h', $bad));
    } catch (\ValueError $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}
