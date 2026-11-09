<?php
// Callable strings resolved by the generic path (issue 093: a name that named a function before goes by its id
// to the context's slot): case variants, a leading backslash, built-ins and user functions, a name that fails
// before its function is declared and works after, a name that never exists, and one site meeting more names
// than its cache keeps (a WordPress hook's).

function my_fn($v) { return "my($v)"; }
function other_fn($v) { return "other($v)"; }

function call_each(array $names, $value): array {
    $out = [];
    foreach ($names as $name) {
        try {
            $out[] = call_user_func($name, $value);
        } catch (Error $e) {
            $out[] = get_class($e) . ': ' . $e->getMessage();
        }
    }
    return $out;
}

$names = ['strtolower', 'StrToUpper', '\strrev', 'my_fn', 'MY_FN', '\Other_Fn', 'ucfirst', 'trim', 'md5',
    'strlen', 'late_fn', 'nope_fn'];
$first = call_each($names, 'AbC');
echo implode("\n", $first), "\n";

if (count($first) > 0) {
    function late_fn($v) { return "late($v)"; }
}

$last = null;
for ($i = 0; $i < 2000; $i++) {
    $last = call_each($names, 'AbC');
}
echo "--\n", implode("\n", $last), "\n";
echo implode(',', array_map('StrLen', ['a', 'bb', 'ccc'])), ' ', implode(',', array_map('\my_fn', [1, 2])), "\n";
var_dump(is_callable('LATE_FN'), is_callable('nope_fn'), function_exists('\late_fn'));
