<?php
// Cold code (E5): calls each function of a set of target/cold/functions.php's (bench/cold/generate.py) 1 to 20 times.
// Iteration 1 runs set 0: first executions on a cold engine. Iteration 2 runs set 1: first executions again, the
// engine's own code warm. Later iterations run set 0 again: nothing is new. Output as bench/k0/run.php's ("kernel
// iteration nanoseconds checksum"), so bench/gate1/ab.sh and speed.py read it.
// Usage: php run.php cold <iterations>

require __DIR__ . '/../../target/cold/functions.php';

$iterations = (int) ($argv[2] ?? 1);
for ($it = 1; $it <= $iterations; $it++) {
    $start = hrtime(true);
    $text = '';
    $first = $it === 2 ? COLD_COUNT : 0;
    for ($k = $first; $k < $first + COLD_COUNT; $k++) {
        $f = 'cold_' . $k;
        $calls = 1 + ($k * 7) % 20;
        for ($j = 0; $j < $calls; $j++) {
            $text .= $f($j, ($k - $first) % 5) . ';';
        }
    }
    $ns = hrtime(true) - $start;
    echo 'cold ', $it, ' ', $ns, ' ', strlen($text), ':', sprintf('%08x', crc32($text)), "\n";
}
