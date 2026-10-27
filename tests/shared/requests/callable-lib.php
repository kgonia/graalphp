<?php
// Included by callable-unbound.php and callable-bound.php, so both requests run the same call site.
class Magic
{
    public function __call($name, $args)
    {
        return "__call";
    }

    public static function __callStatic($name, $args)
    {
        return "__callStatic";
    }
}

// ['Magic', 'missing'] reaches __call from code running on a Magic, __callStatic elsewhere
function magic_caller()
{
    return function () {
        return call_user_func(['Magic', 'missing']);
    };
}
