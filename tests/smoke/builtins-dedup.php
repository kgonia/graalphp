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

