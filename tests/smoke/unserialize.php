<?php
// unserialize() with options, and its limits: allowed_classes keeps classes from being instantiated (and woken),
// a class that doesn't exist becomes __PHP_Incomplete_Class, a length past the input or nesting past max_depth
// fails, and what __wakeup throws propagates. random_int() is uniform over the whole range.
class Gadget
{
    public $a;

    public function __wakeup()
    {
        echo "woken\n";
    }
}

class Fails
{
    public function __wakeup()
    {
        throw new RuntimeException('wakeup refused');
    }
}

$data = serialize(new Gadget());
print_r(unserialize($data));
echo "\n";
print_r(unserialize($data, ['allowed_classes' => false]));
echo "\n";
print_r(unserialize($data, ['allowed_classes' => ['stdClass']]));
echo "\n";
print_r(unserialize($data, ['allowed_classes' => ['GADGET']]));
echo "\n";
print_r(unserialize('O:7:"Missing":1:{s:1:"x";i:1;}'));
echo "\n";
echo get_class(unserialize('O:7:"Missing":0:{}')), "\n";

var_dump(@unserialize('s:2000000000:"a";'));
var_dump(@unserialize('s:-1:"";'));
var_dump(@unserialize('a:1:{i:0;a:1:{i:0;a:0:{}}}', ['max_depth' => 2]));
var_dump(count(unserialize('a:1:{i:0;a:1:{i:0;a:0:{}}}', ['max_depth' => 3])));
var_dump(@unserialize(str_repeat('a:1:{i:0;', 5000) . 'i:1;' . str_repeat('}', 5000)));
var_dump(count(unserialize(str_repeat('a:1:{i:0;', 4096) . 'i:1;' . str_repeat('}', 4096))));
var_dump(@unserialize(str_repeat('a:1:{i:0;', 4097) . 'i:1;' . str_repeat('}', 4097)));
var_dump(@unserialize('a:1:{a:0:{}i:1;}'));

try {
    unserialize(serialize(new Fails()));
} catch (RuntimeException $e) {
    echo "caught: ", $e->getMessage(), "\n";
}

$zeros = 0;
$positive = 0;
for ($i = 0; $i < 2000; $i++) {
    $n = random_int(-PHP_INT_MAX, PHP_INT_MAX);
    $zeros += $n === 0;
    $positive += $n > 0;
}
var_dump($zeros < 5, $positive > 800 && $positive < 1200);
$seen = [];
for ($i = 0; $i < 2000; $i++) {
    $seen[random_int(PHP_INT_MAX - 2, PHP_INT_MAX)] = true;
    $seen[random_int(PHP_INT_MIN, PHP_INT_MIN + 1)] = true;
}
ksort($seen);
var_dump(array_keys($seen) === [PHP_INT_MIN, PHP_INT_MIN + 1, PHP_INT_MAX - 2, PHP_INT_MAX - 1, PHP_INT_MAX]);
var_dump(random_int(5, 5));
