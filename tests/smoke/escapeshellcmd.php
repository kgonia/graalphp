<?php
// escapeshellcmd(): php_escape_shell_cmd, which mail() runs its additional_params through (PHPMailer's -f sender)
ob_start(function ($text) {
    return str_replace([__FILE__, __DIR__], ["FILE", "DIR"], $text);
});
foreach ([
    "-f 'me@example.com' -t",
    "a#b&c;d`e|f*g?h~i<j>k^l(m)n[o]p{q}r\$s\\t",
    "line\nbreak\r\tand tab",
    "'paired' \"paired\" 'lone \"lone",
    "'a \"b' c\"",
    "\"'\"",
    "'",
    "\"x\" \"",
    // multi-byte characters of C.UTF-8 stay whole; a byte that starts none is dropped
    "caf\xC3\xA9 \xE2\x82\xAC \xF0\x9F\x98\x80",
    "a\xE2\x82",
    "a\xE2",
    "\x80\xBFz",
    "\xED\xA0\x80z",         // a surrogate
    "\xC0\x80z\xC1\xBFz",    // overlong two bytes
    "\xE0\x80\x80z",         // overlong three bytes
    "\xF0\x8F\xBF\xBFz",     // overlong four bytes
    "\xF4\x90\x80\x80z",     // past U+10FFFF: glibc takes it
    "\xF8\x88\x80\x80\x80z", // five bytes
    "\xFC\x84\x80\x80\x80\x80z",
    "\xF8\x80\x80\x80\x80z", // overlong five bytes
    "\xFE\xFFz",
    "\xE2\x28\xA1z",
    "",
] as $command) {
    echo bin2hex($command), " => ", bin2hex(escapeshellcmd($command)), "\n";
}
var_dump(escapeshellcmd(12), escapeshellcmd(1.5), escapeshellcmd(true));
foreach (["a\0b", [], null] as $bad) {
    try {
        var_dump(escapeshellcmd($bad));
    } catch (Throwable $e) {
        echo get_class($e), ": ", $e->getMessage(), "\n";
    }
}
try {
    escapeshellcmd(str_repeat("x", 2097150));
} catch (ValueError $e) {
    echo $e->getMessage(), "\n";
}
var_dump(strlen(escapeshellcmd(str_repeat("x", 2097149))));
