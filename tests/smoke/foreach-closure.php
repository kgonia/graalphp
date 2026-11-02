<?php
// A Closure is an object: foreach over it iterates its (no) properties, without a warning (WordPress's
// _wp_json_sanity_check walks every object in what wp_json_encode is given)
$f = function () { return 1; };
foreach ($f as $k => $v) {
    echo "never $k\n";
}
foreach ($f as &$v) {
    echo "never\n";
}
function walk($value) { foreach ($value as $k => $v) { yield $k; } }
var_dump(iterator_to_array(walk($f)), json_encode(["cb" => $f]));
