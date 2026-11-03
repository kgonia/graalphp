<?php
// defined('NAME') with a literal name, as WordPress and WooCommerce guard files and options with it: compiled in
// place (ConstantNodes.DefinedNode, php-src's ZEND_DEFINED), a slot read per test, no call. The budget: about
// twice the IR the function compiles to now (2026-11-02: guards 146-225, a call per test before the node 378-470).
// IR budget: guards 450
define('PE4_A', 1);
define('PE4_B', false);
function guards(int $i): int {
    $n = 0;
    if (defined('PE4_A')) $n++;
    if (defined('PE4_B')) $n++;
    if (!defined('PE4_MISSING')) $n++;
    if (defined('ABSPATH') || $i < 0) $n++;
    $n += defined('WP_DEBUG') ? 1 : 0;
    $n += defined('PE4_C') ? 2 : 0;
    if (defined('SCRIPT_DEBUG') && SCRIPT_DEBUG) $n++;
    if (defined('PHP_VERSION')) $n++;
    return $n + $i;
}
$sum = 0;
for ($i = 0; $i < 3000; $i++) {
    if ($i === 2000) define('PE4_C', 1);
    $sum += guards($i);
}
echo $sum, "\n";
