<?php
// CtxB of the paired two-context run (as bench/b1-precheck/probe-b ProbeBMain hooks-ctxb): the same shared code
// as hooks-a.php, a different pluggable bench_upper_first, and a callback on the special 'all' hook, which makes
// every apply_filters/do_action also run _wp_call_all_hook and WP_Hook::do_all_hook.

require $b1_wordpress . '/wp-includes/plugin.php';

function bench_upper_first(string $value): string {
    return 'B:' . ucfirst($value);
}

$b1_all_calls = 0;
add_filter('all', static function () use (&$b1_all_calls) {
    $b1_all_calls++;
});

return require __DIR__ . '/hooks-kernel.php';
