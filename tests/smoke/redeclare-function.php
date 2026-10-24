<?php
// Redeclaring a function when the declaration runs: E_ERROR, "previously declared in".
ob_start(function ($text) {
    return str_replace(__FILE__, "FILE", $text);
});
register_shutdown_function(function () {
    $e = error_get_last();
    echo "shutdown: ", $e['type'], " ", $e['message'], " ", $e['line'], " ", count($e['trace'] ?? []), "\n";
});

function declared() {
}

if (true) {
    echo "before\n";
    function declared() {
    }
}
echo "not reached\n";
