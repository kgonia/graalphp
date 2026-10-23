<?php
// Output buffers with handlers: the phases each handler is called in (start, flush, clean, final), the flags
// a buffer is started with, nesting, and the buffers a script leaves open when it exits.
$log = [];

function upper($buffer, $phase) {
    global $log;
    $log[] = "upper($phase): " . json_encode($buffer);
    return strtoupper($buffer);
}

ob_start('upper');
echo "one ";
ob_flush();
echo "two ";
ob_end_flush();
echo "\n";

ob_start('upper');
echo "discarded";
ob_clean();
echo "kept ";
ob_end_flush();
echo "\n";

ob_start('upper');
echo "cleaned";
ob_end_clean();

ob_start(function ($buffer) {
    return $buffer === '' ? false : "[$buffer]";
});
echo "closure";
ob_end_flush();
echo "\n";

ob_start(fn($buffer) => false);
echo "unchanged";
ob_end_flush();
echo "\n";

ob_start('upper', 0, PHP_OUTPUT_HANDLER_STDFLAGS ^ PHP_OUTPUT_HANDLER_FLUSHABLE);
echo "whole ";
print_r(ob_list_handlers());
ob_end_flush();

ob_start();
echo "outer ";
ob_start('upper');
echo "inner ";
$inner = ob_get_clean();
echo $inner;
$outer = ob_get_contents();
ob_end_clean();
echo "\n", $outer, "\n";

foreach ($log as $line) {
    echo $line, "\n";
}

ob_start(function ($buffer, $phase) {
    return "exit($phase): $buffer\n";
});
echo "left open";
exit;
