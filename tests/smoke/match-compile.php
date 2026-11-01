<?php
// A match with two default arms: zend_compile_match's E_COMPILE_ERROR, at the second one's line, raised when the
// included file is compiled, before any of it runs.
ob_start(function ($text) {
    // "/" after the directory: Truffle PHP runs the smoke tests on the host
    return str_replace([__DIR__ . "\\", __DIR__ . "/"], "DIR/", $text);
});
register_shutdown_function(function () {
    $e = error_get_last();
    echo "shutdown: ", $e['type'], " ", $e['line'], " ", $e['message'], "\n";
});
function inner() {
    include __DIR__ . "/match-compile.inc";
}
echo "before\n";
inner();
echo "not reached\n";
