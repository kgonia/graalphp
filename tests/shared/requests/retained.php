<?php
// A request that leaves 256 MB in a static property and reads a private property from its class's method:
// once the context is closed, none of it may stay reachable from the shared engine.
class Holder
{
    private $x = 1;
    public static $big;

    public function get()
    {
        return $this->x;
    }
}

Holder::$big = str_repeat('x', 256 * 1024 * 1024);
echo (new Holder())->get(), "\n";
