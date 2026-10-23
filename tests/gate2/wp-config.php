<?php
// Gate 2's wp-config.php (copied into target/gate2/wordpress by tests/gate2/boot.sh): the MariaDB of
// tests/gate2/db.sh, fixed salts, debug output on.
define('DB_NAME', 'wordpress');
define('DB_USER', 'wp');
define('DB_PASSWORD', 'wp');
define('DB_HOST', 'host.docker.internal:3307'); // boot.sh runs in the gate1 containers
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

define('WP_DEBUG', true);
define('WP_DEBUG_DISPLAY', true);

if (!defined('ABSPATH')) {
    define('ABSPATH', __DIR__ . '/');
}

require_once ABSPATH . 'wp-settings.php';
