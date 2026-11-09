<?php
// The count audit's fixture for built-ins that make arrays of objects (audit points, -Dphp.rc.audit.every=1):
// each result is kept in $r while the source is dropped, then $r is dropped. Any EARLY or LATE finding names the
// built-in by the line that made the objects' holder. Compare the output with PHP 8.5's (OPcache off): on 2026-11-06
// the late destructions left are array_splice's removed element, results of callbacks called from built-ins
// (array_map, array_reduce, min/max...), array_column, iterator_to_array, func_get_args, casts, and closures' captures.
class D { public $n; function __construct($n) { $this->n = $n; } function __destruct() { echo "~{$this->n} "; } }
function src($tag) { return ['a' => new D("$tag.a"), 'b' => new D("$tag.b"), 'c' => new D("$tag.c")]; }
$cases = [
    'array_values' => fn($a) => array_values($a),
    'array_merge' => fn($a) => array_merge($a, ['x' => 1]),
    'array_merge_recursive' => fn($a) => array_merge_recursive($a, []),
    'array_replace' => fn($a) => array_replace($a, []),
    'array_slice' => fn($a) => array_slice($a, 1),
    'array_splice' => function ($a) { $b = $a; array_splice($b, 1, 1); return $b; },
    'array_filter' => fn($a) => array_filter($a, fn($d) => $d->n !== ''),
    'array_map' => fn($a) => array_map(fn($d) => $d, $a),
    'array_map_new' => fn($a) => array_map(fn($d) => new D($d->n . '.new'), $a),
    'array_reverse' => fn($a) => array_reverse($a),
    'array_combine' => fn($a) => array_combine(['p', 'q', 'r'], $a),
    'array_fill_keys' => fn($a) => array_fill_keys(['p', 'q'], $a['a']),
    'array_fill' => fn($a) => array_fill(0, 2, $a['b']),
    'array_pad' => fn($a) => array_pad($a, 5, $a['c']),
    'array_chunk' => fn($a) => array_chunk($a, 2),
    'array_column' => fn($a) => array_column([['o' => $a['a']], ['o' => $a['b']]], 'o'),
    // array_unique($a, SORT_REGULAR) with objects throws here (Object ... could not be converted to string): not a count issue
    'array_diff_key' => fn($a) => array_diff_key($a, ['b' => 1]),
    'array_intersect_key' => fn($a) => array_intersect_key($a, ['a' => 1, 'c' => 1]),
    'array_udiff' => fn($a) => array_udiff($a, [], fn($x, $y) => 0),
    'array_walk' => function ($a) { array_walk($a, function (&$d, $k) {}); return $a; },
    'sort' => function ($a) { usort($a, fn($x, $y) => strcmp($x->n, $y->n)); return $a; },
    'uasort' => function ($a) { uasort($a, fn($x, $y) => strcmp($x->n, $y->n)); return $a; },
    'array_shift' => function ($a) { $b = $a; array_shift($b); return $b; },
    'array_pop' => function ($a) { $b = $a; array_pop($b); return $b; },
    'array_unshift' => function ($a) { $b = $a; array_unshift($b, new D('u')); return $b; },
    'compact' => function ($a) { $x = $a['a']; return compact('x'); },
    'iterator_to_array' => fn($a) => iterator_to_array(new ArrayIterator($a)),
    'ArrayObject' => fn($a) => (new ArrayObject($a))->getArrayCopy(),
    'func_get_args' => function ($a) { $f = function () { return func_get_args(); }; return $f(...array_values($a)); },
    'spread' => function ($a) { $f = fn(...$xs) => $xs; return $f(...$a); },
    'get_object_vars' => function ($a) { $o = new stdClass; $o->p = $a['a']; return get_object_vars($o); },
    'cast' => function ($a) { return (array) (object) $a; },
    'array_search_keys' => fn($a) => array_keys($a),
    'current_next' => function ($a) { $b = $a; next($b); return [current($b)]; },
    'min_max' => fn($a) => [max(array_values($a))],
    'array_rand' => fn($a) => [$a[array_key_first($a)]],
    'array_reduce' => fn($a) => [array_reduce($a, fn($c, $d) => $d)],
    'json_roundtrip' => fn($a) => json_decode(json_encode(['k' => 1]), true) + $a,
    'unserialize' => fn($a) => unserialize(serialize($a)),
    'var_export' => function ($a) { var_export([], true); return $a; },
    'preg_replace_callback' => function ($a) { $keep = []; preg_replace_callback('/./', function ($m) use (&$keep, $a) { $keep[] = $a['a']; return ''; }, 'xy'); return $keep; },
];
foreach ($cases as $name => $f) {
    echo "$name: ";
    $r = $f(src($name));
    echo "| ";
    unset($r);
    echo "\n";
}
echo "end\n";
