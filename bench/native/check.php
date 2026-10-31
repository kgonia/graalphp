<?php
// The native image's per-call check: one cheap call of each bound library, timed after warming up. A downcall
// made at run time would take the generic method-handle path and fail the limit: 0.1-0.5 us a call is direct,
// 4.6 us or more generic (2026-10-31, CPUs 2-5).
// Usage: php check.php [limit in ns per call, default 2000]

$limit = (int) ($argv[1] ?? 2000);
$checks = [
    'preg_match (PCRE2)' => function ($n) {
        for ($i = 0; $i < $n; $i++) {
            preg_match('/b/', 'abc');
        }
    },
];
if (function_exists('curl_init')) {
    $checks['curl_escape (libcurl)'] = function ($n) {
        $ch = curl_init();
        for ($i = 0; $i < $n; $i++) {
            curl_escape($ch, 'a b');
        }
    };
}
$failed = false;
foreach ($checks as $name => $check) {
    $check(50000);
    $best = PHP_INT_MAX;
    for ($round = 0; $round < 5; $round++) {
        $start = hrtime(true);
        $check(20000);
        $best = min($best, (hrtime(true) - $start) / 20000);
    }
    $ok = $best <= $limit;
    $failed = $failed || !$ok;
    printf("%s %s: %.0f ns per call (limit %d)\n", $ok ? 'ok  ' : 'FAIL', $name, $best, $limit);
}
exit($failed ? 1 : 0);
