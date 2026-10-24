<?php
/*
 * A must-use plugin for tests/gate2/http.sh: ?fatal=exception or ?fatal=error ends the request with a fatal error
 * during init (an uncaught exception, or trigger_error's E_USER_ERROR), which WordPress's fatal error handler, a
 * shutdown function reading error_get_last(), turns into its "critical error" page.
 */
if (isset($_GET['fatal'])) {
    add_action('init', function () {
        if ($_GET['fatal'] === 'exception') {
            throw new RuntimeException('a test fatal error');
        }
        trigger_error('a test user error', E_USER_ERROR);
    });
}
