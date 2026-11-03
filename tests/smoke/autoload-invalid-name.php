<?php
// php-src runs no autoloader for an empty or invalid class name (zend_lookup_class_ex: zend_is_valid_class_name);
// WordPress's sodium_compat autoloader reads $class[0] and warned for "".
spl_autoload_register(function ($class) { echo "autoload '$class'\n"; });
foreach (['', '\\', 'a-b', 'Foo Bar', "x\0y", 'Ok_Name', '\\Lead\\Ns', "caf\xc3\xa9"] as $name) {
    var_dump(class_exists($name), interface_exists($name));
}
try {
    new ReflectionClass('');
} catch (ReflectionException $e) {
    echo $e->getMessage(), "\n";
}
var_dump(is_a('', 'stdClass', true), is_subclass_of('x y', 'stdClass'));
