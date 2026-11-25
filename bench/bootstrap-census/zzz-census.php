<?php
// Post-bootstrap graph & mutation census barrier hook.
if (isset($_SERVER['HTTP_X_CENSUS_PAGE']) && function_exists('truffle_bootstrap_census')) {
    $census_barrier = $_SERVER['HTTP_X_CENSUS_BARRIER'] ?? 'wp_loaded';
    $census_page = $_SERVER['HTTP_X_CENSUS_PAGE'];
    add_action($census_barrier, function () use ($census_barrier, $census_page) {
        static $fired = false;
        if (!$fired) {
            $fired = true;
            truffle_bootstrap_census($census_barrier, $census_page);
        }
    }, PHP_INT_MAX);
}
