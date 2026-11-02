<?php
// ArgumentCountError for too few arguments, as zend_missing_arg_error words it: where user code made the call,
// "exactly" when every parameter is required, else "at least" the parameters up to the last required one
ob_start(function ($text) {
    return str_replace([__FILE__, __DIR__], ["FILE", "DIR"], $text);
});
function one($a) {}
function two($a, $b = 1) {}
function rest($a, ...$more) {}
class K {
    function m($x, $y) {}
    static function s($x) {}
}
$calls = [
    fn() => one(),
    fn() => two(),
    fn() => rest(),
    fn() => (new K)->m(1),
    fn() => K::s(),
    fn() => call_user_func('one'),
    fn() => array_map('two', []) ?: two(),
    fn() => call_user_func_array([new K, 'm'], [1]),
    function () { $a = [1, 2]; usort($a, function ($x, $y, $z) { return 0; }); },
    fn() => array_map([new K, 'm'], [1]),
];
foreach ($calls as $call) {
    try {
        $call();
    } catch (ArgumentCountError $e) {
        echo $e->getMessage(), "\n";
    }
}
