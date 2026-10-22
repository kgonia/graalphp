<?php
// Stand-ins for WordPress functions the kernels reach outside the fetched files.
// Functions marked "copied" are WordPress's own definitions at the pinned commit; the rest return what a
// default single-site install (en_US, UTF-8, no translations, no persistent object cache) returns, without a
// database. Error-path functions throw, so a kernel that strays onto an error path fails loudly on both engines.

/** Options of a default install; get_option() reads these instead of the database. */
const BENCH_OPTIONS = [
    'blog_charset' => 'UTF-8',
    'use_smilies'  => '1',
    'siteurl'      => 'https://example.org',
    'home'         => 'https://example.org',
];

function get_option($option, $default_value = false) {
    return BENCH_OPTIONS[$option] ?? $default_value;
}

function get_locale() {
    return 'en_US';
}

function determine_locale() {
    return 'en_US';
}

// Without loaded translations, WordPress's translation functions return the text unchanged after the gettext filters,
// which the kernels don't register.
function __($text, $domain = 'default') {
    return $text;
}

function _x($text, $context, $domain = 'default') {
    return $text;
}

function translate($text, $domain = 'default') {
    return $text;
}

function is_admin() {
    return false;
}

function is_multisite() {
    return false;
}

function wp_installing($is_installing = null) {
    return false;
}

function is_feed($feeds = '') {
    return false;
}

function is_comment_feed() {
    return false;
}

function is_trackback() {
    return false;
}

/** The non-persistent object cache: a per-request array, as WP_Object_Cache is without a backend. */
function wp_cache_get($key, $group = '', $force = false, &$found = null) {
    global $bench_cache;
    $found = isset($bench_cache[$group][$key]);
    return $found ? $bench_cache[$group][$key] : false;
}

function wp_cache_set($key, $data, $group = '', $expire = 0) {
    global $bench_cache;
    $bench_cache[$group][$key] = $data;
    return true;
}

function wp_cache_add($key, $data, $group = '', $expire = 0) {
    global $bench_cache;
    if (isset($bench_cache[$group][$key])) {
        return false;
    }
    $bench_cache[$group][$key] = $data;
    return true;
}

function wp_cache_delete($key, $group = '') {
    global $bench_cache;
    unset($bench_cache[$group][$key]);
    return true;
}

function _doing_it_wrong($function_name, $message, $version) {
    throw new LogicException("_doing_it_wrong: $function_name: $message");
}

function _deprecated_argument($function_name, $version, $message = '') {
    throw new LogicException("_deprecated_argument: $function_name");
}

function _deprecated_hook($hook, $version, $replacement = '', $message = '') {
    throw new LogicException("_deprecated_hook: $hook");
}

function wp_trigger_error($function_name, $message, $error_level = E_USER_NOTICE) {
    throw new LogicException("wp_trigger_error: $function_name: $message");
}

function wp_die($message = '', $title = '', $args = array()) {
    throw new LogicException('wp_die');
}

// copied: wp-includes/functions.php
function is_utf8_charset( $blog_charset = null ) {
	return _is_utf8_charset( $blog_charset ?? get_option( 'blog_charset' ) );
}

// copied: wp-includes/compat.php
function _is_utf8_charset( $charset_slug ) {
	if ( ! is_string( $charset_slug ) ) {
		return false;
	}

	return (
		0 === strcasecmp( 'UTF-8', $charset_slug ) ||
		0 === strcasecmp( 'UTF8', $charset_slug )
	);
}

// copied: wp-includes/functions.php
function wp_allowed_protocols() {
	static $protocols = array();

	if ( empty( $protocols ) ) {
		$protocols = array( 'http', 'https', 'ftp', 'ftps', 'mailto', 'news', 'irc', 'irc6', 'ircs', 'gopher', 'nntp', 'feed', 'telnet', 'mms', 'rtsp', 'sms', 'svn', 'tel', 'fax', 'xmpp', 'webcal', 'urn' );
	}

	if ( ! did_action( 'wp_loaded' ) ) {
		$protocols = array_unique( (array) apply_filters( 'kses_allowed_protocols', $protocols ) );
	}

	return $protocols;
}

// copied: wp-includes/load.php
function absint( $maybeint ): int {
	if ( is_float( $maybeint ) ) {
		if ( ! is_finite( $maybeint ) ) {
			// Casting `NAN` or `INF` to int has produced `0` since PHP 7.0.
			return 0;
		}

		if ( $maybeint <= (float) PHP_INT_MIN || $maybeint >= (float) PHP_INT_MAX ) {
			// Casting a float beyond the integer range is unreliable and warns as of PHP 8.5.
			return PHP_INT_MAX;
		}
	}

	$maybeint = (int) $maybeint;

	if ( PHP_INT_MIN === $maybeint ) {
		// `abs( PHP_INT_MIN )` overflows to a float, as `PHP_INT_MAX` is one less than `-PHP_INT_MIN`.
		return PHP_INT_MAX;
	}

	return abs( $maybeint );
}

// copied: wp-includes/load.php
function is_wp_error( $thing ) {
	$is_wp_error = ( $thing instanceof WP_Error );

	if ( $is_wp_error ) {
		do_action( 'is_wp_error_instance', $thing );
	}

	return $is_wp_error;
}

// copied: wp-includes/http.php
function wp_parse_url( $url, $component = -1 ) {
	$to_unset = array();
	$url      = (string) $url;

	if ( str_starts_with( $url, '//' ) ) {
		$to_unset[] = 'scheme';
		$url        = 'placeholder:' . $url;
	} elseif ( str_starts_with( $url, '/' ) ) {
		$to_unset[] = 'scheme';
		$to_unset[] = 'host';
		$url        = 'placeholder://placeholder' . $url;
	}

	$parts = parse_url( $url );

	if ( false === $parts ) {
		// Parsing failure.
		return $parts;
	}

	// Remove the placeholder values.
	foreach ( $to_unset as $key ) {
		unset( $parts[ $key ] );
	}

	return _get_component_from_parsed_url_array( $parts, $component );
}

// copied: wp-includes/http.php
function _get_component_from_parsed_url_array( $url_parts, $component = -1 ) {
	if ( -1 === $component ) {
		return $url_parts;
	}

	$key = _wp_translate_php_url_constant_to_key( $component );
	if ( false !== $key && is_array( $url_parts ) && isset( $url_parts[ $key ] ) ) {
		return $url_parts[ $key ];
	} else {
		return null;
	}
}

// copied: wp-includes/http.php
function _wp_translate_php_url_constant_to_key( $constant ) {
	$translation = array(
		PHP_URL_SCHEME   => 'scheme',
		PHP_URL_HOST     => 'host',
		PHP_URL_PORT     => 'port',
		PHP_URL_USER     => 'user',
		PHP_URL_PASS     => 'pass',
		PHP_URL_PATH     => 'path',
		PHP_URL_QUERY    => 'query',
		PHP_URL_FRAGMENT => 'fragment',
	);

	return $translation[ $constant ] ?? false;
}
