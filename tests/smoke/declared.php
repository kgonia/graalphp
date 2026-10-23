<?php
// get_declared_classes/_interfaces/_traits and get_defined_functions: the built-in ones first (the prelude's
// DateTime and SPL classes included before first use), then the script's in declaration order, without the
// runtime's own helpers.
interface Shape {}
trait Named {}
abstract class Base implements Shape { use Named; }
final class Circle extends Base {}
function myFunc() {}
function MixedCase() {}
$c = get_declared_classes();
$i = get_declared_interfaces();
$t = get_declared_traits();
$f = get_defined_functions();
var_dump(array_slice($c, -2), array_slice($i, -1), $t, $f['user']);
var_dump(in_array('stdClass', $c), in_array('ArrayIterator', $c), in_array('DateTime', $c), in_array('Exception', $c),
    in_array('Countable', $i), in_array('Traversable', $i), in_array('strlen', $f['internal']),
    in_array('Circle', $i), in_array('Shape', $c), array_search('stdClass', $c) < array_search('Base', $c));
