<?php
// Array copies and the references inside them: a reference no variable is bound to any more copies as a value.

function make() {
    $args = [['methods' => 'GET'], ['methods' => 'POST']];
    foreach ($args as $key => &$group) {
        $group = array_merge(['show' => true], $group);
    }
    return $args;
}

$orig = ['/a' => make()];
$copy = $orig;
foreach ($copy as $route => &$handlers) {
    foreach ($handlers as $key => &$handler) {
        $handler['methods'] = ['X' => true];
    }
}
unset($handlers, $handler);
echo json_encode($orig), "\n", json_encode($copy), "\n";

// still bound: the copy shares the element
$x = 1;
$arr = [&$x];
$b = $arr;
$b[0] = 5;
echo $x, " ", $arr[0], "\n";

// bound, then unset: the copy gets its own value
$y = 1;
$arr2 = [&$y];
unset($y);
$c = $arr2;
$c[0] = 7;
echo $arr2[0], " ", $c[0], "\n";

// the foreach variable still points at the last element until unset
$list = [1, 2, 3];
foreach ($list as &$v) {
}
$d = $list;
$d[2] = 30;
echo implode(",", $list), " ", implode(",", $d), "\n";
unset($v);
$list2 = [1, 2, 3];
foreach ($list2 as &$w) {
}
unset($w);
$e = $list2;
$e[2] = 300;
echo implode(",", $list2), " ", implode(",", $e), "\n";

// two arrays share a reference after its variable is gone: a third copy shares it too (refcount 2)
$list3 = [1, 2, 3];
foreach ($list3 as &$v3) {
}
$d3 = $list3;
$d3[2] = 30;
unset($v3);
$e3 = $list3;
$e3[2] = 300;
echo implode(",", $list3), " ", implode(",", $d3), " ", implode(",", $e3), "\n";

// once the other array is gone, the reference is the last slot's alone again
unset($d3);
$f3 = $list3;
$f3[2] = 3000;
echo implode(",", $list3), " ", implode(",", $f3), "\n";

// a reference made inside a function and returned in an array, then copied twice
function pair() {
    $x = 1;
    $a = [&$x, &$x];
    return $a;
}
$p1 = pair();
$p2 = $p1;
$p2[0] = 5;
echo json_encode($p1), " ", json_encode($p2), "\n";

// copied while the foreach reference is bound, written once the function returned (WordPress's register_route)
function register(array $args) {
    foreach ($args as &$group) {
        $group['seen'] = true;
    }
    return store($args);
}
function store(array $args) {
    $args['namespace'] = 'ns';
    return $args;
}
$routes = register([['m' => 'GET']]);
$s = $routes;
foreach ($s as $key => &$handler) {
    if (is_int($key)) {
        $handler['m'] = 'X';
    }
}
unset($handler);
echo json_encode($routes), " ", json_encode($s), "\n";

// a reference parameter is bound while the function runs
function addRef(array &$target, &$value) {
    $target[] = &$value;
}
$z = 1;
$holder = [];
addRef($holder, $z);
$f = $holder;
$f[0] = 9;
echo $z, " ", $holder[0], "\n";

// a function's local reference goes when it returns
function localRef() {
    $local = 1;
    return [&$local];
}
$g = localRef();
$h = $g;
$h[0] = 2;
echo $g[0], " ", $h[0], "\n";

// a global bound into an array
$glob = 'a';
function bindGlobal() {
    global $glob;
    return [&$glob];
}
$i = bindGlobal();
$j = $i;
$j[0] = 'b';
echo $glob, "\n";

// a property reference
class Box {
    public $value = 1;
}
$box = new Box();
$k = [&$box->value];
$l = $k;
$l[0] = 3;
echo $box->value, "\n";

// a closure captures by reference
$counter = 0;
$inc = function () use (&$counter) {
    $counter++;
};
$m = [&$counter];
$n = $m;
$n[0] = 10;
$inc();
echo $counter, "\n";

// nested arrays: copying the outer one leaves the inner references alone
$p = ['inner' => [1, 2]];
foreach ($p['inner'] as &$q) {
    $q *= 10;
}
unset($q);
$r = $p;
$r['inner'][0] = -1;
echo json_encode($p), " ", json_encode($r), "\n";
