<?php
// php-src folds names and keywords as ASCII (zend_str_tolower): a non-ASCII letter is a different name in another
// case. The same goes for array_change_key_case, strnatcasecmp and strip_tags' allow list, which work on bytes.
class Ärger
{
    function Größe()
    {
        return 'Größe';
    }
}

function überall()
{
    return 'überall';
}

var_dump(class_exists('Ärger'), class_exists('ÄRGER'), class_exists('ärger'), class_exists('äRGER'));
var_dump(function_exists('ÜBERALL'), function_exists('üBERALL'), üBERALL(), method_exists('ÄrGeR', 'GRößE'),
    method_exists('Ärger', 'GRÖßE'));
try {
    new ärger();
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

var_dump(array_change_key_case(['Äb' => 1, "\xC0X" => 2, "i\xFF" => 3, 'İi' => 4], CASE_LOWER));
var_dump(array_change_key_case(['äb' => 1, "\xE0x" => 2, 'ıi' => 4], CASE_UPPER));
var_dump(strnatcasecmp("\xE0", "\xC0"), strnatcasecmp('a10', 'A9'), strnatcasecmp('é', 'É'));
var_dump(strip_tags('<b>x</b><É>y</É><é>z</é>', '<é>'), strip_tags('<B>x</B><I>y</I>', ['b']));
var_dump(strtolower('ÄBC'), strtoupper('äbc'), ucfirst('ébc'), lcfirst('Ébc'));
var_dump(version_compare('1.0ALPHA', '1.0alpha'), version_compare('1.0ab', '1.0b'), version_compare('1.0c', '1.0'),
    version_compare('1.0Dev', '1.0dev'), version_compare('1.0patch', '1.0pl'), version_compare('1.0RC1', '1.0rc1'));
foreach (['1.0dev', '1.0alpha', '1.0a', '1.0ab', '1.0ALPHA', '1.0beta', '1.0b', '1.0RC1', '1.0c', '1.0', '1.0pl1',
             '1.0p', '1.0x', '1.0#'] as $a) {
    echo $a, ':';
    foreach (['1.0alpha', '1.0RC1', '1.0', '1.0pl1', '1.0x'] as $b) {
        echo ' ', version_compare($a, $b);
    }
    echo "\n";
}
