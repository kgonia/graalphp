<?php
// The error model: trigger_error's display, error_reporting and @, the error handler stack, error_get_last,
// the error ini directives, then an uncaught exception with an exception handler and shutdown functions.
// Displayed errors go through the output buffers, so the paths in them are replaced here.
ob_start(function ($text) {
    return str_replace([__FILE__, __DIR__], ["FILE", "DIR"], $text);
});

trigger_error("a notice");
trigger_error("a warning", E_USER_WARNING);
trigger_error("deprecated", E_USER_DEPRECATED);
$e = error_get_last();
echo $e['type'], " ", $e['message'], " ", $e['line'], "\n";
error_clear_last();
var_dump(error_get_last());

$old = error_reporting(E_ALL & ~E_USER_NOTICE);
trigger_error("not shown: notice");
trigger_error("shown: warning", E_USER_WARNING);
error_reporting($old);
@trigger_error("silenced");
echo error_get_last()['message'], "\n";
var_dump(@error_reporting());

function handler($no, $str, $file, $line) {
    echo "handler: $no $str ", $file === __FILE__ ? "here" : $file, ":$line ", error_reporting(), "\n";
    return $no !== E_USER_WARNING;
}
var_dump(set_error_handler('handler'));
trigger_error("handled");
trigger_error("handled, then shown", E_USER_WARNING);
@trigger_error("handled under @");
set_error_handler(function () {
    echo "inner handler\n";
    trigger_error("raised in a handler", E_USER_NOTICE);
    return true;
}, E_USER_NOTICE);
trigger_error("to the inner one");
trigger_error("not the inner one's level", E_USER_WARNING);
restore_error_handler();
trigger_error("back to the first");
restore_error_handler();
restore_error_handler();
trigger_error("no handler");

var_dump(ini_get('display_errors'), ini_get('error_reporting'), ini_get('nonexistent'), ini_set('nonexistent', 1));
var_dump(ini_set('html_errors', '1'));
trigger_error("<b>html</b>", E_USER_WARNING);
ini_set('error_prepend_string', '[');
ini_set('error_append_string', ']');
trigger_error("wrapped");
ini_restore('html_errors');
trigger_error("text again");
ini_set('error_prepend_string', '');
ini_set('error_append_string', '');
var_dump(ini_set('zend.exception_string_param_max_len', '3'), ini_set('zend.exception_string_param_max_len', '-1'));
function f($s) { return new Exception(); }
echo f("abcdef")->getTraceAsString(), "\n";
ini_set('zend.exception_ignore_args', '1');
echo f("abcdef")->getTraceAsString(), "\n";

try {
    trigger_error("bad level", E_ERROR);
} catch (ValueError $e) {
    echo get_class($e), ": ", $e->getMessage(), "\n";
}

register_shutdown_function(function () {
    echo "shutdown: ", error_get_last() === null ? "no error" : error_get_last()['message'], "\n";
});
set_exception_handler(function ($e) {
    echo "exception handler: ", $e->getMessage(), "\n";
    throw new LogicException("from the handler");
});
throw new RuntimeException("uncaught");
