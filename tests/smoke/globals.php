<?php
// Global variables (top-level code, `global`, $GLOBALS), static properties, constants and class lookups:
// sites that cache what they look up must see rebinding, unset, references and late declarations.
$counter = 0;
$list = [];
function bump() {
    global $counter, $list, $notYet;
    $counter++;
    $list[] = $counter;
    $notYet = ($notYet ?? 0) + 10;
    return $counter;
}
for ($i = 0; $i < 5; $i++) {
    bump();
    $counter += 100;
}
echo $counter, ' ', implode(',', $list), ' ', $notYet, "\n";

// Rebinding a global to a reference, and unsetting it, between reads of the same sites.
function readCounter() {
    global $counter;
    return $counter;
}
$other = 'other';
for ($i = 0; $i < 6; $i++) {
    if ($i == 2) {
        $counter = &$other;
    }
    if ($i == 4) {
        unset($counter);
    }
    echo var_export(readCounter(), true), ' ', var_export($counter ?? 'unset', true), ' ',
        isset($counter) ? 'set' : 'unset', "\n";
    $counter = $i;
}
$other = 'changed';
echo $counter, ' ', $other, "\n";

// foreach by reference at top level rebinds the loop variable every iteration.
$values = [1, 2, 3];
foreach ($values as &$v) {
    $v *= 2;
    $seen = $v;
}
unset($v);
echo implode(',', $values), ' ', $seen, "\n";

// Sites that see many rebinds and unsets (each changes the globals version).
function readFlag() {
    global $flag;
    return $flag ?? 'none';
}
$out = [];
for ($i = 0; $i < 8; $i++) {
    $flag = $i;
    $out[] = readFlag();
    unset($flag);
    $out[] = readFlag();
    $alias = 'a' . $i;
    $flag = &$alias;
    $out[] = readFlag() . $flag;
}
echo implode(',', $out), "\n";

// A global read before it exists, then defined, by the same site.
for ($i = 0; $i < 3; $i++) {
    echo var_export($late ?? null, true), ' ', isset($GLOBALS['late']) ? 'set' : 'unset', ' ',
        var_export($GLOBALS['late'] ?? 'none', true), "\n";
    $late = $i;
}
$GLOBALS['viaGlobals'] = 5;
echo $viaGlobals, "\n";

// Static properties, inherited ones shared with the parent, redeclared ones not, initialized lazily.
class Registry {
    public static $instance = null;
    public static $items = [];
    public static $count = 0;
    public static $initial = self::START * 2;
    const START = 21;
    static function get() {
        if (self::$instance === null) {
            self::$instance = new static();
        }
        self::$count++;
        static::$items[] = static::class;
        return self::$instance;
    }
}
class SubRegistry extends Registry {
    public static $items = ['own'];
}
for ($i = 0; $i < 4; $i++) {
    Registry::get();
    SubRegistry::get();
}
echo Registry::$count, ' ', SubRegistry::$count, ' ', implode(',', Registry::$items), ' ',
    implode(',', SubRegistry::$items), ' ', get_class(Registry::$instance), ' ', Registry::$initial, "\n";
$ref = &Registry::$count;
$ref = 100;
echo Registry::$count, ' ', SubRegistry::$count, ' ', isset(Registry::$nope) ? 'y' : 'n', "\n";
try {
    echo Registry::$nope;
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

// Constants and classes declared after a site first ran.
function useLate() {
    return defined('LATE') ? LATE : 'no constant';
}
echo useLate(), "\n";
define('LATE', 'late constant');
echo useLate(), ' ', useLate(), "\n";
function makeLate() {
    return class_exists('LateClass') ? get_class(new LateClass()) : 'no class';
}
echo makeLate(), "\n";
if (true) {
    class LateClass {}
}
echo makeLate(), ' ', makeLate(), "\n";
try {
    undefinedFunction();
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
