<?php
// preg_* calls on short subjects, where the call itself (pattern cache, downcall, result arrays) is most of the
// cost. Output as bench/k0/run.php's ("kernel iteration nanoseconds checksum").
// Usage: php micro.php <kernel,kernel,...|all> <iterations>

$kernels = [
    'match' => function () {
        $n = 0;
        for ($i = 0; $i < 100000; $i++) {
            $n += preg_match('/(\d+)-(\w+)/', "item $i-x$i end", $m);
            $n += strlen($m[2]);
        }
        return $n;
    },
    'replace' => function () {
        $s = '';
        for ($i = 0; $i < 50000; $i++) {
            $s = preg_replace('/\s+/', ' ', "a  b   c $i    d");
        }
        return $s;
    },
    'split' => function () {
        $n = 0;
        for ($i = 0; $i < 50000; $i++) {
            $n += count(preg_split('/[,;]\s*/', "a, b;c, $i;d"));
        }
        return $n;
    },
    'match_all' => function () {
        $n = 0;
        for ($i = 0; $i < 30000; $i++) {
            $n += preg_match_all('/<(\w+)>/', "<p>x <b>$i</b> <i>y</i></p>", $m);
        }
        return $n;
    },
];
$selected = ($argv[1] ?? 'all') === 'all' ? array_keys($kernels) : explode(',', $argv[1]);
$iterations = (int) ($argv[2] ?? 10);
foreach ($selected as $name) {
    for ($i = 1; $i <= $iterations; $i++) {
        $start = hrtime(true);
        $result = $kernels[$name]();
        $ns = hrtime(true) - $start;
        $text = json_encode($result);
        echo $name, ' ', $i, ' ', $ns, ' ', strlen($text), ':', sprintf('%08x', crc32($text)), "\n";
    }
}
