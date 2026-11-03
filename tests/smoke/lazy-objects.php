<?php
// PHP 8.4 lazy objects: ghosts and proxies, what initializes them, resetAsLazy*, and ReflectionProperty's
// skipLazyInitialization() and setRawValueWithoutLazyInitialization().

class Point {
    public $x = 0;
    public $y = 0;
    public $label = 'p';

    public function __construct($x, $y) {
        echo __METHOD__, "($x, $y)\n";
        $this->x = $x;
        $this->y = $y;
    }

    public function label() {
        return $this->label;
    }
}

$reflector = new ReflectionClass(Point::class);

$ghost = $reflector->newLazyGhost(function (Point $p) {
    echo "ghost initializer\n";
    $p->__construct(1, 2);
});
var_dump($reflector->isUninitializedLazyObject($ghost));
var_dump($ghost);
var_dump($ghost->x);
var_dump($reflector->isUninitializedLazyObject($ghost));
var_dump($ghost);

$proxy = $reflector->newLazyProxy(function (Point $p) {
    echo "proxy factory\n";
    return new Point(3, 4);
});
var_dump($proxy);
$proxy->y = 40;
var_dump($proxy);
var_dump($proxy->x, $proxy->y, $proxy->label());

// each operation that initializes
foreach (['read', 'write', 'isset', 'unset', 'foreach', 'get_object_vars', 'serialize', 'json', 'compare', 'clone']
         as $operation) {
    $object = $reflector->newLazyGhost(function ($p) use ($operation) {
        echo "init by $operation\n";
        $p->x = 10;
    });
    switch ($operation) {
        case 'read': $object->x; break;
        case 'write': $object->y = 5; break;
        case 'isset': isset($object->x); break;
        case 'unset': unset($object->y); break;
        case 'foreach': foreach ($object as $value) {} break;
        case 'get_object_vars': get_object_vars($object); break;
        case 'serialize': serialize($object); break;
        case 'json': json_encode($object); break;
        case 'compare': $object == new stdClass; break;
        case 'clone': clone $object; break;
    }
    var_dump($reflector->isUninitializedLazyObject($object));
}

// what doesn't initialize
$object = $reflector->newLazyGhost(function ($p) {
    echo "not expected\n";
});
var_dump((array) $object);
var_dump($object instanceof Point, get_class($object));
$reflector->getProperty('x')->skipLazyInitialization($object);
var_dump($object->x);
$reflector->getProperty('y')->setRawValueWithoutLazyInitialization($object, 'raw');
var_dump($reflector->getProperty('label')->isLazy($object));
var_dump($object);

// initializeLazyObject, markLazyObjectAsInitialized, getLazyInitializer
$initializer = function ($p) {
    echo "initialize\n";
};
$object = $reflector->newLazyGhost($initializer);
var_dump($reflector->getLazyInitializer($object) === $initializer);
var_dump($reflector->initializeLazyObject($object) === $object);
var_dump($reflector->getLazyInitializer($object));
$object = $reflector->newLazyGhost($initializer);
$reflector->markLazyObjectAsInitialized($object);
var_dump($object);
$proxy = $reflector->newLazyProxy(fn() => new Point(5, 6));
var_dump($reflector->initializeLazyObject($proxy) === $proxy);

// resetAsLazy*
$point = new Point(7, 8);
$reflector->resetAsLazyGhost($point, function ($p) {
    echo "reset initializer\n";
    $p->x = 70;
});
var_dump($point);
var_dump($point->x, $point->y);
try {
    $reflector->resetAsLazyProxy($object = $reflector->newLazyGhost($initializer), fn() => new Point(0, 0));
} catch (ReflectionException $e) {
    echo $e->getMessage(), "\n";
}

// a failing initializer leaves the object lazy and as it was
$object = $reflector->newLazyGhost(function ($p) {
    $p->x = 'changed';
    throw new Exception('initializer failed');
});
try {
    $object->y;
} catch (Exception $e) {
    echo $e->getMessage(), "\n";
}
var_dump($reflector->isUninitializedLazyObject($object), $object);

// the errors
$errors = [
    fn() => $reflector->newLazyGhost(fn() => null, 1),
    fn() => $reflector->newLazyGhost(fn() => null, ReflectionClass::SKIP_DESTRUCTOR),
    fn() => $reflector->newLazyGhost(fn() => 1)->x,
    fn() => $reflector->newLazyProxy(fn() => 1)->x,
    fn() => $reflector->newLazyProxy(fn() => new stdClass)->x,
    fn() => (new ReflectionClass(ArrayObject::class))->newLazyGhost(fn() => null),
    fn() => $reflector->getProperty('nope'),
    fn() => $reflector->getProperty('x')->skipLazyInitialization(new stdClass),
];
foreach ($errors as $error) {
    try {
        $error();
    } catch (Throwable $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}
