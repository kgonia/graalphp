<?php
// ext/curl calls without a network: curl_escape and curl_setopt (one downcall each, plus the binding's work) and
// file:// transfers into a string (curl_exec, whose data comes back through the write callback, an upcall).
// Output as bench/k0/run.php's ("kernel iteration nanoseconds checksum").
// Usage: php curl.php <kernel,kernel,...|all> <iterations>

$file = sys_get_temp_dir() . '/curl-bench-' . getmypid() . '.txt';
file_put_contents($file, str_repeat("line of text\n", 64));
$kernels = [
    'escape' => function () {
        $ch = curl_init();
        $n = 0;
        for ($i = 0; $i < 20000; $i++) {
            $n += strlen(curl_escape($ch, "a b&c=$i/d"));
        }
        return $n;
    },
    'setopt' => function () {
        $ch = curl_init();
        $ok = 0;
        for ($i = 0; $i < 20000; $i++) {
            $ok += curl_setopt($ch, CURLOPT_URL, "http://example.com/$i") ? 1 : 0;
            $ok += curl_setopt($ch, CURLOPT_TIMEOUT, $i % 30 + 1) ? 1 : 0;
        }
        return $ok;
    },
    'transfer' => function () use ($file) {
        $n = 0;
        for ($i = 0; $i < 1000; $i++) {
            $ch = curl_init('file://' . $file);
            curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
            $n += strlen(curl_exec($ch));
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
unlink($file);
