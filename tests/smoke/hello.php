<?php
function fib($n) { return $n < 2 ? $n : fib($n - 1) + fib($n - 2); }
$a = ['x' => 1, 'y' => [1, 2, 3]];
$b = $a;
$b['y'][] = 4;
echo "Hello, ", PHP_VERSION, "\n";
echo fib(20), " ", count($a['y']), " ", count($b['y']), " ", 7 / 2, " ", intdiv(7, 2), " ", 0.1 + 0.2, "\n";
echo implode(",", array_map(fn($v) => $v * 2, $b['y'])), "\n";
printf("%05.2f|%-4s|%x\n", 3.14159, "ab", 255);
echo json_encode(['a' => 1, 'b' => [true, null, 1.5, "é/"]]), "\n";
var_dump(json_decode('{"a":{"b":[1,2]}}', true));
echo preg_replace('/(\w+) (\w+)/', '$2 ${1}!', "hello world"), "\n";
var_dump(preg_match('/(?<word>w\w+)/', "hello world", $m), $m);
print_r(preg_split('//u', "żółw", -1, PREG_SPLIT_NO_EMPTY));
echo preg_replace_callback('/\d+/', fn($m) => $m[0] * 2, "a1 b22 c333"), "\n";
class P { public $x = 1; function __construct(public int $y = 2) {} function sum() { return $this->x + $this->y; } }
$p = new P(5);
echo $p->sum(), " ", get_class($p), "\n";
try { throw new RuntimeException("boom"); } catch (Exception $e) { echo get_class($e), ": ", $e->getMessage(), "\n"; }
echo mb_strtolower("ŻÓŁW"), " ", mb_substr("żółw", 1, 2), " ", strlen("żółw"), "\n";
