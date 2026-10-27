<?php
// Gate 2's wp-config.php (copied into target/gate2/wordpress by tests/gate2/boot.sh): the MariaDB of
// tests/gate2/db.sh, fixed salts, debug output on.
// tests/gate2/installer.sh installs into a database of each engine's own, named by a request header
define('DB_NAME', in_array($_SERVER['HTTP_X_GATE2_DB'] ?? '', ['wp_install_truffle', 'wp_install_php'], true)
    ? $_SERVER['HTTP_X_GATE2_DB'] : 'wordpress');
define('DB_USER', 'wp');
define('DB_PASSWORD', 'wp');
// boot.sh runs in the gate1 containers; a server with the site in its own file system can reach the database
// container over the Docker bridge instead (GATE2_DB_HOST, as tests/gate2/browse.sh does)
define('DB_HOST', getenv('GATE2_DB_HOST') ?: 'host.docker.internal:3307');
define('DB_CHARSET', 'utf8mb4');
define('DB_COLLATE', '');

define('AUTH_KEY', 'gate2-auth-key');
define('SECURE_AUTH_KEY', 'gate2-secure-auth-key');
define('LOGGED_IN_KEY', 'gate2-logged-in-key');
define('NONCE_KEY', 'gate2-nonce-key');
define('AUTH_SALT', 'gate2-auth-salt');
define('SECURE_AUTH_SALT', 'gate2-secure-auth-salt');
define('LOGGED_IN_SALT', 'gate2-logged-in-salt');
define('NONCE_SALT', 'gate2-nonce-salt');

$table_prefix = 'wp_';

// over HTTP the site is wherever it was requested from (tests/gate2/http.sh sends Host: localhost to both servers)
if (!empty($_SERVER['HTTP_HOST'])) {
    define('WP_HOME', 'http://' . $_SERVER['HTTP_HOST']);
    define('WP_SITEURL', 'http://' . $_SERVER['HTTP_HOST']);
}

// the comparisons can't depend on what api.wordpress.org answers (update checks, the PHP and browser checks)
define('WP_HTTP_BLOCK_EXTERNAL', true);

define('WP_DEBUG', true);
define('WP_DEBUG_DISPLAY', false); // notices would differ: Truffle PHP displays no diagnostics

if (!defined('ABSPATH')) {
    define('ABSPATH', __DIR__ . '/');
}

require_once ABSPATH . 'wp-settings.php';
