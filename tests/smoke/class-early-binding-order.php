<?php
// B's check needs C, which extends B: neither is bound early (C's parent isn't bound), and B's statement fails
// for C missing, before C's statement runs (php-src's variance/class_order_error).
echo "start\n";

class A {
    public function method() : B {}
}
class B extends A {
    public function method() : C {}
}
class C extends B {
}

new C;
