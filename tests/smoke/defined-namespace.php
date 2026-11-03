<?php
// defined() in a namespace: an unqualified call resolves at run time (php-src compiles no ZEND_DEFINED for it, and a
// namespaced function defined() shadows the global one once declared); a qualified one is the global function
namespace Pe4Ns {
    const NS_CONST = 1;
    define('Pe4Ns\DEF', 2);
    define('PE4NS\UPPER', 3);
    define('GLOBAL_ONE', 4);
    var_dump(defined('NS_CONST'), defined('Pe4Ns\NS_CONST'), defined('pe4ns\NS_CONST'), defined('PE4NS\NS_CONST'));
    var_dump(defined('Pe4Ns\ns_const'), defined('\Pe4Ns\NS_CONST'), defined('Pe4Ns\DEF'), defined('pe4ns\DEF'));
    var_dump(defined('Pe4Ns\UPPER'), defined('GLOBAL_ONE'), defined('Pe4Ns\GLOBAL_ONE'), \defined('GLOBAL_ONE'));
    var_dump(defined('true'), defined('Pe4Ns\true'), defined('PHP_VERSION'), defined('Pe4Ns\PHP_VERSION'));
    var_dump(\defined('NS_CONST'), \defined('Pe4Ns\NS_CONST'));
    var_dump(defined('Other\Deep\X'), defined('namespace\NS_CONST'));
    var_dump(\PE4NS\NS_CONST, \pe4ns\DEF, namespace\UPPER);
    $all = get_defined_constants();
    var_dump(array_key_exists('Pe4Ns\DEF', $all), array_key_exists('PE4NS\UPPER', $all),
        array_key_exists('pe4ns\UPPER', $all), array_key_exists('Pe4Ns\NS_CONST', $all));
}
namespace Pe4Shadow {
    function probe() { return defined('GLOBAL_ONE'); }
    var_dump(probe());
    function defined($name) { return "shadowed $name"; }
    var_dump(probe(), defined('GLOBAL_ONE'), \defined('GLOBAL_ONE'));
}
namespace Pe4Late {
    function probe() { return defined('GLOBAL_ONE'); }
    if (PHP_VERSION_ID > 0) {
        function defined($name) { return "declared at run time: $name"; }
    }
    var_dump(probe(), \defined('GLOBAL_ONE'));
}
namespace Pe4Use {
    use function defined as d;
    var_dump(d('GLOBAL_ONE'), d('NOPE'));
}
