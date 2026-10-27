<?php
// The start of a timed request (auto_prepend_file of server.ini) and its end, after every shutdown function: a
// JSON line per request in /srv/log.jsonl
// (run.sh copies it to target/bootshare). mu-plugin.php adds the marks between. Only requests with
// an X-Bootshare header (the page's name) are timed.
if (isset($_SERVER['HTTP_X_BOOTSHARE'])) {
    $GLOBALS['bootshare_marks'] = ['start' => hrtime(true)];
    register_shutdown_function(function () {
        // registered during shutdown, so it runs after WordPress's shutdown hook
        register_shutdown_function(function () {
            $marks = $GLOBALS['bootshare_marks'];
            $marks['end'] = hrtime(true);
            $line = ['page' => $_SERVER['HTTP_X_BOOTSHARE'], 'status' => http_response_code(),
                'peak' => memory_get_peak_usage(), 'boot_peak' => $GLOBALS['bootshare_boot_peak'] ?? null,
                'boot_live' => $GLOBALS['bootshare_boot_live'] ?? null];
            foreach ($marks as $name => $time) {
                $line['ms'][$name] = ($time - $marks['start']) / 1e6;
            }
            file_put_contents('/srv/log.jsonl', json_encode($line) . "\n", FILE_APPEND | LOCK_EX);
        });
    });
}
