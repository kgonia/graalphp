<?php
// Finding M3 (a model error): the error handler of curl_multi_add_handle's warning drops the variable holding the
// multi handle, and the call's frame still shows it among its arguments, on php-src (its frame holds a reference)
// and on Truffle PHP (the built-in's Truffle frame holds its arguments array), so the Cleaner can't queue it while
// the call runs. Same output on both.
set_error_handler(function () {
    unset($GLOBALS['mh']);
    foreach (debug_backtrace() as $frame) {
        if (($frame['function'] ?? '') === 'curl_multi_add_handle') {
            echo get_class($frame['args'][0]), "\n";
        }
    }
    return true;
});
function round_($tmp)
{
    $GLOBALS['mh'] = curl_multi_init();
    $ch = curl_init();
    $fp = fopen($tmp, 'w');
    curl_setopt($ch, CURLOPT_FILE, $fp);
    fclose($fp);
    return curl_multi_add_handle($GLOBALS['mh'], $ch);
}
$tmp = tempnam(sys_get_temp_dir(), 'race');
for ($i = 0; $i < 3; $i++) {
    var_dump(round_($tmp));
}
unlink($tmp);
