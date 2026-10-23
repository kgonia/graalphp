<?php
// Magic property methods: __get/__set/__isset/__unset for properties that don't exist (lazy initialization
// included), with the per-property guard that makes $this->x inside __get('x') the real property.
#[AllowDynamicProperties]
class Lazy
{
    public $parsed = ['attrs' => ['a' => 1]];
    public $declared = 'here';

    public function __get($name)
    {
        if ('attributes' === $name) {
            $this->attributes = $this->parsed['attrs'] ?? array();
            return $this->attributes;
        }
        echo "get $name\n";
        return null;
    }
}

$lazy = new Lazy();
var_dump(array_key_exists('a', $lazy->attributes), count($lazy->attributes), $lazy->declared, $lazy->missing);
unset($lazy->declared);
var_dump($lazy->declared);

class Bag
{
    private $data = [];

    public function __get($name)
    {
        return $this->data[$name] ?? "no $name";
    }

    public function __set($name, $value)
    {
        echo "set $name\n";
        $this->data[$name] = $value;
    }

    public function __isset($name)
    {
        return isset($this->data[$name]);
    }

    public function __unset($name)
    {
        echo "unset $name\n";
        unset($this->data[$name]);
    }
}

$bag = new Bag();
$bag->color = 'red';
$bag->size = 3;
var_dump($bag->color, $bag->size, $bag->other, isset($bag->color), isset($bag->other), empty($bag->size));
unset($bag->color);
var_dump(isset($bag->color), $bag->color ?? 'default');

#[AllowDynamicProperties]
class Plain
{
}
$plain = new Plain();
$plain->x = 1;
var_dump($plain->x, isset($plain->y));
