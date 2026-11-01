<?php
// filter_input(), filter_has_var() and filter_input_array() under the CLI: the request's input as the SAPI
// registered it (no GET, POST or COOKIE variables; INPUT_SERVER the five the CLI passes through the input filter),
// whatever the script does to the superglobals (WooCommerce reads INPUT_GET, INPUT_POST and INPUT_SERVER)
ob_start(function ($text) {
    return str_replace([__FILE__, __DIR__], ["FILE", "DIR"], $text);
});

function attempt(callable $f) {
    try {
        var_dump($f());
    } catch (Throwable $e) {
        echo get_class($e), ": ", $e->getMessage(), "\n";
    }
}

$_GET["a"] = "1";
$_POST["b"] = "2";
$_COOKIE["c"] = "3";
$_SERVER["REQUEST_METHOD"] = "GET";
$_SERVER["PHP_SELF"] = "changed";
$_ENV["E"] = "4";

foreach ([INPUT_GET, INPUT_POST, INPUT_COOKIE, INPUT_SERVER] as $type) {
    echo "-- type $type\n";
    var_dump(filter_has_var($type, "a"), filter_has_var($type, "PHP_SELF"), filter_input($type, "a"),
        filter_input($type, "a", FILTER_VALIDATE_INT, FILTER_NULL_ON_FAILURE),
        filter_input($type, "a", FILTER_VALIDATE_INT, ["options" => ["default" => 9]]),
        filter_input($type, "a", FILTER_VALIDATE_INT, ["flags" => FILTER_NULL_ON_FAILURE]),
        filter_input($type, "a", FILTER_VALIDATE_INT, ["options" => ["default" => 9], "flags" => FILTER_NULL_ON_FAILURE]),
        filter_input($type, "a", FILTER_VALIDATE_INT, ["options" => "x"]));
    $all = filter_input_array($type);
    var_dump(is_array($all) ? array_keys($all) : $all);
    $some = filter_input_array($type, ["PHP_SELF" => FILTER_DEFAULT, "nope" => FILTER_VALIDATE_INT]);
    var_dump(is_array($some) ? array_map(fn($v) => is_string($v) ? basename($v) : $v, $some) : $some);
    var_dump(filter_input_array($type, ["nope" => FILTER_DEFAULT], false));
}

echo "-- INPUT_SERVER\n";
var_dump(basename(filter_input(INPUT_SERVER, "PHP_SELF")), basename(filter_input(INPUT_SERVER, "SCRIPT_NAME")),
    basename(filter_input(INPUT_SERVER, "SCRIPT_FILENAME")), basename(filter_input(INPUT_SERVER, "PATH_TRANSLATED")),
    filter_input(INPUT_SERVER, "DOCUMENT_ROOT"), filter_input(INPUT_SERVER, "DOCUMENT_ROOT", FILTER_DEFAULT,
    FILTER_FLAG_EMPTY_STRING_NULL), filter_input(INPUT_SERVER, "REQUEST_METHOD"), filter_input(INPUT_SERVER, "argv"),
    filter_input(INPUT_SERVER, "REQUEST_TIME"), filter_has_var(INPUT_SERVER, "REQUEST_TIME"), filter_has_var(INPUT_SERVER,
    "PATH"), filter_input(INPUT_SERVER, "PHP_SELF", FILTER_VALIDATE_INT),
    filter_input(INPUT_SERVER, "PHP_SELF", FILTER_SANITIZE_NUMBER_INT, FILTER_FORCE_ARRAY),
    filter_input(INPUT_SERVER, "PHP_SELF", FILTER_DEFAULT, FILTER_REQUIRE_ARRAY));
var_dump(array_keys(filter_input_array(INPUT_SERVER, FILTER_UNSAFE_RAW)));
var_dump($_SERVER["PHP_SELF"]);

echo "-- INPUT_ENV\n";
var_dump(filter_has_var(INPUT_ENV, "E"), filter_input(INPUT_ENV, "E"), is_array(filter_input_array(INPUT_ENV)));

echo "-- missing input\n";
attempt(fn() => filter_input(INPUT_GET, "a", FILTER_DEFAULT, FILTER_THROW_ON_FAILURE));
attempt(fn() => filter_input(INPUT_GET, "a\0b", FILTER_DEFAULT, ["flags" => FILTER_THROW_ON_FAILURE,
    "options" => ["default" => 1]]));
attempt(fn() => filter_input(INPUT_GET, "a", FILTER_DEFAULT, FILTER_THROW_ON_FAILURE | FILTER_NULL_ON_FAILURE));
attempt(fn() => filter_input(INPUT_SERVER, "nope", FILTER_DEFAULT, FILTER_THROW_ON_FAILURE));
attempt(fn() => filter_input(INPUT_SERVER, "PHP_SELF", FILTER_VALIDATE_INT, FILTER_THROW_ON_FAILURE));

echo "-- arguments\n";
attempt(fn() => filter_input(INPUT_GET, "a", 12345));
attempt(fn() => filter_input_array(INPUT_GET, 12345));
attempt(fn() => filter_input(3, "a"));
attempt(fn() => filter_input(99, "a"));
attempt(fn() => filter_has_var(-1, "a"));
attempt(fn() => filter_input_array(6));
attempt(fn() => filter_input_array(3, 12345));
attempt(fn() => filter_input());
attempt(fn() => filter_input(INPUT_GET));
attempt(fn() => filter_has_var(INPUT_GET));
attempt(fn() => filter_input("x", "a"));
attempt(fn() => filter_input(INPUT_GET, []));
attempt(fn() => filter_input(INPUT_SERVER, null));
attempt(fn() => filter_input_array(INPUT_SERVER, ["PHP_SELF" => FILTER_DEFAULT], []));
attempt(fn() => filter_input_array(INPUT_SERVER, [1 => FILTER_DEFAULT]));
var_dump(filter_has_var(INPUT_SERVER, 1), filter_input(INPUT_SERVER, 1.0));
