<?php
// WeakReference: one per object while it lives, get() gives the object (WooCommerce's WC_Cart_Session keeps the
// carts whose session updates are off this way)
class Cart { public $items = 2; }
$cart = new Cart();
$ref = WeakReference::create($cart);
var_dump($ref->get() === $cart, WeakReference::create($cart) === $ref, $ref->get()->items);
var_dump(WeakReference::create(new Cart()) !== $ref, get_class($ref), $ref instanceof WeakReference);
$closure = fn() => 1;
var_dump(WeakReference::create($closure)->get() === $closure);
foreach ([fn() => new WeakReference(), fn() => clone $ref, fn() => serialize($ref), fn() => WeakReference::create(1)]
         as $attempt) {
    try {
        $attempt();
        echo "no error\n";
    } catch (Throwable $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}
