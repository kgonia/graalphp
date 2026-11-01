<?php
// What an include's frame shows in traces: php-src's zend_fetch_debug_backtrace names the included file as the
// frame's argument only when a frame inside the file comes before it in the trace (getTraceAsString,
// debug_backtrace, debug_print_backtrace), whatever DEBUG_BACKTRACE_IGNORE_ARGS says.
ob_start(function ($text) {
    // "/" after the directory: Truffle PHP runs the smoke tests on the host; a path cut short differs by its length
    return preg_replace("/'[^']*\.\.\.'/", "'...'", str_replace([__DIR__ . "\\", __DIR__ . "/"], "DIR/", $text));
});
function base($path) {
    return preg_replace('~.*[/\\\\]~', '', $path);
}
function show($trace) {
    foreach ($trace as $i => $frame) {
        echo "  $i: ", base($frame['file'] ?? '-'), ":", $frame['line'] ?? '-', " ", $frame['function'],
            array_key_exists('args', $frame) ? "(" . implode(", ", array_map(fn($a) => is_string($a) ? base($a)
                : gettype($a), $frame['args'])) . ")" : " no args", "\n";
    }
}
function thrower($x) {
    throw new RuntimeException("from function");
}
function backtrace($options) {
    show(debug_backtrace($options));
}
function printed() {
    debug_print_backtrace();
}

foreach (['top', 'function', 'nested-top', 'nested-function'] as $mode) {
    $GLOBALS['mode'] = $mode;
    try {
        require __DIR__ . "/include-trace.inc";
    } catch (Exception $e) {
        echo "$mode:\n", $e->getTraceAsString(), "\n";
        show($e->getTrace());
    }
}
foreach (['bt-top', 'bt-function', 'bt-function-noargs', 'print-top', 'print-function', 'bt-limit'] as $mode) {
    $GLOBALS['mode'] = $mode;
    echo "$mode:\n";
    include __DIR__ . "/include-trace.inc";
}
$GLOBALS['mode'] = 'once';
echo "once:\n";
include_once __DIR__ . "/include-trace-once.inc";
require_once __DIR__ . "/include-trace-require-once.inc";
function inFunction() {
    $GLOBALS['mode'] = 'bt-function';
    echo "from a function:\n";
    require __DIR__ . "/include-trace.inc";
}
inFunction();
// without arguments in exceptions' traces, an include's file is still named
ini_set('zend.exception_ignore_args', 1);
$GLOBALS['mode'] = 'nested-function';
try {
    require __DIR__ . "/include-trace.inc";
} catch (Exception $e) {
    echo "ignore_args:\n", $e->getTraceAsString(), "\n";
    show($e->getTrace());
}
ini_set('zend.exception_ignore_args', 0);
// a Throwable made in an included file, thrown outside it
$made = require __DIR__ . "/include-trace-make.inc";
echo "made:\n", $made->getTraceAsString(), "\n";
