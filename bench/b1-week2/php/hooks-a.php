<?php
// B1 Week 2 harness, Gate 1 'hooks' (bench/gate1/run.php hooks): the real plugin API from the unmodified
// wp-includes/plugin.php (which requires class-wp-hook.php and class-wp-filter-sentinel.php).
// The host sets $b1_wordpress to the WordPress root and calls the returned factory with the corpus headings.

require $b1_wordpress . '/wp-includes/plugin.php';

// bench/gate1/kernels.php
function bench_upper_first(string $value): string {
    return ucfirst($value);
}

return require __DIR__ . '/hooks-kernel.php';
