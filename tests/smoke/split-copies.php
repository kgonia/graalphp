<?php
// A function called from many sites with values of many kinds (WordPress's is_wp_error()) goes polymorphic and
// Truffle splits it, a copy per caller; each copy must answer as the original did. A bytecode copy kept the
// original's specialization state but not its caches, and answered as if the value were of none of the kinds it
// had met: `instanceof` false for an object of the class (wp_signon() went on with a WP_Error), a string truthy
// however empty, an element or a list() element null, isset() false, a foreach with no steps.
set_error_handler(fn() => true);

class WP_Error {}
class Post {}
class Term {}
class User {}
class Named { public $name = 'post'; protected $hidden = 1; public $id = 7; }

function is_error($thing) {
    return $thing instanceof WP_Error;
}

// each test: $x the value under test, $o an object of a different class each call (polymorphic, so the test splits)
final class TruthyTest {
    static function test($x, $o) { $o instanceof WP_Error; if ($x) { return 'T'; } return 'F'; }
    static function site1($x, $o) { return self::test($x, $o); }
    static function site2($x, $o) { return self::test($x, $o); }
    static function site3($x, $o) { return self::test($x, $o); }
    static function site4($x, $o) { return self::test($x, $o); }
}
final class ElementTest {
    static function test($x, $o) { $o instanceof WP_Error; return $x['key']; }
    static function site1($x, $o) { return self::test($x, $o); }
    static function site2($x, $o) { return self::test($x, $o); }
    static function site3($x, $o) { return self::test($x, $o); }
    static function site4($x, $o) { return self::test($x, $o); }
}
final class OffsetTest {
    static function test($x, $o) { $o instanceof WP_Error; return $x[1]; }
    static function site1($x, $o) { return self::test($x, $o); }
    static function site2($x, $o) { return self::test($x, $o); }
    static function site3($x, $o) { return self::test($x, $o); }
    static function site4($x, $o) { return self::test($x, $o); }
}
final class IssetTest {
    static function test($x, $o) { $o instanceof WP_Error; return isset($x['key']); }
    static function site1($x, $o) { return self::test($x, $o); }
    static function site2($x, $o) { return self::test($x, $o); }
    static function site3($x, $o) { return self::test($x, $o); }
    static function site4($x, $o) { return self::test($x, $o); }
}
final class CoalesceTest {
    static function test($x, $o) { $o instanceof WP_Error; return $x['key'] ?? 'none'; }
    static function site1($x, $o) { return self::test($x, $o); }
    static function site2($x, $o) { return self::test($x, $o); }
    static function site3($x, $o) { return self::test($x, $o); }
    static function site4($x, $o) { return self::test($x, $o); }
}
final class ListTest {
    static function test($x, $o) { $o instanceof WP_Error; [$first] = $x; return $first; }
    static function site1($x, $o) { return self::test($x, $o); }
    static function site2($x, $o) { return self::test($x, $o); }
    static function site3($x, $o) { return self::test($x, $o); }
    static function site4($x, $o) { return self::test($x, $o); }
}
final class ForeachTest {
    static function test($x, $o) {
        $o instanceof WP_Error;
        $seen = '';
        foreach ($x as $key => $value) { $seen .= "$key=$value,"; }
        return $seen;
    }
    static function site1($x, $o) { return self::test($x, $o); }
    static function site2($x, $o) { return self::test($x, $o); }
    static function site3($x, $o) { return self::test($x, $o); }
    static function site4($x, $o) { return self::test($x, $o); }
}
final class ForeachRefTest {
    static function test($x, $o) {
        $o instanceof WP_Error;
        $seen = '';
        foreach ($x as &$value) { $seen .= "$value,"; }
        return $seen;
    }
    static function site1($x, $o) { return self::test($x, $o); }
    static function site2($x, $o) { return self::test($x, $o); }
    static function site3($x, $o) { return self::test($x, $o); }
    static function site4($x, $o) { return self::test($x, $o); }
}

function is_error1($x) { return is_error($x); }
function is_error2($x) { return is_error($x); }
function is_error3($x) { return is_error($x); }
function is_error4($x) { return is_error($x); }

$error = new WP_Error();
$answers = [];
for ($i = 0; $i < 3; $i++) {
    // the original sees non-objects and objects of several classes: polymorphic, so its callers get copies
    $answers[] = is_error1(null);
    $answers[] = is_error1(false);
    $answers[] = is_error2(new Post());
    $answers[] = is_error2(new Term());
    $answers[] = is_error3(new User());
    // the copies
    $answers[] = is_error4($error);
    $answers[] = is_error1($error);
    $answers[] = is_error2($error);
    $answers[] = is_error3([1]);
    $answers[] = is_error3($error);
}
echo implode(' ', array_map(fn($a) => $a ? 'T' : 'F', $answers)), "\n";

// $other: a value none of the operation's specializations takes; $values: ones that do. The original meets $other and
// $values[0] (where the objects turn polymorphic), the copies the rest, from site2's second call on.
function run(string $test, $other, array $values) {
    $objects = [new Post(), new Term(), new User(), new WP_Error()];
    $answers = [];
    for ($i = 0; $i < 3; $i++) {
        $answers[] = $test::site1($other, $objects[0]);
        foreach ($values as $j => $value) {
            $answers[] = $test::site2($value, $objects[($j + 1) % 4]);
        }
        $last = $values[count($values) - 1];
        $answers[] = $test::site3($last, $objects[2]);
        $answers[] = $test::site4($last, $objects[3]);
        $answers[] = $test::site1($last, $objects[1]);
        $answers[] = $test::site2($last, $objects[0]);
    }
    echo $test, ': ', implode(' ', array_map('var_export', $answers, array_fill(0, count($answers), true))), "\n";
}

run('TruthyTest', new Post(), ['x', '0', '']);
run('ElementTest', null, [['key' => 'a'], ['key' => 'b']]);
run('OffsetTest', null, ['ab', 'cd']);
run('IssetTest', null, [['key' => 'a'], ['key' => 'b']]);
run('CoalesceTest', null, [['key' => 'a'], ['key' => 'b']]);
run('ListTest', null, [['a'], ['b']]);
run('ForeachTest', null, [new Named(), new Named()]);
run('ForeachRefTest', null, [['v'], [1, 2]]);
