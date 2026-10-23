<?php
// extract() into a function's variables and the globals; dynamic instanceof.
function show($vars)
{
    $color = 'kept';
    $n = extract($vars, EXTR_SKIP);
    echo $n, ' ', $color, ' ', $size ?? 'none', ' ', isset($shape) ? $shape : 'no shape', "\n";
    $m = extract(['color' => 'blue', 'size' => 9], EXTR_PREFIX_ALL, 'p');
    echo $m, ' ', $p_color, ' ', $p_size, "\n";
    extract(['color' => 'green']);
    echo $color, "\n";
}
show(['color' => 'red', 'size' => 3, '1bad' => 'x', 'shape' => 'round']);
extract(['top' => 'global value']);
echo $top, "\n";

class Animal {}
class Dog extends Animal {}
$class = 'Animal';
$dog = new Dog();
var_dump($dog instanceof $class, $dog instanceof $dog, new Animal() instanceof $dog, 'x' instanceof $class);
