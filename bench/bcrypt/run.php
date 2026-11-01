<?php
// bcrypt as a WordPress login spends it: password_hash and password_verify of the key wp_hash_password and
// wp_check_password make (base64 of an HMAC-SHA384, 64 bytes) at PHP's default cost (12), and crypt() at a fixed
// salt. Output as bench/gate1/run.php's ("kernel iteration nanoseconds checksum"), so bench/gate1/ab.py summarizes
// it, and a fifth field: the CPU time this thread ran during the call, in ns (/proc/thread-self/schedstat; 0 where
// there is none), which a loaded host's preemption of the VM's CPUs inflates less than the wall time. Iteration 1 is
// the first call.
// Usage: php run.php <kernel,kernel,...|all> <iterations>

$key = base64_encode(hash_hmac('sha384', 'admin', 'wp-sha384', true));
$stored = password_hash($key, PASSWORD_BCRYPT);
$kernels = [
    'hash' => fn() => strlen(password_hash($key, PASSWORD_BCRYPT)),
    'verify' => fn() => password_verify($key, $stored),
    'crypt10' => fn() => crypt($key, '$2y$10$abcdefghijklmnopqrstuu'),
    'crypt12' => fn() => crypt($key, '$2y$12$abcdefghijklmnopqrstuu'),
];

function cpu(): int
{
    $stat = @file_get_contents('/proc/thread-self/schedstat');
    return $stat === false ? 0 : (int) explode(' ', $stat)[0];
}

$selected = ($argv[1] ?? 'all') === 'all' ? array_keys($kernels) : explode(',', $argv[1]);
$iterations = (int) ($argv[2] ?? 10);
foreach ($selected as $name) {
    $kernel = $kernels[$name];
    for ($i = 1; $i <= $iterations; $i++) {
        $cpu = cpu();
        $start = hrtime(true);
        $result = $kernel();
        $ns = hrtime(true) - $start;
        $cpu = cpu() - $cpu;
        $text = json_encode($result);
        echo $name, ' ', $i, ' ', $ns, ' ', strlen($text), ':', sprintf('%08x', crc32($text)), ' ', $cpu, "\n";
    }
}
