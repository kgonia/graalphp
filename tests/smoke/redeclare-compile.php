<?php
// Redeclaring a function at the top level: E_COMPILE_ERROR before the file runs; and strlen, a built-in.
ob_start(function ($text) {
    // "/" after the directory: Truffle PHP runs the smoke tests on the host
    return str_replace([__DIR__ . "\\", __DIR__ . "/"], "DIR/", $text);
});
register_shutdown_function(function () {
    $e = error_get_last();
    echo "shutdown: ", $e['type'], " ", $e['line'], "\n";
});
function inner() {
    include __DIR__ . "/redeclare-included.inc";
}
inner();
echo "not reached\n";
