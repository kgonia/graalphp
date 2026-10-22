<?php
// K0 driver: same output format as bench/gate1/run.php ("kernel iteration nanoseconds checksum"), so
// bench/gate1/summarize.py compares the engines.
// Usage: php run.php <kernel,kernel,...|all> <iterations> [dump]

$kernels = require __DIR__ . '/kernels.php';
$selected = ($argv[1] ?? 'all') === 'all' ? array_keys($kernels) : explode(',', $argv[1]);
$iterations = (int) ($argv[2] ?? 10);
$dump = ($argv[3] ?? '') === 'dump';

foreach ($selected as $name) {
    $kernel = $kernels[$name];
    for ($i = 1; $i <= $iterations; $i++) {
        $start = hrtime(true);
        $result = $kernel();
        $ns = hrtime(true) - $start;
        $text = json_encode($result);
        echo $name, ' ', $i, ' ', $ns, ' ', strlen($text), ':', sprintf('%08x', crc32($text)), "\n";
        if ($dump && $i === 1) {
            echo $text, "\n";
        }
    }
}
