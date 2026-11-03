<?php
// Issue 003: the compile's early binding sees only the built-in classes and the classes of the file being
// compiled (ZEND_COMPILE_IGNORE_OTHER_FILES). A class whose inheritance check needs another file's class is
// left to the delayed binding at the load, which skips a name already taken; its statement then fails.
ob_start(function ($text) {
    return str_replace([__DIR__ . "\\", __DIR__ . "/"], "DIR/", $text);
});
register_shutdown_function(function () {
    $e = error_get_last();
    echo "shutdown: ", $e['type'] ?? '-', " ", $e['line'] ?? '-', "\n";
});
class Wide {}
class Narrow extends Wide {}
class Taken {}
echo "including\n";
require __DIR__ . '/class-early-binding-files.inc';
echo "not reached\n";
