<?php
// Issue 057: Constants that refer to each other: an Error, not a stack overflow.
class SR { const X = self::Y; const Y = self::X; }
try { echo SR::X; } catch (Error $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
try { echo SR::Y; } catch (Error $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
class A { const P = B::Q; } class B { const Q = A::P; const R = 1; }
try { echo B::Q; } catch (Error $e) { echo $e->getMessage(), "\n"; }
class S { const Z = self::Z; }
try { echo S::Z; } catch (Error $e) { echo $e->getMessage(), "\n"; }
class T { const U = T::U . 'x'; }
try { echo T::U; } catch (Error $e) { echo $e->getMessage(), "\n"; }
try { echo T::U; } catch (Error $e) { echo $e->getMessage(), "\n"; }
class P1 { public static $s = P1::K; const K = self::L; const L = P1::K; }
try { echo P1::$s; } catch (Error $e) { echo $e->getMessage(), "\n"; }
class D1 { public $p = self::M; const M = self::N; const N = self::M; }
try { new D1; } catch (Error $e) { echo $e->getMessage(), "\n"; }
class E1 { const M = [self::N]; const N = self::M; }
try { var_dump(E1::M); } catch (Error $e) { echo $e->getMessage(), "\n"; }
// throwing initializer: constant stays unevaluated, retried
class F1 { const M = self::N + 1; const N = UNDEF_C; }
try { echo F1::M; } catch (Error $e) { echo $e->getMessage(), "\n"; }
define('UNDEF_C', 5);
echo F1::M, "\n";
function f($a = G1::X) { return $a; }
class G1 { const X = self::Y; const Y = self::X; }
try { f(); } catch (Error $e) { echo $e->getMessage(), "\n"; }
// a class's constants are all evaluated at its first object or static property (zend_update_class_constants)
class UC { const B = UNDEF_B; public $x = 1; }
try { new UC; echo "new ok\n"; } catch (Error $e) { echo "new: ", $e->getMessage(), "\n"; }
try { new UC; echo "new ok\n"; } catch (Error $e) { echo "new2: ", $e->getMessage(), "\n"; }
class US { const B = UNDEF_S; public static $s = 2; }
try { echo US::$s, "\n"; } catch (Error $e) { echo "static: ", $e->getMessage(), "\n"; }
class UP { const B = UNDEF_P; } class UQ extends UP { }
try { new UQ; echo "child ok\n"; } catch (Error $e) { echo "child: ", $e->getMessage(), "\n"; }
class UM { const B = UNDEF_M; static function f() { return 3; } }
try { echo UM::f(), "\n"; } catch (Error $e) { echo "method: ", $e->getMessage(), "\n"; }
class UK { const A = UNDEF_K; const C = 4; }
try { echo UK::C, "\n"; } catch (Error $e) { echo "const: ", $e->getMessage(), "\n"; }
class UO { const A = 'a' . self::B; const B = 'b'; public $p = self::A; }
var_dump((new UO)->p);
// the access check comes before the initializer runs
class V { private const P = UNDEF_V; }
try { echo V::P; } catch (Error $e) { echo $e->getMessage(), "\n"; }
