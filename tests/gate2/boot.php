<?php
// Gate 2's first milestone: load WordPress in CLI, as wp-cli does, and report how far the boot got.
$_SERVER['HTTP_HOST'] = 'localhost';
$_SERVER['SERVER_NAME'] = 'localhost';
$_SERVER['REQUEST_URI'] = '/';
$_SERVER['REQUEST_METHOD'] = 'GET';
$_SERVER['SCRIPT_NAME'] = '/index.php';
$_SERVER['PHP_SELF'] = '/index.php';
require __DIR__ . '/../../target/gate2/wordpress/wp-load.php';
echo "booted: WordPress ", $GLOBALS['wp_version'], "\n";
