<?php
// Errors the runtime raises reach catch blocks as objects of their own class (JsonException with its code, the
// generator's Exception, TypeError, ArithmeticError...); a pattern that doesn't compile makes the preg_* call fail
// with preg_last_error() PREG_INTERNAL_ERROR, as in php-src (its warning is silenced here).
foreach (['/(/', '', 'abc', '/x', '/x/Q', '/a/'] as $re) {
    preg_match('/a/', 'a');
    echo json_encode($re), "\n";
    var_dump(@preg_match($re, 'a'), preg_last_error(), preg_last_error_msg());
    var_dump(@preg_match_all($re, 'a'), preg_last_error());
    var_dump(@preg_replace($re, 'b', 'a'), @preg_replace($re, 'b', ['k' => 'a', 'a']), @preg_replace([$re, '/a/'], 'b', 'a'));
    var_dump(@preg_replace_callback($re, fn($m) => 'b', 'a'), @preg_replace_callback($re, fn($m) => 'b', ['a']));
    var_dump(@preg_split($re, 'a'), @preg_grep($re, ['a']), preg_last_error());
}
try { var_dump(json_decode('{', false, 512, JSON_THROW_ON_ERROR)); } catch (JsonException $e) { echo get_class($e), ': ', $e->getMessage(), ' ', $e->getCode(), "\n"; }
function g() { yield 1; yield 2; }
$g = g(); foreach ($g as $v) {} 
try { $g->rewind(); } catch (Exception $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
$h = g();
try { $h->getReturn(); } catch (Exception $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
try { intdiv(1, 0); } catch (DivisionByZeroError $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
try { intdiv(PHP_INT_MIN, -1); } catch (ArithmeticError $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
try { str_repeat('x', -1); } catch (ValueError $e) { echo get_class($e), ': ', $e->getMessage(), "\n"; }
