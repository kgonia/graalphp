<?php
// class_exists(), interface_exists() and trait_exists() tell the three kinds apart (php-src's
// class_exists_impl: a class is neither an interface nor a trait)
interface I {}
trait T {}
abstract class A implements I {}
final class C extends A { use T; }
foreach (['I', 'T', 'A', 'C', 'ArrayAccess', 'ArrayObject', 'Missing'] as $name) {
    printf("%-12s class %d interface %d trait %d\n", $name, class_exists($name), interface_exists($name),
        trait_exists($name));
}
var_dump(trait_exists('\T'), trait_exists('t', false), class_exists('\C', false));
spl_autoload_register(function ($name) { echo "autoload $name\n"; });
var_dump(trait_exists('Later'), trait_exists('Later', false));
