<?php
// Redeclaring a class, an interface, or a built-in class: E_COMPILE_ERROR, named by what was declared first.
ob_start(function ($text) {
    return str_replace(__FILE__, "FILE", $text);
});
register_shutdown_function(function () {
    $e = error_get_last();
    echo "shutdown: ", $e['type'], " ", $e['message'], "\n";
});

interface Shape {
}

echo "before\n";
if (true) {
    class Shape {
    }
}
