<?php
// isset($this) and empty($this) in object, static and function scope; $GLOBALS read as a whole, as a value
// and as an argument.
$x = 5;
class A
{
    public function f()
    {
        return [isset($this), empty($this)];
    }

    public static function g()
    {
        return [isset($this), empty($this)];
    }
}
function plain()
{
    return isset($this);
}
var_dump((new A())->f(), A::g(), plain());
var_dump(array_key_exists('x', $GLOBALS), isset($GLOBALS['x']), $GLOBALS['x']);
$all = $GLOBALS;
var_dump($all['x'], is_array($all));
function count_x()
{
    return count(array_filter(array_keys($GLOBALS), fn($k) => $k === 'x'));
}
var_dump(count_x());
