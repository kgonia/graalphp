<?php
// ext/filter's functions around the filters: filter_var's options and flags, filter_var_array (WooCommerce's
// definitions), FILTER_CALLBACK, the argument errors and warnings, filter_list() and filter_id()
ob_start(function ($text) {
    return str_replace([__FILE__, __DIR__], ["FILE", "DIR"], $text);
});

function attempt(callable $f) {
    try {
        var_dump($f());
    } catch (Throwable $e) {
        echo get_class($e), ": ", $e->getMessage(), "\n";
        for ($p = $e->getPrevious(); $p; $p = $p->getPrevious()) {
            echo "  previous ", get_class($p), ": ", $p->getMessage(), "\n";
        }
    }
}

echo "-- options and flags\n";
var_dump(filter_var("on", 0, ["filter" => FILTER_VALIDATE_BOOL]));
var_dump(filter_var("5", FILTER_VALIDATE_INT, ["filter" => FILTER_VALIDATE_BOOL]),
    filter_var("on", FILTER_DEFAULT, ["filter" => FILTER_VALIDATE_BOOL]),
    filter_var("x", FILTER_VALIDATE_INT, ["flags" => FILTER_NULL_ON_FAILURE]),
    filter_var("x", FILTER_VALIDATE_INT, ["options" => "not an array", "flags" => FILTER_NULL_ON_FAILURE]),
    filter_var(["1", ["2", "x"]], FILTER_VALIDATE_INT, ["flags" => FILTER_REQUIRE_ARRAY | FILTER_NULL_ON_FAILURE]),
    filter_var("1", FILTER_VALIDATE_INT, FILTER_REQUIRE_ARRAY),
    filter_var("1", FILTER_VALIDATE_INT, FILTER_REQUIRE_ARRAY | FILTER_NULL_ON_FAILURE),
    filter_var(["1"], FILTER_VALIDATE_INT), filter_var(["1"], FILTER_VALIDATE_INT, FILTER_NULL_ON_FAILURE),
    filter_var(["1"], FILTER_VALIDATE_INT, FILTER_FORCE_ARRAY), filter_var("1", FILTER_VALIDATE_INT, FILTER_FORCE_ARRAY),
    filter_var("x", FILTER_VALIDATE_INT, FILTER_FORCE_ARRAY | FILTER_NULL_ON_FAILURE),
    filter_var([], FILTER_VALIDATE_INT, FILTER_REQUIRE_ARRAY),
    filter_var(["a" => ["b" => ["c" => " 3 "]]], FILTER_VALIDATE_INT, FILTER_REQUIRE_ARRAY),
    filter_var("x", FILTER_VALIDATE_INT, ["options" => ["default" => null]]),
    filter_var("x", 0x0200), filter_var("x", 0x0100), filter_var("x", 0x0209));

echo "-- objects\n";
class Stringy { function __toString(): string { return "42"; } }
class Plain {}
var_dump(filter_var(new Stringy(), FILTER_VALIDATE_INT), filter_var(new Plain(), FILTER_VALIDATE_INT),
    filter_var(new Plain(), FILTER_DEFAULT), filter_var(new Plain(), FILTER_VALIDATE_INT, FILTER_NULL_ON_FAILURE),
    filter_var(new Plain(), FILTER_VALIDATE_INT, ["options" => ["default" => "d"]]),
    filter_var(function () {}, FILTER_DEFAULT), filter_var([new Plain(), new Stringy()], FILTER_DEFAULT, FILTER_REQUIRE_ARRAY));
class Throws { function __toString(): string { throw new RuntimeException("no string"); } }
attempt(fn() => filter_var(new Throws(), FILTER_VALIDATE_INT));
attempt(fn() => filter_var(["a" => new Throws(), "b" => new Throws()], FILTER_DEFAULT, FILTER_REQUIRE_ARRAY));

echo "-- references\n";
$x = "5";
$y = ["7"];
$with = [&$x, "6", "n" => &$y];
var_dump(filter_var($with, FILTER_VALIDATE_INT, FILTER_REQUIRE_ARRAY), $x, $y, $with);
$self = ["9"];
$self[] = &$self;
var_dump(count(filter_var($self, FILTER_VALIDATE_INT, FILTER_REQUIRE_ARRAY)));
$def = ["v" => ["filter" => FILTER_VALIDATE_INT]];
$f = FILTER_VALIDATE_INT;
var_dump(filter_var_array(["v" => "8", "w" => "x"], ["v" => &$f, "w" => &$def["v"]]));

echo "-- callback\n";
var_dump(filter_var("abc", FILTER_CALLBACK, ["options" => "strtoupper"]),
    filter_var(["a", ["b"]], FILTER_CALLBACK, ["options" => "strtoupper"]),
    filter_var("abc", FILTER_CALLBACK, ["options" => fn($v) => [$v, strlen($v)]]),
    filter_var("abc", FILTER_CALLBACK, ["options" => fn($v) => null]),
    filter_var(12, FILTER_CALLBACK, ["options" => fn($v) => var_export($v, true)]),
    filter_var("abc", FILTER_CALLBACK, ["options" => "strtoupper", "flags" => FILTER_REQUIRE_SCALAR]),
    filter_var(["abc"], FILTER_CALLBACK, ["options" => "strtoupper", "flags" => FILTER_REQUIRE_SCALAR]));
class Cb { static function twice($v) { return $v . $v; } function wrap($v) { return "[$v]"; } }
var_dump(filter_var("ab", FILTER_CALLBACK, ["options" => "Cb::twice"]),
    filter_var("ab", FILTER_CALLBACK, ["options" => ["Cb", "twice"]]),
    filter_var("ab", FILTER_CALLBACK, ["options" => [new Cb(), "wrap"]]));
attempt(fn() => filter_var("abc", FILTER_CALLBACK));
attempt(fn() => filter_var("abc", FILTER_CALLBACK, ["options" => "no_such_function"]));
attempt(fn() => filter_var(["a", "b", "c"], FILTER_CALLBACK, ["options" => "no_such_function"]));
$calls = [];
attempt(function () use (&$calls) {
    return filter_var(["a", "b", "c"], FILTER_CALLBACK, ["options" => function ($v) use (&$calls) {
        $calls[] = $v;
        throw new LogicException("at $v");
    }]);
});
var_dump($calls);
attempt(fn() => filter_var(["1.5", "2.5"], FILTER_VALIDATE_FLOAT,
    ["options" => ["decimal" => "ab"], "flags" => FILTER_REQUIRE_ARRAY]));
attempt(fn() => filter_var(["x", "y"], FILTER_VALIDATE_INT, ["flags" => FILTER_REQUIRE_ARRAY | FILTER_THROW_ON_FAILURE]));
attempt(fn() => filter_var([new Plain(), new Plain()], FILTER_VALIDATE_INT,
    ["flags" => FILTER_REQUIRE_ARRAY | FILTER_THROW_ON_FAILURE]));

echo "-- filter_var_array\n";
$data = ["id" => "42", "email" => "a@example.com", "bad" => "x", "list" => ["1", "2", "z"], "url" => "http://e.com",
         "flag" => "yes", 5 => "five", "nested" => ["a" => ["b" => "7"]]];
var_dump(filter_var_array($data, [
    "id" => FILTER_VALIDATE_INT,
    "email" => ["filter" => FILTER_VALIDATE_EMAIL],
    "bad" => ["filter" => FILTER_VALIDATE_INT, "options" => ["default" => -1]],
    "list" => ["filter" => FILTER_VALIDATE_INT, "flags" => FILTER_REQUIRE_ARRAY],
    "url" => ["filter" => FILTER_VALIDATE_URL, "flags" => FILTER_FORCE_ARRAY],
    "flag" => ["filter" => FILTER_VALIDATE_BOOL, "flags" => FILTER_NULL_ON_FAILURE],
    "missing" => FILTER_VALIDATE_INT,
    "nested" => ["flags" => FILTER_REQUIRE_ARRAY],
    "scalar-list" => [],
]));
var_dump(filter_var_array($data, ["id" => FILTER_VALIDATE_INT, "missing" => FILTER_DEFAULT], false),
    filter_var_array($data, FILTER_VALIDATE_INT), filter_var_array([], FILTER_VALIDATE_INT),
    filter_var_array(["list" => ["1"]], ["list" => FILTER_VALIDATE_INT]),
    filter_var_array(["a" => "1"], ["a" => "257"]),
    filter_var_array(["a" => "1"], ["a" => ["filter" => FILTER_CALLBACK, "options" => "strrev", "flags" => 0]]));
var_dump(filter_var_array(["a" => "1"], ["a" => 12345]));
var_dump(filter_var_array(["a" => "1"], ["a" => new Plain()]));
var_dump(filter_var_array(["a" => "1"], 12345));
attempt(fn() => filter_var_array(["a" => "1"], [5 => FILTER_VALIDATE_INT]));
attempt(fn() => filter_var_array(["a" => "1"], ["" => FILTER_VALIDATE_INT]));
attempt(fn() => filter_var_array(["a" => "1", "b" => "2"], ["a" => FILTER_VALIDATE_INT, 0 => FILTER_VALIDATE_INT]));
attempt(fn() => filter_var_array(["a" => "x", "b" => "y"], ["a" => ["filter" => FILTER_VALIDATE_INT,
    "flags" => FILTER_THROW_ON_FAILURE], "b" => ["filter" => FILTER_VALIDATE_INT, "flags" => FILTER_THROW_ON_FAILURE]]));
attempt(fn() => filter_var_array(["a" => "x"], ["a" => ["flags" => FILTER_THROW_ON_FAILURE | FILTER_NULL_ON_FAILURE]]));
attempt(fn() => filter_var_array("x"));
attempt(fn() => filter_var_array(["a" => "1"], "abc"));
attempt(fn() => filter_var_array(["a" => "1"], FILTER_DEFAULT, []));
var_dump(filter_var_array(["a" => "1"], FILTER_DEFAULT, 0), filter_var_array(["a" => "1"], ["b" => 0], "0"));

echo "-- arguments\n";
attempt(fn() => filter_var());
attempt(fn() => filter_var("1", FILTER_VALIDATE_INT, 0, 1));
attempt(fn() => filter_var("1", "abc"));
attempt(fn() => filter_var("1", "257"));
attempt(fn() => filter_var("1", " 257 "));
attempt(fn() => filter_var("1", 257.0));
attempt(fn() => filter_var("1", 257.5));
attempt(fn() => filter_var("1", "257.5"));
attempt(fn() => filter_var("1", 1e30));
attempt(fn() => filter_var("1", null));
attempt(fn() => filter_var("1", FILTER_VALIDATE_INT, null));
attempt(fn() => filter_var("1", FILTER_VALIDATE_INT, "x"));
attempt(fn() => filter_var("1", FILTER_VALIDATE_INT, new Plain()));
attempt(fn() => filter_var("1", true));
attempt(fn() => filter_var("1", 9999));
attempt(fn() => filter_var("1", -1));
attempt(fn() => filter_var("x", FILTER_VALIDATE_INT, ["flags" => new Plain()]));
attempt(fn() => filter_var("1", FILTER_VALIDATE_INT, ["options" => ["min_range" => new Plain()]]));
attempt(fn() => filter_var("1", FILTER_VALIDATE_FLOAT, ["options" => ["max_range" => new Plain()]]));
attempt(fn() => filter_list(1));
attempt(fn() => filter_id());
attempt(fn() => filter_id([]));
