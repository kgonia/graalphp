<?php
// Micro kernels: one WordPress-style primitive each, 200k operations per call, so the per-operation cost of the
// engines can be compared primitive by primitive (bench/micro/run.php; bench/gate1/compare.sh with
// DRIVER=bench/micro/run.php). Each returns a value that depends on every operation.

final class M_Point {
    public int $x = 0;
    public $tag = 'p';
    public function __construct(public int $y = 0) {
    }
    public function sum(int $add): int {
        return $this->x + $this->y + $add;
    }
    public static function twice(int $v): int {
        return $v * 2;
    }
}

function m_add(int $a, int $b): int {
    return $a + $b;
}

function m_variadic($first, ...$rest) {
    return $first + count($rest);
}

/** An array built at run time: PHP's optimizer folds lookups in literal arrays, which no real code gets. */
function m_row(array $pairs): array {
    $row = [];
    foreach ($pairs as $key => $value) {
        $row[$key] = $value;
    }
    return $row;
}

const M_N = 200000;

return [
    // calls
    'call_function' => static function (): int {
        $s = 0;
        for ($i = 0; $i < M_N; $i++) {
            $s = m_add($s, $i);
        }
        return $s;
    },
    'call_method' => static function (): int {
        $p = new M_Point(3);
        $s = 0;
        for ($i = 0; $i < M_N; $i++) {
            $s += $p->sum($i & 7);
        }
        return $s;
    },
    'call_static' => static function (): int {
        $s = 0;
        for ($i = 0; $i < M_N; $i++) {
            $s += M_Point::twice($i & 7);
        }
        return $s;
    },
    'call_closure' => static function (): int {
        $k = 3;
        $f = static function (int $v) use ($k): int {
            return $v + $k;
        };
        $s = 0;
        for ($i = 0; $i < M_N; $i++) {
            $s += $f($i & 7);
        }
        return $s;
    },
    'call_user_func_array' => static function (): int {
        $callbacks = ['m_add', [new M_Point(1), 'sum'], ['M_Point', 'twice'], static fn(int $v): int => $v + 1];
        $args = [[1, 2], [5], [7], [9]];
        $s = 0;
        for ($i = 0; $i < M_N; $i++) {
            $k = $i & 3;
            $s += call_user_func_array($callbacks[$k], $args[$k]);
        }
        return $s;
    },
    'call_variadic' => static function (): int {
        $s = 0;
        for ($i = 0; $i < M_N; $i++) {
            $s += m_variadic($i, 1, 2);
        }
        return $s;
    },
    'call_builtin' => static function (): int {
        $s = 0;
        $str = 'hello world';
        for ($i = 0; $i < M_N; $i++) {
            $s += strlen($str) + abs($i - 5);
        }
        return $s;
    },
    // objects
    'new_object' => static function (): int {
        $s = 0;
        for ($i = 0; $i < M_N; $i++) {
            $p = new M_Point($i);
            $s += $p->y;
        }
        return $s;
    },
    'prop_rw' => static function (): string {
        $p = new M_Point();
        for ($i = 0; $i < M_N; $i++) {
            $p->x = $p->x + ($i & 3);
            $p->tag = $i & 1 ? 'a' : 'b';
        }
        return $p->x . $p->tag;
    },
    // arrays
    'array_str_read' => static function (): int {
        $a = m_row(['id' => 1, 'title' => 2, 'content' => 3, 'status' => 4, 'author' => 5, 'date' => 6]);
        $keys = ['id', 'title', 'content', 'status', 'author', 'date'];
        $s = 0;
        for ($i = 0; $i < M_N; $i++) {
            $s += $a[$keys[$i % 6]];
        }
        return $s;
    },
    'array_str_literal' => static function (): int {
        $a = m_row(['id' => 1, 'title' => 2, 'content' => 3]);
        $s = 0;
        for ($i = 0; $i < M_N; $i++) {
            $s += $a['title'] + $a['content'];
        }
        return $s;
    },
    'array_str_write' => static function (): int {
        $a = [];
        for ($i = 0; $i < M_N; $i++) {
            $a['k' . ($i & 63)] = $i;
        }
        return count($a) + $a['k5'];
    },
    'array_build_assoc' => static function (): int {
        $s = 0;
        for ($i = 0; $i < M_N / 4; $i++) {
            $row = ['function' => 'f', 'accepted_args' => $i & 3, 'priority' => 10];
            $s += $row['accepted_args'];
        }
        return $s;
    },
    'isset_str' => static function (): int {
        $a = m_row(['a' => 1, 'b' => null, 'c' => 3]);
        $s = 0;
        for ($i = 0; $i < M_N; $i++) {
            $s += isset($a['a']) + isset($a['b']) + isset($a['zz']) + ($a['c'] ?? 0);
        }
        return $s;
    },
    'foreach_assoc' => static function (): int {
        $a = [];
        for ($i = 0; $i < 50; $i++) {
            $a['key' . $i] = $i;
        }
        $s = 0;
        for ($r = 0; $r < M_N / 50; $r++) {
            foreach ($a as $k => $v) {
                $s += $v;
            }
        }
        return $s;
    },
    'foreach_list' => static function (): int {
        $a = range(0, 99);
        $s = 0;
        for ($r = 0; $r < M_N / 100; $r++) {
            foreach ($a as $v) {
                $s += $v;
            }
        }
        return $s;
    },
    'array_copy_write' => static function (): int {
        $base = m_row(['a' => 1, 'b' => 2, 'c' => 3, 'd' => 4]);
        $s = 0;
        for ($i = 0; $i < M_N / 4; $i++) {
            $copy = $base;
            $copy['a'] = $i;
            $s += $copy['a'] + $base['a'];
        }
        return $s;
    },
    // strings
    'str_concat' => static function (): int {
        $s = '';
        for ($i = 0; $i < M_N; $i++) {
            $s .= 'ab';
        }
        return strlen($s);
    },
    'str_interpolate' => static function (): int {
        $n = 0;
        for ($i = 0; $i < M_N; $i++) {
            $n += strlen("<li id=\"item-$i\">x</li>");
        }
        return $n;
    },
    'str_funcs' => static function (): int {
        $text = 'The quick brown fox jumps over the lazy dog';
        $n = 0;
        for ($i = 0; $i < M_N / 4; $i++) {
            $n += strpos($text, 'fox') + strlen(substr($text, $i & 15, 5)) + strlen(trim(' x ')) + strlen(strtolower('ABC'));
        }
        return $n;
    },
    'str_compare' => static function (): int {
        $words = ['alpha', 'beta', 'gamma', 'delta'];
        $n = 0;
        for ($i = 0; $i < M_N; $i++) {
            $n += $words[$i & 3] === 'gamma' ? 1 : 0;
        }
        return $n;
    },
    'switch_str' => static function (): int {
        $words = ['p', 'div', 'span', 'a', 'img'];
        $n = 0;
        for ($i = 0; $i < M_N; $i++) {
            switch ($words[$i % 5]) {
                case 'p': $n += 1; break;
                case 'div': $n += 2; break;
                case 'span': $n += 3; break;
                default: $n += 4;
            }
        }
        return $n;
    },
];
