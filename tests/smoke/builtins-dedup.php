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
