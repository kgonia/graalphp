<?php
// The PHP oracle for B1BenchMain's checksums: runs the same driver files on PHP itself, one variant per process.
// Usage (from the repository root):
//   php bench/b1-week2/php/oracle.php hooks-a|hooks-b <wordpress> <corpus.php>
//   php bench/b1-week2/php/oracle.php hot-a|hot-b <wordpress>
//   php bench/b1-week2/php/oracle.php k0
// Prints "<name> <checksum>" lines; the formats match B1BenchMain's.
error_reporting(E_ALL);

/** bench/gate1/run.php */
function bench_checksum(mixed $result): string {
    $text = bench_render($result);
    return strlen($text) . ':' . sprintf('%08x', crc32($text));
}

function bench_render(mixed $value): string {
    if (is_array($value)) {
        $out = '[';
        foreach ($value as $key => $item) {
            $out .= $key . '=>' . bench_render($item) . ',';
        }
        return $out . ']';
    }
    if (is_object($value)) {
        return get_class($value) . bench_render(get_object_vars($value));
    }
    if (is_bool($value)) {
        return $value ? 'true' : 'false';
    }
    if ($value === null) {
        return 'null';
    }
    return gettype($value) . ':' . $value;
}

/** bench/gate1/kernels.php bench_headings */
function b1_headings(array $corpus): array {
    $headings = [];
    foreach ($corpus as [$title, $content]) {
        $headings[] = $title;
        preg_match_all('/<h[1-6][^>]*>(.*?)<\/h[1-6]>/s', $content, $matches);
        foreach ($matches[1] as $heading) {
            $headings[] = $heading;
        }
    }
    return $headings;
}

$mode = $argv[1];
if ($mode === 'hooks-a' || $mode === 'hooks-b') {
    $b1_wordpress = rtrim($argv[2], '/');
    $corpus = require $argv[3];
    $factory = require __DIR__ . '/' . $mode . '.php';
    $kernel = $factory(b1_headings($corpus));
    for ($i = 0; $i < 2; $i++) {
        echo $mode, ' ', bench_checksum($kernel($corpus)), "\n";
    }
    echo $mode, '-all-calls ', $b1_all_calls ?? 0, "\n";
} elseif ($mode === 'hot-a' || $mode === 'hot-b') {
    $b1_wordpress = rtrim($argv[2], '/');
    $b1_ctxb = $mode === 'hot-b';
    require __DIR__ . '/../../b1-precheck/probe-b/php-ref/hot-fns.php';
    $hot = require __DIR__ . '/hot.php';
    foreach ($hot['kernels'] as $name => $kernel) {
        echo $name, ($b1_ctxb ? '-ctxb' : ''), ' ', $hot['run']($kernel, 250), "\n";
    }
    echo $mode, '-all-calls ', $b1_all_calls, "\n";
} elseif ($mode === 'k0') {
    $full = require __DIR__ . '/../../k0/kernels.php';
    $small = [
        'fannkuch' => fn() => k0_fannkuch(7), 'spectralnorm' => fn() => k0_spectralnorm(50),
        'binarytrees' => fn() => k0_binarytrees(6), 'nbody' => fn() => k0_nbody(1000), 'fib' => fn() => k0_fib(15),
        'intloop' => fn() => k0_intloop(10000), 'floatloop' => fn() => k0_floatloop(10000),
        'mandelbrot' => fn() => k0_mandelbrot(40), 'sieve' => fn() => k0_sieve(5000), 'bubblesort' => fn() => k0_bubblesort(100),
    ];
    foreach ($small as $name => $kernel) {
        $text = json_encode($kernel());
        echo $name, '-small ', strlen($text), ':', sprintf('%08x', crc32($text)), "\n";
    }
    foreach ($full as $name => $kernel) {
        $text = json_encode($kernel());
        echo $name, ' ', strlen($text), ':', sprintf('%08x', crc32($text)), "\n";
    }
} else {
    fwrite(STDERR, "unknown mode $mode\n");
    exit(2);
}
