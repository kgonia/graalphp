<?php
/*
 * PHPUnit's launcher for any composer project, run from the project's root by tests/wp-phpunit/run.sh on both
 * engines. It does what vendor/phpunit/phpunit/phpunit does, without what of it Truffle PHP can't run yet: the
 * "#!/usr/bin/env php" line before <?php declare(strict_types=1) ("strict_types declaration must be the very first
 * statement"), the environment checks (the first is version_compare(PHP_VERSION, PHP_VERSION, '='), a ValueError:
 * '=' isn't among Truffle PHP's operators; the next wants ext-dom, libxml, tokenizer and xmlwriter) and getopt().
 * With PHPUNIT_CONFIG_SERIALIZED set, the XML configuration is read from config-export.php's output instead
 * (ConfigLoader.php), since loading XML takes ext-dom. shims.php declares the built-ins the runner needs that the
 * engine lacks (none on PHP).
 */
if ( ! ini_get( 'date.timezone' ) ) {
	ini_set( 'date.timezone', 'UTC' );
}
if ( '0' !== getenv( 'WP_PHPUNIT_SHIMS' ) ) {
	require __DIR__ . '/shims.php';
}
if ( getenv( 'PHPUNIT_CONFIG_SERIALIZED' ) ) {
	require __DIR__ . '/ConfigLoader.php';
}
define( 'PHPUNIT_COMPOSER_INSTALL', getcwd() . '/vendor/autoload.php' );
require PHPUNIT_COMPOSER_INSTALL;
if ( class_exists( 'PHPUnit\TextUI\Application' ) ) {
	// PHPUnit 10 and later
	exit( ( new PHPUnit\TextUI\Application() )->run( $_SERVER['argv'] ) );
}
PHPUnit\TextUI\Command::main();
