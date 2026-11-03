<?php
// A class whose inheritance checks need a class not declared yet isn't bound early (zend_try_early_bind:
// INHERITANCE_UNRESOLVED): it is declared where its statement runs, when the class exists. php-src's
// variance/property_types_early_bind, intersection_types/variance/valid8.
var_dump(class_exists('B', false), class_exists('D', false), class_exists('C', false));

class X {}
class_alias('X', 'Y');

class A {
    public X $prop;
    public function method(Y $param): object { return $param; }
}
// Y is an alias made at run time: B waits for its statement
class B extends A {
    public Y $prop;
}
var_dump(class_exists('B', false));

class C {
    public function make(): object { return new stdClass; }
}
// Z is declared below: D waits, and is declared after Z
class D extends C {
    public function make(): Z { return new Z; }
}
class Z {}
var_dump(class_exists('D', false), get_class((new D)->make()));
