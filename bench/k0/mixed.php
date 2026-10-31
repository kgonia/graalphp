<?php
// K0 kernels interleaved in one process: each round runs every selected kernel once, so each kernel's code runs
// with the interpreter's and the host compiler's profiles shared with the others, as an application's code does.
// run.php instead runs one kernel's iterations back to back (and ab-each.sh gives each kernel a JVM of its own).
// Same output as run.php: "kernel round nanoseconds checksum".
// Usage: php mixed.php <kernel,kernel,...|all> <rounds>

$kernels = require __DIR__ . '/kernels.php';
$selected = ($argv[1] ?? 'all') === 'all' ? array_keys($kernels) : explode(',', $argv[1]);
$rounds = (int) ($argv[2] ?? 10);

for ($i = 1; $i <= $rounds; $i++) {
    foreach ($selected as $name) {
        $start = hrtime(true);
        $result = $kernels[$name]();
        $ns = hrtime(true) - $start;
        $text = json_encode($result);
        echo $name, ' ', $i, ' ', $ns, ' ', strlen($text), ':', sprintf('%08x', crc32($text)), "\n";
    }
}
