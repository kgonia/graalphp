<?php
switch ($GLOBALS['mode']) {
    case 'top':
        throw new RuntimeException("at the top");
    case 'function':
        thrower(1);
    case 'nested-top':
    case 'nested-function':
        $GLOBALS['mode'] = $GLOBALS['mode'] === 'nested-top' ? 'top' : 'function';
        include __DIR__ . "/include-trace.inc";
        break;
    case 'bt-top':
        show(debug_backtrace());
        break;
    case 'bt-function':
        backtrace(0);
        break;
    case 'bt-function-noargs':
        backtrace(DEBUG_BACKTRACE_IGNORE_ARGS);
        break;
    case 'print-top':
        debug_print_backtrace();
        break;
    case 'print-function':
        printed();
        break;
    case 'bt-limit':
        show(debug_backtrace(0, 1));
        (function () { show(debug_backtrace(0, 2)); })();
        break;
}
