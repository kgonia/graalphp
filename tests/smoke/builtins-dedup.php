<?php
// Built-ins whose hand-rolled helpers were replaced by the JDK's (HexFormat, one UTF-8 validator, PhpArray.pop,
// static patterns): the edge cases where a library version could differ from PHP.

echo "-- hex\n";
var_dump(bin2hex("\x00\x0f\xf0\xff abc"), bin2hex(""));
var_dump(@hex2bin("48656c6C6F"), @hex2bin(""), bin2hex(@hex2bin("00ff")));
var_dump(@hex2bin("abc"), @hex2bin("a"), @hex2bin("zz"), @hex2bin("0g"), @hex2bin(" 0"), @hex2bin("０１"),
    @hex2bin("ａｂ"), @hex2bin("\xff\xff"), @hex2bin("+1"), @hex2bin("0x"));

echo "-- digests\n";
var_dump(md5("hello"), sha1("hello"), md5(""), sha1(""));
var_dump(bin2hex(md5("hello", true)), bin2hex(sha1("", true)), strlen(md5("x", true)), strlen(sha1("x", true)));
var_dump(hash('crc32b', 'The quick brown fox jumped over the lazy dog.'), hash('crc32b', ''), hash('CRC32B', 'a'));
var_dump(bin2hex(hash('crc32b', 'abc', true)), strlen(hash('crc32b', 'abc', true)), bin2hex(hash('crc32b', '', true)));
var_dump(hash('sha256', 'abc'), hash('MD5', 'abc'), bin2hex(hash('sha512', 'abc', true)));
var_dump(hash_hmac('sha1', 'data', 'key'), bin2hex(hash_hmac('md5', 'data', '', true)), hash_hmac('sha256', '', ''));

echo "-- htmlspecialchars: UTF-8 validation (ENT_IGNORE drops, no flag returns '')\n";
$inputs = ["ok \xC3\xA9 \xE2\x82\xAC \xF0\x9F\x98\x80", "\xC0\xAF", "\xC1\xBF", "\xC2", "\xC2\x80", "\xDF\xBF",
    "\xE0\x9F\xBF", "\xE0\xA0\x80", "\xED\x9F\xBF", "\xED\xA0\x80", "\xED\xBF\xBF", "\xEE\x80\x80", "\xEF\xBF\xBF",
    "\xF0\x8F\xBF\xBF", "\xF0\x90\x80\x80", "\xF4\x8F\xBF\xBF", "\xF4\x90\x80\x80", "\xF5\x80\x80\x80", "\xFF",
    "\xE2\x82", "\xE2\x82<", "a\xF0\x9F\x98", "\x80<\xBF", "\xE2\x28\xA1&"];
foreach ($inputs as $s) {
    echo bin2hex(htmlspecialchars($s, ENT_QUOTES | ENT_IGNORE)), " ", bin2hex(htmlspecialchars($s, ENT_QUOTES)), "\n";
}

echo "-- entity tables per doctype\n";
foreach ([ENT_HTML401, ENT_XML1, ENT_XHTML, ENT_HTML5] as $doctype) {
    $table = get_html_translation_table(HTML_ENTITIES, ENT_QUOTES | $doctype);
    echo count($table), " ", md5(serialize($table)), " ", count(get_html_translation_table(HTML_SPECIALCHARS,
        ENT_QUOTES | $doctype)), "\n";
    $s = "&eacute; &hellip; &apos; &nbsp; &bogus; &#x41; &#65; &#x110000; &lt; &amp;amp;";
    echo bin2hex(html_entity_decode($s, ENT_QUOTES | $doctype)), "\n";
    echo bin2hex(htmlspecialchars_decode($s, ENT_QUOTES | $doctype)), "\n";
    echo htmlspecialchars($s, ENT_QUOTES | $doctype, 'UTF-8', false), "\n";
}

echo "-- array_pop through the built-in\n";
function pop_dynamic(array &$a) {
    return call_user_func_array('array_pop', [&$a]);
}
function show($label, $value) {
    echo $label, ": ", json_encode($value), "\n";
}
$a = [1, 2, 3];
show('popped', pop_dynamic($a));
$a[] = 9;
show('appended', $a);
$h = [5 => 'a', 'k' => 'b', 7 => 'c'];
next($h);
show('popped', pop_dynamic($h));
show('pointer', [current($h), key($h)]);
$h[] = 'd';
show('appended', $h);
$s = ['x' => 1, 3 => 2, 'y' => 3];
show('popped', pop_dynamic($s));
$s[] = 4;
show('appended', $s);
$e = [];
show('popped', pop_dynamic($e));
show('empty', $e);
$inner = [1, 2];
$outer = ['a' => $inner, 'b' => $inner];
$copy = $outer;
$got = pop_dynamic($outer);
$got[] = 3;
show('nested', [$got, $outer, $copy, $inner]);
$x = 1;
$r = [&$x, 2, &$x];
$v = pop_dynamic($r);
$v = 5;
show('ref', [$x, count($r)]);
$f = [1.5, 'two', null];
show('popped', pop_dynamic($f));
show('popped', pop_dynamic($f));
$f[] = 'again';
show('appended', $f);

echo "-- sprintf %f\n";
foreach ([0.0, 0.04, -1.005, 2.345, -2.345, 1e15, -123456.789, 0.5, -0.51, 1.5, 2.5, -7.25] as $v) {
    echo sprintf("[%.1f] [%.2f] [%08.2f] [%-8.1f] [%+.1f] [%+05.0f] [%F] [%.0f]", $v, $v, $v, $v, $v, $v, $v, $v), "\n";
}

echo "-- serialize floats\n";
foreach ([0.1, 1e100, -1.5e-7, NAN, INF, -INF, 1.0, 123456789012345678.0] as $v) {
    echo serialize($v), " ", var_export($v, true), " ", json_encode([$v], JSON_PARTIAL_OUTPUT_ON_ERROR), "\n";
}
var_dump(unserialize(serialize([1.5, -INF, 0.30000000000000004])));

echo "-- odds and ends\n";
var_dump(function_exists('function_exists_internal'), function_exists('md5'));
class Point {
    public function __construct(public $x = 0) {
    }
}
$p = new Point(1);
$q = new Point(1);
var_dump($p == $p, $p <=> $p, $p === $q, $p != new Point(2));
try {
    throw new InvalidArgumentException('bad', 3);
} catch (LogicException $e) {
    var_dump(get_class($e), $e->getMessage(), $e->getCode(), $e instanceof Exception, $e instanceof Throwable);
}
try {
    intdiv(1, 0);
} catch (Error $e) {
    var_dump(get_class($e), $e instanceof ArithmeticError);
}

echo "-- parse_url\n";
$urls = ["http://user:pw@example.com:8080/p/a?q=1#frag", "https://example.com", "//example.com/x", "mailto:a@b.c",
    "http://[::1]:80/", "http://h:/p", "http://h:65536/", "a1+.-b:rest", "/just/a/path?x", "?q", "#f", "x:٣/path",
    "http://h:0/", "foo:123abc", "http://h:08/x", "https://u@h.example:443"];
foreach ($urls as $u) {
    echo json_encode(parse_url($u)), " ", json_encode(parse_url($u, PHP_URL_PORT)), "\n";
}

echo "-- timezones\n";
foreach (["+02:00", "-0530", "+2", "+02", "-11:30", "+0200", "UTC", "Z", "Europe/Warsaw", "+1:30"] as $tz) {
    $d = new DateTime("2024-03-01 12:00:00", new DateTimeZone($tz));
    echo $tz, " => ", $d->format("c P"), " ", $d->getTimezone()->getName(), "\n";
}

echo "-- floats as strings\n";
foreach ([1.0, 0.1, 100.0, 1.5e20, 1e25, 1e-5, 123456789012345.0, 0.000123, -2.50, 3.0e14, 1e15, 7.0E-10, 1200.0,
             0.30000000000000004, 1 / 3] as $v) {
    echo $v, " ", var_export($v, true), " ", json_encode($v), " ", (string) ($v * 10), "\n";
}

echo "-- base64_decode\n";
foreach (["aGVsbG8=", "aGVsbG8", "aGVs bG8=", "ab", "abc", "abcd=", "YWJjZA", "YWJjZA=", ""] as $b) {
    echo bin2hex(base64_decode($b)), "\n";
}

