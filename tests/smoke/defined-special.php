<?php
// defined(): php-src compiles a literal name into ZEND_DEFINED (zend_compile_func_defined) unless it has a
// namespace or a class; other forms call the function, which resolves them as zend_get_constant_ex does
define('PE4_PLAIN', 1);
define('pe4_lower', 2);
const PE4_CONST = 3;
var_dump(defined('PE4_PLAIN'), defined('pe4_plain'), defined('pe4_lower'), defined('PE4_LOWER'));
var_dump(defined('PE4_CONST'), defined('PE4_MISSING'));
var_dump(defined('true'), defined('TRUE'), defined('True'), defined('false'), defined('null'), defined('NULL'));
var_dump(defined('PHP_VERSION'), defined('E_ALL'), defined('PHP_EOL'), defined('__LINE__'), defined('__FILE__'));
var_dump(defined('__COMPILER_HALT_OFFSET__'));
var_dump(defined(''), defined('1'), defined(1), defined(1.5));
var_dump(defined('\PE4_PLAIN'), defined('\true'), defined('\PHP_VERSION'), defined('\pe4_plain'));
// before and after a define in the same site
function check($i) { return defined('PE4_LATE'); }
var_dump(check(0));
define('PE4_LATE', null);
var_dump(check(1));
for ($i = 0; $i < 3; $i++) {
    if ($i == 1) define('PE4_LOOP', $i);
    var_dump(defined('PE4_LOOP'));
    if (!defined('PE4_LOOP')) echo "not yet\n";
    echo defined('PE4_LOOP') ? "yes\n" : "no\n";
}
// a constant whose value is false or null is still defined
define('PE4_FALSE', false);
var_dump(defined('PE4_FALSE'));
defined('PE4_GUARD') || define('PE4_GUARD', 'first');
defined('PE4_GUARD') || define('PE4_GUARD', 'second');
var_dump(PE4_GUARD);
// class constants
class Pe4A { const X = 1; const P = 2; const Q = 3; const T = PE4_PLAIN + 1;
    static function inside() { return [defined('Pe4A::P'), defined('self::P'), defined('static::Q'), defined('static::R')]; } }
class Pe4B extends Pe4A { const R = 4;
    static function sub() { return [defined('parent::X'), defined('self::X'), defined('self::R'), defined('parent::R')]; } }
var_dump(defined('Pe4A::X'), defined('pe4a::X'), defined('Pe4A::x'), defined('Pe4A::Y'), defined('PE4B::P'));
var_dump(Pe4A::inside(), Pe4B::sub(), Pe4B::inside());
var_dump(defined('Pe4A::class'), defined('Pe4A::T'), Pe4A::T, defined('\Pe4A::X'), defined('::X'), defined('Pe4A:X'));
var_dump(defined('Missing::X'));
trait Pe4Trait { public const TC = 1; public static function own() { return [defined('self::TC'), defined('Pe4Trait::TC')]; } }
class Pe4UsesTrait { use Pe4Trait; }
var_dump(defined('Pe4Trait::TC'), defined('Pe4UsesTrait::TC'), Pe4UsesTrait::own());
class Pe4C extends Pe4B { static function f() { $g = function () { return [defined('self::R'), defined('static::R')]; };
    return $g(); } }
var_dump(Pe4C::f());
spl_autoload_register(function ($c) {
    echo "autoload $c\n";
    if ($c === 'Pe4Auto') {
        require __DIR__ . '/defined-autoload.inc';
    }
});
var_dump(defined('Pe4Auto::Z'), defined('Pe4Nope::Z'), defined('Pe4Nope::Z'));
try {
    var_dump(defined('self::X'));
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
class Pe4Lone { static function p() { return defined('parent::X'); } }
try {
    var_dump(Pe4Lone::p());
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    var_dump(defined('static::X'));
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
// a dynamic name is the function too
$n = 'PE4_PLAIN';
var_dump(defined($n), defined('PE4_' . 'PLAIN'), call_user_func('defined', 'PE4_PLAIN'), \defined('PE4_PLAIN'));
var_dump(array_map('defined', ['PE4_PLAIN', 'true', 'Pe4A::X', 'NOPE', 'Null', '\PE4_PLAIN']));
$f = 'defined';
var_dump($f('PE4_CONST'), $f('False'));
