<?php
// Core-mechanism micro-benchmarks (Gate 1 K0): ns per operation on PHP and on Truffle PHP.
// Usage: php micro.php [rounds]   prints "name round ns_per_op result"

function m_loop(int $n): int { $s = 0; for ($i = 0; $i < $n; $i++) { $s += $i & 7; } return $s; }
function add1(int $x): int { return $x + 1; }
function m_call(int $n): int { $s = 0; for ($i = 0; $i < $n; $i++) { $s = add1($s); } return $s; }
function m_array_rw(int $n): int { $a = []; for ($i = 0; $i < 1000; $i++) { $a[] = $i; } $s = 0;
    for ($i = 0; $i < $n; $i++) { $a[$i % 1000] += 1; $s += $a[($i * 7) % 1000]; } return $s; }
function m_hash(int $n): int { $a = []; for ($i = 0; $i < 100; $i++) { $a['k' . $i] = $i; } $s = 0; $keys = array_keys($a);
    for ($i = 0; $i < $n; $i++) { $s += $a[$keys[$i % 100]]; } return $s; }
function m_foreach(int $n): int { $a = range(1, 1000); $s = 0; for ($r = 0; $r < $n / 1000; $r++) { foreach ($a as $k => $v) { $s += $v + $k; } } return $s; }
function m_concat(int $n): int { $s = ''; for ($i = 0; $i < $n; $i++) { $s .= 'x'; if (strlen($s) > 1000) { $s = ''; } } return strlen($s); }
function m_strfn(int $n): int { $t = 'Hello World, this is a test'; $s = 0; for ($i = 0; $i < $n; $i++) { $s += strlen(str_replace('o', '0', $t)) + strpos($t, 'test'); } return $s; }
final class Pt { public int $x = 0; public function inc(): void { $this->x++; } public function get(): int { return $this->x; } }
function m_prop(int $n): int { $p = new Pt(); for ($i = 0; $i < $n; $i++) { $p->x = $p->x + 1; } return $p->x; }
function m_method(int $n): int { $p = new Pt(); for ($i = 0; $i < $n; $i++) { $p->inc(); } return $p->get(); }
function m_closure(int $n): int { $f = fn($x) => $x + 1; $s = 0; for ($i = 0; $i < $n; $i++) { $s = $f($s); } return $s; }
function m_cuf(int $n): int { $s = 0; for ($i = 0; $i < $n; $i++) { $s = call_user_func_array('add1', [$s]); } return $s; }
function m_isset(int $n): int { $a = ['a' => 1, 'b' => null]; $s = 0; for ($i = 0; $i < $n; $i++) { if (isset($a['a'])) { $s++; } if (isset($a['b'])) { $s--; } } return $s; }
function m_strcmp(int $n): int { $s = 0; $x = 'priority'; for ($i = 0; $i < $n; $i++) { if ($x === 'priority') { $s++; } if ($x == 'other') { $s--; } } return $s; }

$benches = ['m_loop' => 3000000, 'm_call' => 2000000, 'm_array_rw' => 2000000, 'm_hash' => 2000000, 'm_foreach' => 2000000,
    'm_concat' => 2000000, 'm_strfn' => 500000, 'm_prop' => 2000000, 'm_method' => 2000000, 'm_closure' => 2000000,
    'm_cuf' => 1000000, 'm_isset' => 2000000, 'm_strcmp' => 2000000];
$rounds = (int) ($argv[1] ?? 10);
foreach ($benches as $name => $n) {
    for ($r = 1; $r <= $rounds; $r++) {
        $t = hrtime(true);
        $result = $name($n);
        $ns = hrtime(true) - $t;
        echo $name, ' ', $r, ' ', number_format($ns / $n, 2, '.', ''), ' ', $result, "\n";
    }
}
