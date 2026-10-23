<?php
// `.=`, string interpolation and dynamic method names with operands of every scalar type, objects with
// __toString, and the order in which the two sides of `.=` are converted.
class Name {
    function __construct(public string $s) {}
    function __toString(): string { echo "[toString {$this->s}]"; return $this->s; }
}
$values = ['str', 42, -7, 0, PHP_INT_MAX, PHP_INT_MIN, 1.5, -0.0, 1e100, 0.1 + 0.2, 2.0, true, false, null,
    new Name('obj'), '0', ''];
for ($round = 0; $round < 3; $round++) {
    $out = '';
    foreach ($values as $v) {
        $out .= $v;
        $out .= '|';
    }
    if ($round == 2) {
        echo $out, "\n";
    }
}
// .= on a target that isn't a string yet: int, float, null, unset, bool.
foreach ([5, 2.5, null, true, false] as $start) {
    $x = $start;
    $x .= 7;
    $x .= 1.25;
    $x .= 'end';
    var_dump($x);
}
$fresh = [];
$fresh['k'] = null;
$fresh['k'] .= 'a';
$fresh['k'] .= 1;
var_dump($fresh);
// The target is converted before the value: both are objects with side effects.
$left = new Name('L');
$left .= new Name('R');
echo "\n", $left, "\n";
// Interpolation of every type.
foreach ($values as $i => $v) {
    if ($v instanceof Name) {
        echo "obj: {$v}\n";
        continue;
    }
    echo "$i: <$v> <{$v}>\n";
}
$n = 3;
$f = 0.5;
$arr = ['a' => 1, 'b' => [2, 3]];
$obj = new stdClass();
$obj->p = 'prop';
for ($i = 0; $i < 3; $i++) {
    echo "n=$n f=$f a={$arr['a']} b={$arr['b'][1]} p={$obj->p} i=$i sum={$n}{$i}\n";
}
// Dynamic method names: strings, and values that convert to strings.
class Dyn {
    function hello() { return 'hello'; }
    function m42() { return 'm42'; }
}
$d = new Dyn();
foreach (['hello', 'HELLO', 'm42', 'hello'] as $m) {
    echo $d->$m(), "\n";
}
$num = 42;
$prefix = 'm';
echo $d->{$prefix . $num}(), "\n";
