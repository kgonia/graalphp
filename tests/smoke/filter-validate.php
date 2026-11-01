<?php
// ext/filter's validators (WooCommerce: filter_var with FILTER_VALIDATE_INT, _BOOLEAN, _EMAIL, _URL, _IP and its
// range flags, _REGEXP, FILTER_NULL_ON_FAILURE), with php-src's ext/filter/tests edge cases
ob_start(function ($text) {
    return str_replace([__FILE__, __DIR__], ["FILE", "DIR"], $text);
});

function show($label, ...$values) {
    echo $label, ": ";
    var_dump(...$values);
}

echo "-- int\n";
foreach (["123", " 123 ", "\t\n 42\r\v", "+0", "-0", "0", "00", "-1", "+7", "007", "1e3", "12abc", "", "  ",
          "9223372036854775807", "9223372036854775808", "-9223372036854775808", "-9223372036854775809",
          "123456789012345678901", "1.0", "0x1A", "0X1a", "0x", "0o17", "017", "0b11", "- 1", "٣"] as $v) {
    show(json_encode($v), filter_var($v, FILTER_VALIDATE_INT), filter_var($v, FILTER_VALIDATE_INT,
        FILTER_FLAG_ALLOW_HEX | FILTER_FLAG_ALLOW_OCTAL));
}
show("hex", filter_var("0xffffffffffffffff", FILTER_VALIDATE_INT, FILTER_FLAG_ALLOW_HEX),
    filter_var("0x10000000000000000", FILTER_VALIDATE_INT, FILTER_FLAG_ALLOW_HEX),
    filter_var("0xG", FILTER_VALIDATE_INT, FILTER_FLAG_ALLOW_HEX));
show("octal", filter_var("01777777777777777777777", FILTER_VALIDATE_INT, FILTER_FLAG_ALLOW_OCTAL),
    filter_var("02000000000000000000000", FILTER_VALIDATE_INT, FILTER_FLAG_ALLOW_OCTAL),
    filter_var("0o", FILTER_VALIDATE_INT, FILTER_FLAG_ALLOW_OCTAL), filter_var("08", FILTER_VALIDATE_INT, FILTER_FLAG_ALLOW_OCTAL));
show("range", filter_var("5", FILTER_VALIDATE_INT, ["options" => ["min_range" => 1, "max_range" => 10]]),
    filter_var("11", FILTER_VALIDATE_INT, ["options" => ["min_range" => 1, "max_range" => 10]]),
    filter_var("0", FILTER_VALIDATE_INT, ["options" => ["min_range" => "1"]]),
    filter_var("-5", FILTER_VALIDATE_INT, ["options" => ["max_range" => -5.9]]),
    filter_var("x", FILTER_VALIDATE_INT, ["options" => ["default" => 3]]),
    filter_var("x", FILTER_VALIDATE_INT, ["options" => ["default" => 3], "flags" => FILTER_NULL_ON_FAILURE]),
    filter_var("99", FILTER_VALIDATE_INT, ["options" => ["max_range" => 10, "default" => [1]]]));
show("scalars", filter_var(12, FILTER_VALIDATE_INT), filter_var(1.0, FILTER_VALIDATE_INT),
    filter_var(1.5, FILTER_VALIDATE_INT), filter_var(true, FILTER_VALIDATE_INT), filter_var(false, FILTER_VALIDATE_INT),
    filter_var(null, FILTER_VALIDATE_INT), filter_var(null, FILTER_VALIDATE_INT, FILTER_NULL_ON_FAILURE));

echo "-- bool\n";
foreach (["1", "0", "true", "TRUE", "False", "on", "On", "off", "yes", "YES", "no", "No", "", " ", " yes ",
          "\ttrue\n", "2", "y", "n", "none", "truee", "-1", "0.0", " off  "] as $v) {
    show(json_encode($v), filter_var($v, FILTER_VALIDATE_BOOLEAN), filter_var($v, FILTER_VALIDATE_BOOL,
        FILTER_NULL_ON_FAILURE));
}
show("bool scalars", filter_var(true, FILTER_VALIDATE_BOOL), filter_var(0, FILTER_VALIDATE_BOOL),
    filter_var(null, FILTER_VALIDATE_BOOL, FILTER_NULL_ON_FAILURE), filter_var([], FILTER_VALIDATE_BOOL,
    FILTER_NULL_ON_FAILURE), filter_var("no", FILTER_VALIDATE_BOOL, ["options" => ["default" => "d"]]));

echo "-- float\n";
foreach (["1.5", " 1.5 ", "1", "-1.5e3", "+.5", "5.", ".", "1e", "1e+", "e5", "1.5.5", "1,000", "1,000.25",
          "1.000,25", "1,00", "12,345,678.9", "1'000", "1e400", "-1e400", "1e-400", "0.0", "-0", "0e5",
          "99999999999999999999", "1.5abc", "", "1 000", "0x1A", "١"] as $v) {
    show(json_encode($v), filter_var($v, FILTER_VALIDATE_FLOAT), filter_var($v, FILTER_VALIDATE_FLOAT,
        FILTER_FLAG_ALLOW_THOUSAND));
}
show("decimal", filter_var("1,5", FILTER_VALIDATE_FLOAT, ["options" => ["decimal" => ","]]),
    filter_var("1.000,5", FILTER_VALIDATE_FLOAT, ["options" => ["decimal" => ","], "flags" => FILTER_FLAG_ALLOW_THOUSAND]),
    filter_var("1 000.5", FILTER_VALIDATE_FLOAT, ["options" => ["thousand" => " "], "flags" => FILTER_FLAG_ALLOW_THOUSAND]),
    filter_var("1~000~000", FILTER_VALIDATE_FLOAT, ["options" => ["thousand" => "~"], "flags" => FILTER_FLAG_ALLOW_THOUSAND]),
    filter_var("2.5", FILTER_VALIDATE_FLOAT, ["options" => ["min_range" => 1, "max_range" => 2]]),
    filter_var("2", FILTER_VALIDATE_FLOAT, ["options" => ["min_range" => "1.5", "max_range" => 2]]),
    filter_var(1.25, FILTER_VALIDATE_FLOAT), filter_var(7, FILTER_VALIDATE_FLOAT));
foreach ([["decimal" => ".."], ["decimal" => ""], ["thousand" => ""]] as $options) {
    try {
        var_dump(filter_var("1.5", FILTER_VALIDATE_FLOAT, ["options" => $options]));
    } catch (ValueError $e) {
        echo get_class($e), ": ", $e->getMessage(), "\n";
    }
}
show("empty skips the options", filter_var("", FILTER_VALIDATE_FLOAT, ["options" => ["decimal" => ".."]]));

echo "-- regexp\n";
show("regexp", filter_var("abc", FILTER_VALIDATE_REGEXP, ["options" => ["regexp" => "/^a/"]]),
    filter_var("xbc", FILTER_VALIDATE_REGEXP, ["options" => ["regexp" => "/^a/"]]),
    filter_var("ÄB", FILTER_VALIDATE_REGEXP, ["options" => ["regexp" => "/^\\p{Lu}+$/u"]]),
    filter_var("\xff", FILTER_VALIDATE_REGEXP, ["options" => ["regexp" => "/./u"]]),
    filter_var("x", FILTER_VALIDATE_REGEXP, ["options" => ["regexp" => "/y/", "default" => "def"]]));
preg_match("/a/", "a");
show("bad pattern", filter_var("abc", FILTER_VALIDATE_REGEXP, ["options" => ["regexp" => "/abc"]]), preg_last_error());
show("bad modifier", filter_var("abc", FILTER_VALIDATE_REGEXP, ["options" => ["regexp" => "/abc/Q"]]));
show("bad group", filter_var("abc", FILTER_VALIDATE_REGEXP, ["options" => ["regexp" => "/(abc/"]]));
try {
    filter_var("abc", FILTER_VALIDATE_REGEXP);
} catch (ValueError $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
try {
    filter_var("abc", FILTER_VALIDATE_REGEXP, ["options" => ["regexp" => 5]]);
} catch (ValueError $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}

echo "-- domain\n";
foreach (["example.com", "example.com.", "a", "", ".", "a..b", "-a.com", "a-.com", "a-", "a-.", "a_b.com", "xn--bcher-kva.ch",
          str_repeat("a", 63) . ".com", str_repeat("a", 64) . ".com", str_repeat("a.", 126) . "a", str_repeat("a.", 127),
          str_repeat("a.", 127) . "a", "1.2.3.4", "ex ample.com", "a.b-.c", "a.-b.c", "bücher.ch"] as $v) {
    show(json_encode($v), filter_var($v, FILTER_VALIDATE_DOMAIN), filter_var($v, FILTER_VALIDATE_DOMAIN, FILTER_FLAG_HOSTNAME));
}

echo "-- url\n";
foreach (["http://example.com", "https://example.com/path?q=1#frag", "http://example.com.", "http://ex_ample.com",
          "http://-example.com", "http://example", "http://localhost:8080/", "http://127.0.0.1/", "http://[::1]/",
          "http://[2001:db8::1]:80/x", "http://[::1", "http://[zz::1]/", "http://user:pass@example.com/",
          "http://us%20er@example.com/", "http://us%2Ger@example.com/", "http://us%G2er@example.com/", "http://us{er@example.com/",
          "http://user:p@ss@example.com/", "ftp://ftp.example.com/file", "mailto:someone@example.com", "news:comp.lang.php",
          "file:///etc/passwd", "foo:bar", "//example.com/path", "example.com", "/path/only", "http://", "http:///path",
          "http://exa mple.com", "http://example.com/ä", "http://example.com/\x01", "javascript:alert(1)",
          "http://example.com:65536/", "http://example.com:abc/", "http://a.b", "HTTP://EXAMPLE.COM", "http://1.2.3",
          "https://xn--bcher-kva.ch/", "http://example.com/?a[]=1", "http://example.com/#a#b", "telnet://192.0.2.16:80/",
          "ldap://[2001:db8::7]/c=GB?objectClass?one", "urn:oasis:names:specification:docbook:dtd:xml:4.1.2",
          "http://www.example.com/a b", "irc://irc.example.com", "http://x:y", "http://a.com:80:90"] as $v) {
    show(json_encode($v), filter_var($v, FILTER_VALIDATE_URL), filter_var($v, FILTER_VALIDATE_URL, FILTER_FLAG_PATH_REQUIRED),
        filter_var($v, FILTER_VALIDATE_URL, FILTER_FLAG_QUERY_REQUIRED));
}
show("parse_url parser", filter_var("http://example.com", FILTER_VALIDATE_URL, ["options" => ["uri_parser_class" => "parse_url"]]),
    filter_var("http://example.com", FILTER_VALIDATE_URL, ["options" => ["uri_parser_class" => null]]));
try {
    filter_var("http://example.com", FILTER_VALIDATE_URL, ["options" => ["uri_parser_class" => "nope"]]);
} catch (ValueError $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}

echo "-- email\n";
foreach (["user@example.com", "first.last@sub.example.co.uk", "user+tag@example.com", "user@localhost", "user@example",
          "@example.com", "user@", "user", "us er@example.com", "\"us er\"@example.com", "user@[127.0.0.1]",
          "user@[IPv6:2001:db8::1]", "user@[300.0.0.1]", "a..b@example.com", ".a@example.com", "a.@example.com",
          "user@-example.com", "user@example-.com", "user@ex_ample.com", "üser@example.com", "user@exämple.com",
          "user@xn--exmple-cua.com", str_repeat("a", 64) . "@example.com", str_repeat("a", 65) . "@example.com",
          "a@" . str_repeat("b", 63) . ".com", "a@" . str_repeat("b", 64) . ".com",
          "a@" . str_repeat("b.", 160) . "com", "!#$%&'*+-/=?^_`{|}~@example.com", "user@EXAMPLE.COM", "USER@example.com",
          "user@123.com", "user@example.123", "user@example.c0m", "\"\\\"\"@example.com", "user@example.com\n",
          "user@example.com "] as $v) {
    show(json_encode($v), filter_var($v, FILTER_VALIDATE_EMAIL), filter_var($v, FILTER_VALIDATE_EMAIL, FILTER_FLAG_EMAIL_UNICODE));
}

echo "-- ip\n";
$flags = [0, FILTER_FLAG_IPV4, FILTER_FLAG_IPV6, FILTER_FLAG_NO_PRIV_RANGE, FILTER_FLAG_NO_RES_RANGE,
          FILTER_FLAG_GLOBAL_RANGE, FILTER_FLAG_IPV4 | FILTER_FLAG_IPV6 | FILTER_FLAG_NO_PRIV_RANGE | FILTER_FLAG_NO_RES_RANGE];
foreach (["127.0.0.1", "192.168.0.1", "10.1.2.3", "172.16.0.1", "172.32.0.1", "8.8.8.8", "0.0.0.0", "0.1.2.3",
          "100.64.0.1", "169.254.1.1", "192.0.0.1", "192.0.2.1", "192.88.99.1", "198.18.0.1", "198.51.100.1",
          "203.0.113.1", "224.0.0.1", "240.0.0.1", "255.255.255.255", "256.1.1.1", "1.2.3", "1.2.3.4.5", "01.2.3.4",
          "1.2.3.04", "1.2.3.0", " 1.2.3.4", "1.2.3.4 ", "1.2..3", "::1", "::", "::ffff:127.0.0.1", "::ffff:8.8.8.8",
          "2001:db8::1", "2001:4860:4860::8888", "fe80::1", "fc00::1", "fd12:3456::1", "64:ff9b::1.2.3.4",
          "64:ff9b::8.8.8.8", "2002::1", "100::1", "2001::1", "2001:0:1::1", "1:2:3:4:5:6:7:8", "1:2:3:4:5:6:7:8:9",
          "1::2::3", ":1:2:3:4:5:6:7", "1:2:3:4:5:6:7:", "1:2:3:4:5:6::", "::2:3:4:5:6:7:8", "1:2:3:4:5:6:7::",
          "12345::1", "g::1", "1:2:3:4:5:6:1.2.3.4", "1:2:3:4:5:1.2.3.4", "::1.2.3.4", "1.2.3.4::", "::1.2.3",
          "a:b:c:d:e:f:0:1", "A:B:C:D:E:F:0:1", "localhost", "", ":", "1.2.3.4:80"] as $v) {
    echo json_encode($v), ":";
    foreach ($flags as $flag) {
        $result = filter_var($v, FILTER_VALIDATE_IP, $flag);
        echo " ", $result === false ? "-" : "ok";
    }
    echo "\n";
}

echo "-- mac\n";
foreach (["01-23-45-67-89-ab", "01:23:45:67:89:AB", "0123.4567.89ab", "01-23-45-67-89", "01-23-45:67-89-ab",
          "0g-23-45-67-89-ab", "01-23-45-67-89-ab-cd", "0123.4567.89aG", "01234567890a", "", "01_23_45_67_89_ab",
          "0123-4567-89ab"] as $v) {
    show(json_encode($v), filter_var($v, FILTER_VALIDATE_MAC));
}
show("separator", filter_var("01-23-45-67-89-ab", FILTER_VALIDATE_MAC, ["options" => ["separator" => "-"]]),
    filter_var("01-23-45-67-89-ab", FILTER_VALIDATE_MAC, ["options" => ["separator" => ":"]]),
    filter_var("0123.4567.89ab", FILTER_VALIDATE_MAC, ["options" => ["separator" => "."]]));
try {
    filter_var("01-23-45-67-89-ab", FILTER_VALIDATE_MAC, ["options" => ["separator" => "--"]]);
} catch (ValueError $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}

echo "-- FILTER_THROW_ON_FAILURE\n";
foreach ([["abc", FILTER_VALIDATE_INT], ["a\0b", FILTER_VALIDATE_BOOL], ["1.2.3", FILTER_VALIDATE_IP],
          ["nope", FILTER_VALIDATE_EMAIL], ["x", FILTER_VALIDATE_URL], [["a"], FILTER_VALIDATE_INT],
          [new stdClass(), FILTER_VALIDATE_INT], ["\xff", FILTER_VALIDATE_FLOAT]] as [$value, $filter]) {
    try {
        var_dump(filter_var($value, $filter, FILTER_THROW_ON_FAILURE));
    } catch (Filter\FilterFailedException $e) {
        echo get_class($e), " (", get_parent_class($e), ", line ", $e->getLine(), "): ", $e->getMessage(), "\n";
    }
}
show("thrown default", filter_var("12", FILTER_VALIDATE_INT, ["flags" => FILTER_THROW_ON_FAILURE, "options" => ["default" => 1]]));
try {
    filter_var("x", FILTER_VALIDATE_INT, ["flags" => FILTER_THROW_ON_FAILURE, "options" => ["default" => 1]]);
} catch (Filter\FilterException $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
try {
    filter_var("1", FILTER_VALIDATE_INT, FILTER_THROW_ON_FAILURE | FILTER_NULL_ON_FAILURE);
} catch (ValueError $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
try {
    filter_var("x", FILTER_VALIDATE_INT, ["flags" => FILTER_THROW_ON_FAILURE | FILTER_REQUIRE_ARRAY]);
} catch (Filter\FilterFailedException $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}
var_dump(is_subclass_of("Filter\\FilterFailedException", "Filter\\FilterException"), class_exists("Filter\\FilterException"));
