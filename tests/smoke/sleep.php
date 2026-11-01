<?php
// sleep, usleep, time_nanosleep, time_sleep_until (ext/standard/basic_functions.c): WooCommerce's session and
// batch-processing locks wait with usleep() when another request holds them
set_error_handler(function ($type, $message, $file, $line) {
    echo "warning $type: $message (line $line)\n";
    return true;
});
$t = hrtime(true);
var_dump(sleep(0), usleep(20000), time_nanosleep(0, 10000000));
var_dump((hrtime(true) - $t) >= 30000000);
var_dump(time_sleep_until(microtime(true) - 10));
$t = microtime(true);
var_dump(time_sleep_until($t + 0.02), microtime(true) >= $t + 0.019);
foreach ([fn() => time_nanosleep(0, 1000000000), fn() => sleep(-1), fn() => usleep(-1), fn() => time_nanosleep(-1, 0), fn() => time_nanosleep(0, -1)] as $f) {
    try {
        $f();
    } catch (ValueError $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}
