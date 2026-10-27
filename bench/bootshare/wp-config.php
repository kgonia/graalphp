<?php
// wp-config.php of the bootstrap-share site (bench/bootshare/setup.sh): the Gate 2 MariaDB, a database per variant
// (core: no plugins; plugins: popular plugins active), chosen by the X-Bootshare-Db header or, for wp-cli, the
// BOOTSHARE_DB environment variable. Production settings: no debug output, no cron spawned by requests, no
// outgoing HTTP (update checks would time the network).
$bootshare_db = $_SERVER['HTTP_X_BOOTSHARE_DB'] ?? getenv('BOOTSHARE_DB') ?: 'core';
define('DB_NAME', 'wp_bootshare_' . (in_array($bootshare_db, ['core', 'plugins'], true) ? $bootshare_db : 'core'));
define('DB_USER', 'wp');
define('DB_PASSWORD', 'wp');
// run.sh connects over the Docker bridge, to the database container's address
define('DB_HOST', getenv('BOOTSHARE_DB_HOST') ?: 'host.docker.internal:3307');
define('DB_CHARSET', 'utf8mb4');
define('DB_COLLATE', '');

define('AUTH_KEY', 'bootshare-auth-key');
define('SECURE_AUTH_KEY', 'bootshare-secure-auth-key');
define('LOGGED_IN_KEY', 'bootshare-logged-in-key');
define('NONCE_KEY', 'bootshare-nonce-key');
define('AUTH_SALT', 'bootshare-auth-salt');
define('SECURE_AUTH_SALT', 'bootshare-secure-auth-salt');
define('LOGGED_IN_SALT', 'bootshare-logged-in-salt');
define('NONCE_SALT', 'bootshare-nonce-salt');

$table_prefix = 'wp_';

if (!empty($_SERVER['HTTP_HOST'])) {
    define('WP_HOME', 'http://' . $_SERVER['HTTP_HOST']);
    define('WP_SITEURL', 'http://' . $_SERVER['HTTP_HOST']);
}
define('WP_HTTP_BLOCK_EXTERNAL', true);
define('DISABLE_WP_CRON', true);
define('WP_DEBUG', false);

if (!defined('ABSPATH')) {
    define('ABSPATH', __DIR__ . '/');
}

require_once ABSPATH . 'wp-settings.php';
