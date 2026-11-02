<?php
// class_alias(): an alias names the same class (WooCommerce's StoreApi/deprecated.php and Features aliases)
ob_start(function ($text) {
    return str_replace([__FILE__, __DIR__], ["FILE", "DIR"], $text);
});
interface Shape { function area(); }
class Square implements Shape {
    function __construct(public $side = 2) {}
    function area() { return $this->side ** 2; }
}
trait Greets { function hi() { return 'hi'; } }

var_dump(class_alias('Square', 'Box'));
var_dump(class_alias('\Shape', '\Figure'));
var_dump(class_alias('Greets', 'Waves'));
$b = new Box(3);
var_dump(get_class($b), $b instanceof Square, $b instanceof Box, $b instanceof Figure, $b->area());
var_dump(class_exists('box'), interface_exists('figure'));
var_dump(Box::class, get_class(new BOX()));
class UsesWaves { use Waves; }
var_dump((new UsesWaves)->hi());
function takes(Box $s): Figure { return $s; }
var_dump(takes(new Square)->area());
class Cube extends Box {}
var_dump(get_parent_class(new Cube), is_subclass_of('Cube', 'Box'), is_a(new Cube, 'Figure'));

// the alias is listed under its key, in lower case, where the class was declared
$classes = get_declared_classes();
var_dump(in_array('box', $classes, true), in_array('Box', $classes, true), in_array('Square', $classes, true));
var_dump(in_array('figure', get_declared_interfaces(), true), in_array('waves', get_declared_traits(), true));

// autoloading: the class is loaded for the alias unless told not to
spl_autoload_register(function ($class) {
    echo "autoload $class\n";
    if ($class === 'Lazy') {
        class Lazy {}
    }
});
var_dump(class_alias('Lazy', 'LazyAlias'));
var_dump(class_alias('Missing', 'MissingAlias', false));
var_dump(class_alias('Missing2', 'MissingAlias2'));

// a name already in use, an internal class
var_dump(class_alias('Square', 'Shape'));
var_dump(class_alias('Square', 'box'));
var_dump(class_alias('ArrayObject', 'MyArrayObject'), get_class(new MyArrayObject([])));
var_dump(class_alias('Square', 'stdClass'));

// reserved names stop the script
try {
    class_alias('Square', 'static');
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
echo "not reached\n";
