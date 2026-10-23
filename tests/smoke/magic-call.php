<?php
// __call and __callStatic stand in for missing methods: called directly, through a dynamic name, as a static
// call (with $this an instance: __call; without: __callStatic), and as callables. The name keeps the case it
// was called with; the arguments arrive as an array of values.
class Magic
{
    public function real($x)
    {
        return "real $x";
    }

    public function __call($name, $arguments)
    {
        return "__call $name(" . implode(', ', array_map('json_encode', $arguments)) . ")";
    }

    public static function __callStatic($name, $arguments)
    {
        return "__callStatic $name(" . implode(', ', array_map('json_encode', $arguments)) . ")";
    }

    public function inside()
    {
        return [self::Missing(1), static::other(2), Magic::third(3)];
    }
}

class OnlyStatic
{
    public static function __callStatic($name, $arguments)
    {
        return "static $name";
    }
}

class Child extends Magic
{
    public function viaParent()
    {
        return parent::fromChild('p');
    }
}

$m = new Magic();
$name = 'DynamicName';
var_dump($m->real(1), $m->missingMethod(1, 'two', [3]), $m->$name(), $m->MixedCase());
var_dump(Magic::staticMissing(true, null), $m->inside(), (new Child())->viaParent());
var_dump(call_user_func([$m, 'viaCallable'], 1), call_user_func(['Magic', 'staticCallable'], 2),
    call_user_func('Magic::stringCallable', 3), call_user_func_array([$m, 'withArray'], ['a' => 1, 'b' => 2]));
var_dump(is_callable([$m, 'anything']), is_callable(['Magic', 'anything']), is_callable([new OnlyStatic(), 'x']),
    method_exists($m, 'anything'));
var_dump(OnlyStatic::direct());
try {
    call_user_func([new OnlyStatic(), 'onObject']);
} catch (TypeError $e) {
    echo get_class($e), "\n";
}
$value = 1;
var_dump($m->byRef($value), $value);
var_dump(array_map([$m, 'mapped'], [1, 2]));

try {
    (new OnlyStatic())->nothing();
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    stdClass::nothing();
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
