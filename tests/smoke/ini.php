<?php
// ini_get_all, ini_set's values, and error_reporting as the directive and the function both change it.
ob_start(function ($text) {
    // "/" after the directory: Truffle PHP runs the smoke tests on the host
    return str_replace([__DIR__ . "\\", __DIR__ . "/"], "DIR/", $text);
});

var_dump(ini_get_all('core') === false, ini_get_all('Core') === false);
var_dump(ini_get_all('nope'));
$values = ini_get_all(null, false);
var_dump($values['display_errors'], $values['error_log'], $values['zend.exception_string_param_max_len']);
$details = ini_get_all();
var_dump($details['error_log'], $details['html_errors']);
ini_set('zend.exception_string_param_max_len', '20');
var_dump(ini_get_all(null, true)['zend.exception_string_param_max_len']);
var_dump(ini_set('zend.exception_string_param_max_len', '-1'), ini_get('zend.exception_string_param_max_len'));
var_dump(ini_set('html_errors', false), ini_get('html_errors'), ini_set('html_errors', true), ini_get('html_errors'));
var_dump(ini_set('error_log', null), ini_get('error_log'));

var_dump(ini_set('error_reporting', E_ALL & ~E_USER_NOTICE), error_reporting());
trigger_error("not reported");
var_dump(error_reporting(E_ALL), ini_get('error_reporting'));
var_dump(@ini_get('error_reporting'), @error_reporting());
ini_restore('error_reporting');
var_dump(error_reporting(), ini_get('error_reporting'));
trigger_error("reported");

// Core's directives WordPress reads: their defaults, and where a script may change them
// memory_limit is left out: the reference image sets its own
foreach (['max_execution_time', 'upload_max_filesize', 'post_max_size', 'file_uploads',
          'max_file_uploads', 'upload_tmp_dir', 'open_basedir', 'disable_functions', 'allow_url_fopen',
          'default_mimetype', 'max_input_vars', 'max_input_time', 'sendmail_path', 'sendmail_from',
          'mail.add_x_header', 'arg_separator.output'] as $name) {
    var_dump($name, ini_get($name), $details[$name]['access'] ?? null);
}
var_dump(ini_set('default_mimetype', 'text/plain'), ini_get('default_mimetype'), ini_set('upload_max_filesize', '64M'),
    ini_get('upload_max_filesize'), ini_set('file_uploads', '0'), ini_set('max_execution_time', '5'));
ini_restore('default_mimetype');
ini_restore('upload_max_filesize');
var_dump(ini_get('default_mimetype'), ini_get('upload_max_filesize'), ini_get('mbstring.func_overload'));
