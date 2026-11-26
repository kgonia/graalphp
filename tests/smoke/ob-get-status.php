<?php
// ob_get_status() (main/output.c, php_output_handler_status): the top buffer's status, or every level's with true.
// buffer_size grows as php_output_handler_append grows it; flags gain STARTED and PROCESSED once the handler ran,
// DISABLED once it failed. Elementor's image loading optimization reads it while flushing its header buffer.

function handler($buffer, $phase) {
    return $buffer;
}
function eats($buffer) {
    return '';
}
function fails($buffer) {
    return false;
}
class Filter {
    function run($buffer) { return $buffer; }
    static function run2($buffer) { return $buffer; }
}

$s = [];
$s['none'] = ob_get_status();
$s['none full'] = ob_get_status(true);
ob_start();
$s['default'] = ob_get_status();
echo str_repeat('x', 100);
$s['default 100'] = ob_get_status();
echo str_repeat('y', 20000);
$s['default 20100'] = ob_get_status();
echo str_repeat('z', 12284);
$s['default exactly full'] = ob_get_status();
ob_start('handler', 100);
$s['chunked'] = ob_get_status();
echo 'abc';
$s['chunked 3'] = ob_get_status();
ob_flush();
$s['chunked flushed'] = ob_get_status();
ob_start([new Filter, 'run'], 5000);
ob_start('Filter::run2');
ob_start('eats');
echo 'eaten';
ob_flush();
ob_start('fails');
echo 'kept';
ob_flush();
$s['all'] = ob_get_status(true);
for ($i = 0; $i < 5; $i++) {
    ob_end_clean();
}
$s['one left'] = ob_get_status(false);
ob_end_clean();
foreach ($s as $name => $status) {
    echo "$name: ", json_encode($status), "\n";
}
