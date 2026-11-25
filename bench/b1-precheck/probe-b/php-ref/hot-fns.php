<?php
function _wp_array_get( $input_array, $path, $default_value = null ) {
	// Confirm $path is valid.
	if ( ! is_array( $path ) || 0 === count( $path ) ) {
		return $default_value;
	}

	foreach ( $path as $path_element ) {
		if ( ! is_array( $input_array ) ) {
			return $default_value;
		}

		if ( is_string( $path_element )
			|| is_int( $path_element )
			|| null === $path_element
		) {
			/*
			 * Check if the path element exists in the input array.
			 * We check with `isset()` first, as it is a lot faster
			 * than `array_key_exists()`.
			 */
			if ( isset( $path_element, $input_array[ $path_element ] ) ) {
				$input_array = $input_array[ $path_element ];
				continue;
			}

			/*
			 * If `isset()` returns false, we check with `array_key_exists()`,
			 * which also checks for `null` values.
			 */
			if ( isset( $path_element ) && array_key_exists( $path_element, $input_array ) ) {
				$input_array = $input_array[ $path_element ];
				continue;
			}
		}

		return $default_value;
	}

	return $input_array;
}

function maybe_unserialize( $data ) {
	if ( is_serialized( $data ) ) { // Don't attempt to unserialize data that wasn't serialized going in.
		return @unserialize( trim( $data ) );
	}

	return $data;
}

function is_serialized( $data, $strict = true ) {
	// If it isn't a string, it isn't serialized.
	if ( ! is_string( $data ) ) {
		return false;
	}
	$data = trim( $data );
	if ( 'N;' === $data ) {
		return true;
	}
	if ( strlen( $data ) < 4 ) {
		return false;
	}
	if ( ':' !== $data[1] ) {
		return false;
	}
	if ( $strict ) {
		$lastc = substr( $data, -1 );
		if ( ';' !== $lastc && '}' !== $lastc ) {
			return false;
		}
	} else {
		$semicolon = strpos( $data, ';' );
		$brace     = strpos( $data, '}' );
		// Either ; or } must exist.
		if ( false === $semicolon && false === $brace ) {
			return false;
		}
		// But neither must be in the first X characters.
		if ( false !== $semicolon && $semicolon < 3 ) {
			return false;
		}
		if ( false !== $brace && $brace < 4 ) {
			return false;
		}
	}
	$token = $data[0];
	switch ( $token ) {
		case 's':
			if ( $strict ) {
				if ( '"' !== substr( $data, -2, 1 ) ) {
					return false;
				}
			} elseif ( ! str_contains( $data, '"' ) ) {
				return false;
			}
			// Or else fall through.
		case 'a':
		case 'O':
		case 'E':
			return (bool) preg_match( "/^{$token}:[0-9]+:/s", $data );
		case 'b':
		case 'i':
		case 'd':
			$end = $strict ? '$' : '';
			return (bool) preg_match( "/^{$token}:[0-9.E+-]+;$end/", $data );
	}
	return false;
}

function wp_parse_args( $args, $defaults = array() ): array {
	if ( is_object( $args ) ) {
		$parsed_args = get_object_vars( $args );
	} elseif ( is_array( $args ) ) {
		$parsed_args =& $args;
	} else {
		wp_parse_str( $args, $parsed_args );
	}

	if ( is_array( $defaults ) && $defaults ) {
		return array_merge( $defaults, $parsed_args );
	}
	return $parsed_args;
}

function __return_true() { // phpcs:ignore WordPress.NamingConventions.ValidFunctionName.FunctionDoubleUnderscore,PHPCompatibility.FunctionNameRestrictions.ReservedFunctionNames.FunctionDoubleUnderscore
	return true;
}

function __return_false() { // phpcs:ignore WordPress.NamingConventions.ValidFunctionName.FunctionDoubleUnderscore,PHPCompatibility.FunctionNameRestrictions.ReservedFunctionNames.FunctionDoubleUnderscore
	return false;
}

function __return_null() { // phpcs:ignore WordPress.NamingConventions.ValidFunctionName.FunctionDoubleUnderscore,PHPCompatibility.FunctionNameRestrictions.ReservedFunctionNames.FunctionDoubleUnderscore
	return null;
}

function __return_empty_array() { // phpcs:ignore WordPress.NamingConventions.ValidFunctionName.FunctionDoubleUnderscore,PHPCompatibility.FunctionNameRestrictions.ReservedFunctionNames.FunctionDoubleUnderscore
	return array();
}

function wp_parse_list( $input_list ): array {
	if ( is_int( $input_list ) ) {
		$input_list = array( (string) $input_list );
	} elseif ( ! is_array( $input_list ) ) {
		$parsed_list = preg_split( '/[\s,]+/', $input_list, -1, PREG_SPLIT_NO_EMPTY );
		return is_array( $parsed_list ) ? $parsed_list : array();
	}

	// Validate all entries of the list are scalar.
	$input_list = array_filter( $input_list, 'is_scalar' );

	return $input_list;
}

function wp_list_pluck( $input_list, $field, $index_key = null ) {
	if ( ! is_array( $input_list ) ) {
		return array();
	}

	$util = new WP_List_Util( $input_list );

	return $util->pluck( $field, $index_key );
}

function wp_array_slice_assoc( $input_array, $keys ): array {
	$slice = array();

	foreach ( $keys as $key ) {
		if ( isset( $input_array[ $key ] ) ) {
			$slice[ $key ] = $input_array[ $key ];
		}
	}

	return $slice;
}

function wp_is_numeric_array( $data ): bool {
	if ( ! is_array( $data ) ) {
		return false;
	}

	return array_all( $data, fn( $value, $key ) => ! is_string( $key ) );
}

function wp_recursive_ksort( &$input_array ) {
	foreach ( $input_array as &$value ) {
		if ( is_array( $value ) ) {
			wp_recursive_ksort( $value );
		}
	}

	ksort( $input_array );
}

function _wp_to_kebab_case( $input_string ) {
	// Ignore the camelCase names for variables so the names are the same as lodash so comparing and porting new changes is easier.
	// phpcs:disable WordPress.NamingConventions.ValidVariableName.VariableNotSnakeCase

	/*
	 * Some notable things we've removed compared to the lodash version are:
	 *
	 * - non-alphanumeric characters: rsAstralRange, rsEmoji, etc
	 * - the groups that processed the apostrophe, as it's removed before passing the string to preg_match: rsApos, rsOptContrLower, and rsOptContrUpper
	 *
	 */

	/** Used to compose unicode character classes. */
	$rsLowerRange       = 'a-z\\xdf-\\xf6\\xf8-\\xff';
	$rsNonCharRange     = '\\x00-\\x2f\\x3a-\\x40\\x5b-\\x60\\x7b-\\xbf';
	$rsPunctuationRange = '\\x{2000}-\\x{206f}';
	$rsSpaceRange       = ' \\t\\x0b\\f\\xa0\\x{feff}\\n\\r\\x{2028}\\x{2029}\\x{1680}\\x{180e}\\x{2000}\\x{2001}\\x{2002}\\x{2003}\\x{2004}\\x{2005}\\x{2006}\\x{2007}\\x{2008}\\x{2009}\\x{200a}\\x{202f}\\x{205f}\\x{3000}';
	$rsUpperRange       = 'A-Z\\xc0-\\xd6\\xd8-\\xde';
	$rsBreakRange       = $rsNonCharRange . $rsPunctuationRange . $rsSpaceRange;

	/** Used to compose unicode capture groups. */
	$rsBreak  = '[' . $rsBreakRange . ']';
	$rsDigits = '\\d+'; // The last lodash version in GitHub uses a single digit here and expands it when in use.
	$rsLower  = '[' . $rsLowerRange . ']';
	$rsMisc   = '[^' . $rsBreakRange . $rsDigits . $rsLowerRange . $rsUpperRange . ']';
	$rsUpper  = '[' . $rsUpperRange . ']';

	/** Used to compose unicode regexes. */
	$rsMiscLower = '(?:' . $rsLower . '|' . $rsMisc . ')';
	$rsMiscUpper = '(?:' . $rsUpper . '|' . $rsMisc . ')';
	$rsOrdLower  = '\\d*(?:1st|2nd|3rd|(?![123])\\dth)(?=\\b|[A-Z_])';
	$rsOrdUpper  = '\\d*(?:1ST|2ND|3RD|(?![123])\\dTH)(?=\\b|[a-z_])';

	$regexp = '/' . implode(
		'|',
		array(
			$rsUpper . '?' . $rsLower . '+' . '(?=' . implode( '|', array( $rsBreak, $rsUpper, '$' ) ) . ')',
			$rsMiscUpper . '+' . '(?=' . implode( '|', array( $rsBreak, $rsUpper . $rsMiscLower, '$' ) ) . ')',
			$rsUpper . '?' . $rsMiscLower . '+',
			$rsUpper . '+',
			$rsOrdUpper,
			$rsOrdLower,
			$rsDigits,
		)
	) . '/u';

	preg_match_all( $regexp, str_replace( "'", '', $input_string ), $matches );
	return strtolower( implode( '-', $matches[0] ) );
	// phpcs:enable WordPress.NamingConventions.ValidVariableName.VariableNotSnakeCase
}

function wp_unique_id( $prefix = '' ) {
	static $id_counter = 0;
	return $prefix . (string) ++$id_counter;
}

function is_utf8_charset( $blog_charset = null ) {
	return _is_utf8_charset( $blog_charset ?? get_option( 'blog_charset' ) );
}

function _canonical_charset( $charset ) {
	if ( is_utf8_charset( $charset ) ) {
		return 'UTF-8';
	}

	/*
	 * Normalize the ISO-8859-1 family of languages.
	 *
	 * This is not required for htmlspecialchars(), as it properly recognizes all of
	 * the input character sets that here are transformed into "ISO-8859-1".
	 *
	 * @todo Should this entire check be removed since it's not required for the stated purpose?
	 * @todo Should WordPress transform other potential charset equivalents, such as "latin1"?
	 */
	if (
		( 0 === strcasecmp( 'iso-8859-1', $charset ) ) ||
		( 0 === strcasecmp( 'iso8859-1', $charset ) )
	) {
		return 'ISO-8859-1';
	}

	return $charset;
}

function mbstring_binary_safe_encoding( $reset = false ) {
	static $encodings  = array();
	static $overloaded = null;

	if ( is_null( $overloaded ) ) {
		if ( function_exists( 'mb_internal_encoding' )
			&& ( (int) ini_get( 'mbstring.func_overload' ) & 2 ) // phpcs:ignore PHPCompatibility.IniDirectives.RemovedIniDirectives.mbstring_func_overloadDeprecated
		) {
			$overloaded = true;
		} else {
			$overloaded = false;
		}
	}

	if ( false === $overloaded ) {
		return;
	}

	if ( ! $reset ) {
		$encoding = mb_internal_encoding();
		array_push( $encodings, $encoding );
		mb_internal_encoding( 'ISO-8859-1' );
	}

	if ( $reset && $encodings ) {
		$encoding = array_pop( $encodings );
		mb_internal_encoding( $encoding );
	}
}

function reset_mbstring_encoding() {
	mbstring_binary_safe_encoding( true );
}

function wp_cache_get_last_changed( $group ) {
	$last_changed = wp_cache_get( 'last_changed', $group );

	if ( $last_changed ) {
		return $last_changed;
	}

	return wp_cache_set_last_changed( $group );
}

function wp_allowed_protocols() {
	static $protocols = array();

	if ( empty( $protocols ) ) {
		$protocols = array( 'http', 'https', 'ftp', 'ftps', 'mailto', 'news', 'irc', 'irc6', 'ircs', 'gopher', 'nntp', 'feed', 'telnet', 'mms', 'rtsp', 'sms', 'svn', 'tel', 'fax', 'xmpp', 'webcal', 'urn' );
	}

	if ( ! did_action( 'wp_loaded' ) ) {
		/**
		 * Filters the list of protocols allowed in HTML attributes.
		 *
		 * @since 3.0.0
		 *
		 * @param string[] $protocols Array of allowed protocols e.g. 'http', 'ftp', 'tel', and more.
		 */
		$protocols = array_unique( (array) apply_filters( 'kses_allowed_protocols', $protocols ) );
	}

	return $protocols;
}

function wp_installing( $is_installing = null ) {
	static $installing = null;

	// Support for the `WP_INSTALLING` constant, defined before WP is loaded.
	if ( is_null( $installing ) ) {
		$installing = defined( 'WP_INSTALLING' ) && WP_INSTALLING;
	}

	if ( ! is_null( $is_installing ) ) {
		$old_installing = $installing;
		$installing     = $is_installing;

		return (bool) $old_installing;
	}

	return (bool) $installing;
}

function is_multisite() {
	if ( defined( 'MULTISITE' ) ) {
		return MULTISITE;
	}

	if ( defined( 'SUBDOMAIN_INSTALL' ) || defined( 'VHOST' ) || defined( 'SUNRISE' ) ) {
		return true;
	}

	return false;
}

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

	/*
	 * Casting from an unknown type is the entire contract of this function, so this conversion is
	 * deliberate: arrays, objects, and resources are converted exactly as PHP has always converted
	 * them here, and narrowing the type first would change long-standing behavior. PHPStan flags
	 * such casts for good reason, but that reasoning does not apply here; if the rule level is
	 * ever raised to 9, the resulting `cast.int` error will need to be ignored or baselined.
	 */
	$maybeint = (int) $maybeint;

	if ( PHP_INT_MIN === $maybeint ) {
		// `abs( PHP_INT_MIN )` overflows to a float, as `PHP_INT_MAX` is one less than `-PHP_INT_MIN`.
		return PHP_INT_MAX;
	}

	return abs( $maybeint );
}

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

function _is_utf8_charset( $charset_slug ) {
	if ( ! is_string( $charset_slug ) ) {
		return false;
	}

	return (
		0 === strcasecmp( 'UTF-8', $charset_slug ) ||
		0 === strcasecmp( 'UTF8', $charset_slug )
	);
}

