<?php
$wp_did_header = true;
if ( isset( $_SERVER['HTTP_X_FREEZE_VERIFY'] ) ) {
    (static function () {
        global $wpdb, $timestart, $wp_scripts;
        $empty_result = null === $wpdb->result;
        $session = WC()->session;
        $guest = null;
        if ( $session instanceof WC_Session_Handler ) {
            // Both timestamps are protected. Casting retains PHP's mangled property names.
            $fields = (array) $session;
            $guest = array( 'id' => $session->get_customer_id(),
                'expiring' => $fields["\0*\0_session_expiring"],
                'expiration' => $fields["\0*\0_session_expiration"] );
        }
        $localized = array();
        if ( isset( $wp_scripts->registered ) ) {
            foreach ( array( 'utils', 'wp-api-request', 'wc-settings', 'wc-blocks-middleware' ) as $handle ) {
                if ( ! isset( $wp_scripts->registered[ $handle ] ) ) {
                    continue;
                }
                foreach ( $wp_scripts->registered[ $handle ]->extra as $slot => $values ) {
                    if ( ! in_array( $slot, array( 'data', 'before', 'after' ), true ) ) {
                        continue;
                    }
                    foreach ( (array) $values as $js ) {
                        if ( ! is_string( $js ) ) {
                            continue;
                        }
                        if ( 'utils' === $handle && false !== strpos( $js, 'var userSettings' ) ) {
                            $localized['utils_time'] = preg_match( '/"time"\s*:\s*"(\d+)"/', $js, $m ) ? (int) $m[1] : -1;
                        }
                        if ( 'wp-api-request' === $handle && false !== strpos( $js, 'var wpApiSettings' ) ) {
                            $localized['rest_nonce'] = preg_match( '/"nonce"\s*:\s*"([^"]+)"/', $js, $m )
                                && wp_create_nonce( 'wp_rest' ) === $m[1];
                        }
                        if ( 0 === strpos( $handle, 'wc-' ) && false !== strpos( $js, 'storeApiNonce' ) ) {
                            $localized['store_nonce'] = preg_match( '/storeApiNonce["\']?\s*:\s*["\']([^"\']+)/', $js, $m )
                                && wp_create_nonce( 'wc_store_api' ) === $m[1];
                            $localized['store_time'] = preg_match( '/wcStoreApiNonceTimestamp["\']?\s*:\s*["\']?(\d+)/', $js, $m )
                                ? (int) $m[1] : -1;
                        }
                    }
                }
            }
        }
        $connection = mysqli_fetch_row( mysqli_query( $wpdb->dbh, 'SELECT CONNECTION_ID()' ) );
        $probe = array(
            'uri' => $_SERVER['REQUEST_URI'],
            'cf7' => wpcf7_get_request_uri(),
            'marker' => $_SERVER['HTTP_X_FREEZE_VERIFY'],
            'guest' => $guest,
            'localized' => $localized,
            'checked_at' => time(),
            'cookie' => $_COOKIE,
            'filter_cookie' => filter_input( INPUT_COOKIE, 'freeze_probe' ),
            'filter_post_id' => filter_input( INPUT_GET, 'p' ),
            'get_post_id' => $_GET['p'] ?? null,
            'request_post_id' => $_REQUEST['p'] ?? null,
            'result_cleared' => $empty_result,
            'connection' => $connection[0],
            'placeholder_hash' => hash( 'sha256', $wpdb->placeholder_escape() ),
            'request_time' => $_SERVER['REQUEST_TIME_FLOAT'],
            'wp_start' => WP_START_TIMESTAMP,
            'timestart' => $timestart,
        );
        header( 'X-Freeze-Check: ' . base64_encode( json_encode( $probe ) ) );
    })();
}

wp();
require_once ABSPATH . WPINC . '/template-loader.php';
