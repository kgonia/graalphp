<?php
namespace {
    // FILTER_SANITIZE_STRING and FILTER_SANITIZE_STRIPPED, #[\Deprecated] since 8.1: each fetch raises the
    // deprecation (not folded at compile time), constant() too, defined() not; a namespace's own constant of the
    // name isn't deprecated
    ob_start(function ($text) {
        return str_replace([__FILE__, __DIR__], ["FILE", "DIR"], $text);
    });

    var_dump(FILTER_SANITIZE_STRING);
    var_dump(\FILTER_SANITIZE_STRIPPED);
    for ($i = 0; $i < 2; $i++) {
        var_dump(filter_var("<b>'x'</b>", FILTER_SANITIZE_STRING));
    }
    var_dump(constant("FILTER_SANITIZE_STRIPPED"), defined("FILTER_SANITIZE_STRING"), FILTER_SANITIZE_ENCODED);
    function f($flag = FILTER_SANITIZE_STRING) {
        return $flag;
    }
    var_dump(f(), f(1));
    set_error_handler(function ($type, $message, $file, $line) {
        echo "handled: $type $message\n";
        return true;
    });
    var_dump(FILTER_SANITIZE_STRING);
    restore_error_handler();
    var_dump(@FILTER_SANITIZE_STRING);
}

namespace Shop {
    var_dump(FILTER_SANITIZE_STRING, FILTER_DEFAULT);
    const FILTER_SANITIZE_STRIPPED = "own";
    var_dump(FILTER_SANITIZE_STRIPPED, \FILTER_SANITIZE_STRIPPED);
}
