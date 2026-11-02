<?php
// ext/filter's sanitizers (WooCommerce: FILTER_SANITIZE_FULL_SPECIAL_CHARS, _NUMBER_INT, _URL; FILTER_UNSAFE_RAW
// with flags), with php-src's ext/filter/tests edge cases
ob_start(function ($text) {
    return str_replace([__FILE__, __DIR__], ["FILE", "DIR"], $text);
});

$samples = ["", "plain", "<b>bold</b> & 'single' \"double\"", "a\0b\x01c\x1f\x7f\x80\xff`d", "é ü € 日本",
            "1,234.5e-6 +-x", "user(at)exa mple.com <x@y.z>", "http://exa mple.com/ä?q=1&r=<2>#f",
            "&amp; &lt; &#39; &foo;", "<", "< b>", "<a href='x'>link</a>", "5 < 6 > 4", "<<>>", "\xc3\x28", "%41+\\'"];
$filters = ["FILTER_UNSAFE_RAW" => FILTER_UNSAFE_RAW, "FILTER_SANITIZE_ENCODED" => FILTER_SANITIZE_ENCODED,
            "FILTER_SANITIZE_SPECIAL_CHARS" => FILTER_SANITIZE_SPECIAL_CHARS,
            "FILTER_SANITIZE_FULL_SPECIAL_CHARS" => FILTER_SANITIZE_FULL_SPECIAL_CHARS,
            "FILTER_SANITIZE_EMAIL" => FILTER_SANITIZE_EMAIL, "FILTER_SANITIZE_URL" => FILTER_SANITIZE_URL,
            "FILTER_SANITIZE_NUMBER_INT" => FILTER_SANITIZE_NUMBER_INT,
            "FILTER_SANITIZE_NUMBER_FLOAT" => FILTER_SANITIZE_NUMBER_FLOAT,
            "FILTER_SANITIZE_ADD_SLASHES" => FILTER_SANITIZE_ADD_SLASHES,
            // FILTER_SANITIZE_STRING, deprecated: its id
            "513" => 513];
$flags = [0, FILTER_FLAG_STRIP_LOW, FILTER_FLAG_STRIP_HIGH, FILTER_FLAG_STRIP_BACKTICK, FILTER_FLAG_ENCODE_LOW,
          FILTER_FLAG_ENCODE_HIGH, FILTER_FLAG_ENCODE_AMP, FILTER_FLAG_NO_ENCODE_QUOTES, FILTER_FLAG_EMPTY_STRING_NULL,
          FILTER_FLAG_STRIP_LOW | FILTER_FLAG_ENCODE_HIGH | FILTER_FLAG_ENCODE_AMP];
foreach ($filters as $name => $filter) {
    echo "-- $name\n";
    foreach ($samples as $sample) {
        $results = [];
        foreach ($flags as $flag) {
            $results[] = filter_var($sample, $filter, $flag);
        }
        echo json_encode(bin2hex($sample)), ": ", json_encode(array_map(fn($r) => is_string($r) ? bin2hex($r) : $r,
            $results)), "\n";
    }
}

echo "-- number_float flags\n";
foreach ([0, FILTER_FLAG_ALLOW_FRACTION, FILTER_FLAG_ALLOW_THOUSAND, FILTER_FLAG_ALLOW_SCIENTIFIC,
          FILTER_FLAG_ALLOW_FRACTION | FILTER_FLAG_ALLOW_THOUSAND | FILTER_FLAG_ALLOW_SCIENTIFIC] as $flag) {
    var_dump(filter_var("-1,234.56e+7E8 abc.", FILTER_SANITIZE_NUMBER_FLOAT, $flag));
}

echo "-- default filter\n";
var_dump(filter_var("x"), filter_var(12), filter_var(1.5), filter_var(true), filter_var(false), filter_var(null),
    filter_var(""), filter_var("", FILTER_DEFAULT, FILTER_FLAG_EMPTY_STRING_NULL), filter_var([1]),
    filter_var(-0.0), filter_var(1e100), filter_var(0.1 + 0.2));

echo "-- full special chars\n";
var_dump(filter_var("café <b> \"q\" 'a' &amp; &copy &#x41; \xe9", FILTER_SANITIZE_FULL_SPECIAL_CHARS),
    filter_var("'q'", FILTER_SANITIZE_FULL_SPECIAL_CHARS, FILTER_FLAG_NO_ENCODE_QUOTES),
    filter_var("\x80abc", FILTER_SANITIZE_FULL_SPECIAL_CHARS), filter_var("café", FILTER_SANITIZE_FULL_SPECIAL_CHARS));

echo "-- string\n";
var_dump(filter_var("<p>Hello</p> <script>alert('x')</script> world", 513),
    filter_var("<p>x</p>", 513, FILTER_FLAG_EMPTY_STRING_NULL), filter_var("<>", 513, FILTER_FLAG_EMPTY_STRING_NULL),
    filter_var("a < b and c > d", 513), filter_var("a <b and c", 513), filter_var("\"><img src=x onerror=1>", 514));

echo "-- arrays\n";
$nested = ["a" => " <x> ", "b" => ["c" => "1&2", "d" => [3, null, true, 4.5]], 7 => "seven"];
var_dump(filter_var($nested, FILTER_SANITIZE_SPECIAL_CHARS, FILTER_REQUIRE_ARRAY),
    filter_var($nested, FILTER_SANITIZE_NUMBER_INT, FILTER_FORCE_ARRAY),
    filter_var("x<", FILTER_SANITIZE_SPECIAL_CHARS, FILTER_FORCE_ARRAY),
    filter_var($nested, FILTER_SANITIZE_SPECIAL_CHARS), $nested);

echo "-- filter_list, filter_id\n";
var_dump(count(filter_list()));
foreach (filter_list() as $name) {
    echo $name, " ", filter_id($name), "\n";
}
var_dump(filter_id("nope"), filter_id(""), filter_id("int\0suffix"), filter_id("INT"), filter_id(123),
    FILTER_VALIDATE_BOOL === FILTER_VALIDATE_BOOLEAN, FILTER_DEFAULT === FILTER_UNSAFE_RAW, extension_loaded("filter"),
    function_exists("filter_var"), in_array("filter", get_loaded_extensions()));
