<?php
// Gate 1 driver: runs WordPress kernels for N iterations and prints, per iteration,
// "kernel iteration nanoseconds checksum". The same file runs on PHP and on the Truffle PHP, which adds a fifth
// column: the nanoseconds spent in native code (PCRE2) during the iteration, for the native-share accounting.
// Usage: php run.php <kernel,kernel,...|all> <iterations> [dump]   (dump: also print each result's rendering)

error_reporting(E_ALL);
set_error_handler(static function (int $level, string $message, string $file, int $line): bool {
    throw new ErrorException($message, 0, $level, $file, $line);
});

define('ABSPATH', __DIR__ . '/wordpress/');
define('WPINC', 'wp-includes');

require __DIR__ . '/stubs.php';
require __DIR__ . '/stubs-copied.php';
require ABSPATH . WPINC . '/plugin.php';
require ABSPATH . WPINC . '/utf8.php';
require ABSPATH . WPINC . '/formatting.php';
require ABSPATH . WPINC . '/kses.php';
require ABSPATH . WPINC . '/shortcodes.php';
require ABSPATH . WPINC . '/blocks.php';
require ABSPATH . WPINC . '/class-wp-block-parser.php';
require ABSPATH . WPINC . '/class-wp-token-map.php';
require ABSPATH . WPINC . '/html-api/html5-named-character-references.php';
require ABSPATH . WPINC . '/html-api/class-wp-html-span.php';
require ABSPATH . WPINC . '/html-api/class-wp-html-text-replacement.php';
require ABSPATH . WPINC . '/html-api/class-wp-html-attribute-token.php';
require ABSPATH . WPINC . '/html-api/class-wp-html-decoder.php';
require ABSPATH . WPINC . '/html-api/class-wp-html-tag-processor.php';

$corpus  = require __DIR__ . '/corpus.php';
$kernels = require __DIR__ . '/kernels.php';

$selected   = ($argv[1] ?? 'all') === 'all' ? array_keys($kernels) : explode(',', $argv[1]);
$iterations = (int) ($argv[2] ?? 10);
$dump       = ($argv[3] ?? '') === 'dump';
$native     = function_exists('truffle_native_ns');

foreach ($selected as $name) {
    $kernel = $kernels[$name];
    for ($i = 1; $i <= $iterations; $i++) {
        $native0 = $native ? truffle_native_ns() : 0;
        $start   = hrtime(true);
        $result  = $kernel($corpus);
        $ns      = hrtime(true) - $start;
        $extra   = $native ? ' ' . (truffle_native_ns() - $native0) : '';
        echo $name, ' ', $i, ' ', $ns, ' ', bench_checksum($result), $extra, "\n";
        if ($dump) {
            echo bench_render($result), "\n";
        }
    }
}

/** Length and CRC-32 of a canonical rendering of the kernel's result; computed outside the timed region. */
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
