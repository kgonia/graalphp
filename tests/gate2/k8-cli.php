<?php
/*
 * K8's loop without a server (tests/gate2/k8-cli.sh, wiki/beat-php.md): one PHP process serves one request of N
 * renders (tests/gate2/k8-plugin.php), so WordPress is booted once and its context lives as long as the loop. The
 * request is the one nginx hands the FastCGI servers for the page: the same host, so the same URLs in the page.
 *
 * Usage: php k8-cli.php PAGE N [WORDPRESS_DIR]      PAGE as in a URL: /, /?p=1
 */
$k8_page = $argv[1] ?? '/';
$k8_root = $argv[3] ?? '/srv/target/gate2/wordpress';
$k8_query = (string) parse_url($k8_page, PHP_URL_QUERY);
parse_str($k8_query, $_GET);
$_GET['k8'] = (string) (int) ($argv[2] ?? 100);
$_REQUEST = $_GET;
$_SERVER['HTTP_HOST'] = 'localhost';
$_SERVER['SERVER_NAME'] = 'localhost';
$_SERVER['SERVER_PORT'] = '8080';
$_SERVER['SERVER_PROTOCOL'] = 'HTTP/1.1';
$_SERVER['REQUEST_METHOD'] = 'GET';
$_SERVER['REQUEST_URI'] = $k8_page;
$_SERVER['QUERY_STRING'] = $k8_query;
$_SERVER['SCRIPT_NAME'] = '/index.php';
$_SERVER['PHP_SELF'] = '/index.php';
$_SERVER['DOCUMENT_ROOT'] = $k8_root;
$_SERVER['SCRIPT_FILENAME'] = $k8_root . '/index.php';
$_SERVER['REMOTE_ADDR'] = '127.0.0.1';
require $k8_root . '/index.php';
