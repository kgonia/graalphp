<?php
// Issue 006: php-src checks jumps when it compiles a file, before any of it runs, in functions never called too.
// Included: the main script has run up to the include; the included file doesn't run at all.
ob_start(function ($text) {
    // "/" after the directory: Truffle PHP runs the smoke tests on the host
    return str_replace([__DIR__ . "\\", __DIR__ . "/"], "DIR/", $text);
});
register_shutdown_function(function () {
    $e = error_get_last();
    echo "shutdown: ", $e['type'], " ", $e['line'], "\n";
});
echo "before\n";
include __DIR__ . '/compile-jump-finally.inc';
echo "not reached\n";
