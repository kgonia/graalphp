<?php
// A fixed piece of pure CPU work timed again and again: does the CPU itself run at two speeds?
// Usage: php calib.php SECONDS   prints JSON {"chunks": [[start ns, time ns], ...]}
$seconds = (float) ($argv[1] ?? 30);
function work(int $n): int {
    $x = 0;
    for ($j = 0; $j < $n; $j++) {
        $x = ($x * 31 + $j) & 0xFFFFFFF;
    }
    return $x;
}
// size a chunk at about 2 ms
$n = 20000;
do {
    $n *= 2;
    $t = hrtime(true);
    work($n);
    $d = hrtime(true) - $t;
} while ($d < 2e6);
$chunks = [];
$t0 = hrtime(true);
$end = $t0 + (int) ($seconds * 1e9);
do {
    $t = hrtime(true);
    work($n);
    $u = hrtime(true);
    $chunks[] = [$t - $t0, $u - $t];
} while ($u < $end);
echo json_encode(['n' => $n, 'chunks' => $chunks]), "\n";
