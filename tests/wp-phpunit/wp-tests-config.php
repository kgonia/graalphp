<?php
/*
 * WordPress's PHPUnit suite configuration for both engines; tests/wp-phpunit/setup.sh copies it to the root of
 * target/wp-phpunit/wordpress-develop. It is wordpress-develop's sample with the database name taken from the
 * environment, so each engine has its own database and both can run at once.
 */

define( 'ABSPATH', __DIR__ . '/src/' );
define( 'WP_DEFAULT_THEME', 'default' );
define( 'WP_DEBUG', true );

// run.sh sets WP_PHPUNIT_DB (wp_phpunit_php, wp_phpunit_truffle, ...); the user reaches only wp\_phpunit%
define( 'DB_NAME', getenv( 'WP_PHPUNIT_DB' ) ?: 'wp_phpunit' );
define( 'DB_USER', 'wp_phpunit' );
define( 'DB_PASSWORD', 'wp_phpunit' );
// gate2-mariadb on Docker's default bridge
define( 'DB_HOST', getenv( 'WP_PHPUNIT_DB_HOST' ) ?: '172.17.0.2:3306' );
define( 'DB_CHARSET', 'utf8mb4' );
define( 'DB_COLLATE', '' );

define( 'AUTH_KEY',         'put your unique phrase here' );
define( 'SECURE_AUTH_KEY',  'put your unique phrase here' );
define( 'LOGGED_IN_KEY',    'put your unique phrase here' );
define( 'NONCE_KEY',        'put your unique phrase here' );
define( 'AUTH_SALT',        'put your unique phrase here' );
define( 'SECURE_AUTH_SALT', 'put your unique phrase here' );
define( 'LOGGED_IN_SALT',   'put your unique phrase here' );
define( 'NONCE_SALT',       'put your unique phrase here' );

$table_prefix = 'wptests_';

define( 'WP_TESTS_DOMAIN', 'example.org' );
define( 'WP_TESTS_EMAIL', 'admin@example.org' );
define( 'WP_TESTS_TITLE', 'Test Blog' );

// the engine under test: in the Truffle PHP container `php` is Truffle PHP (run.sh mounts its launcher there), so
// install.php and PHPUnit's process-isolated tests run on the same engine as the suite
define( 'WP_PHP_BINARY', 'php' );

define( 'WPLANG', '' );
