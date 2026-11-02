<?php
// microtime(), uniqid() and REQUEST_TIME_FLOAT read gettimeofday(): to the microsecond. WooCommerce's transient
// versions are microtime() strings, written to the database.
$fine = 0;
for ($i = 0; $i < 50; $i++) {
    [$usec, $sec] = explode(' ', microtime());
    $fine += ((int) round((float) $usec * 1e6)) % 1000 !== 0;
    usleep(37);
}
var_dump($fine > 0);
var_dump(preg_match('/^0\.[0-9]{6}00 [0-9]+$/', microtime()) === 1);
$fine = 0;
for ($i = 0; $i < 50; $i++) {
    $fine += ((int) round(fmod(microtime(true), 1) * 1e6)) % 1000 !== 0;
    usleep(37);
}
var_dump($fine > 0);
// no two ids alike, however fast they are asked for
$ids = [];
for ($i = 0; $i < 2000; $i++) {
    $ids[] = uniqid();
}
var_dump(count(array_unique($ids)) === 2000, strlen($ids[0]));
var_dump(is_float($_SERVER['REQUEST_TIME_FLOAT']), abs($_SERVER['REQUEST_TIME_FLOAT'] - microtime(true)) < 60);
