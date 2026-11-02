<?php
// Reflection of user classes, methods, functions and parameters: what WooCommerce's dependency container
// (RuntimeContainer::instantiate_class_using_reflection), CallbackUtil and the schedulers use.
namespace App\Model;

ob_start(function ($text) {
    return str_replace([__FILE__, __DIR__], ["FILE", "DIR"], $text);
});

interface Named { function name(): string; }
interface Shaped extends Named {}
trait Logs {
    protected array $log = [];
    function note(string $line): static { $this->log[] = $line; return $this; }
    abstract function name(): string;
}

/**
 * A base.
 */
abstract class Base implements Named
{
    const KIND = 'base';
    public $id = 0;
    protected static $made = 0;

    public function __construct() { static::$made++; }
    abstract protected function shape(): ?Shaped;
    private function secret($x) { return "base secret $x"; }
    public static function make(...$args): static { return new static(...$args); }
    final public function name(): string { return static::class; }
}

final class Order extends Base implements \Countable
{
    use Logs;
    const STATUS = 'pending', TAX = 0.2;
    private ?Order $parent = null;

    public function init(Item $item, ?Base $other = null, int|string $count = 1, &$out = null, string ...$tags): void
    {
        $out = "init $count " . implode(',', $tags);
    }

    protected function shape(): ?Shaped { return null; }
    public function count(): int { return 3; }
    private function secret($x) { return "order secret $x"; }
    function plain($a, $b = [1, 2], ?self $c = null) { return $a; }
    public function &byRef() { static $v = 1; return $v; }
}

class Item
{
    public function __construct(public string $sku = 'X', protected int $qty = 0) {}
    public function vars() { return get_object_vars($this); }
}

class Plain {
    public $a = 1;
    protected $b = [2];
    function __construct() { echo "not called
"; }
    function vars() { return get_object_vars($this); }
}
class NeedsArgs { public function __construct(int $required) {} }
class PrivateCtor { private function __construct() {} public static function get() { return new self(); } }

function helper(Item $item, $x = null, array|bool $flag = false): ?array { return null; }

function dumpObject(object $o): void
{
    echo get_class($o), ' ', json_encode($o->vars()), "
";
}

function show(\ReflectionType|null $t): string
{
    if ($t === null) {
        return 'none';
    }
    $s = get_class($t) . ' ' . $t . ' null=' . var_export($t->allowsNull(), true);
    if ($t instanceof \ReflectionNamedType) {
        $s .= ' name=' . $t->getName() . ' builtin=' . var_export($t->isBuiltin(), true);
    } else {
        $s .= ' [' . implode(', ', array_map(fn($u) => (string) $u, $t->getTypes())) . ']';
    }
    return $s;
}

function params(\ReflectionFunctionAbstract $f): void
{
    echo '  ', $f->getName(), ': ', $f->getNumberOfParameters(), ' params, ', $f->getNumberOfRequiredParameters(),
        ' required, returns ', show($f->getReturnType()), ', variadic ', var_export($f->isVariadic(), true), "\n";
    foreach ($f->getParameters() as $p) {
        echo '    #', $p->getPosition(), ' $', $p->getName(), ' optional=', var_export($p->isOptional(), true),
            ' default=', var_export($p->isDefaultValueAvailable(), true), ' ref=', var_export($p->isPassedByReference(), true),
            ' variadic=', var_export($p->isVariadic(), true), ' type=', show($p->getType()),
            ' hasType=', var_export($p->hasType(), true), ' allowsNull=', var_export($p->allowsNull(), true),
            ' function=', $p->getDeclaringFunction()->getName(),
            ' class=', $p->getDeclaringClass() ? $p->getDeclaringClass()->getName() : 'none', "\n";
    }
}

// classes
foreach ([Order::class, Base::class, Named::class, Shaped::class, Logs::class, Item::class] as $name) {
    $c = new \ReflectionClass($name);
    echo $c->getName(), ' short=', $c->getShortName(), ' ns=', $c->getNamespaceName(), ' inNs=',
        var_export($c->inNamespace(), true), "\n";
    echo '  interface=', var_export($c->isInterface(), true), ' trait=', var_export($c->isTrait(), true),
        ' abstract=', var_export($c->isAbstract(), true), ' final=', var_export($c->isFinal(), true),
        ' instantiable=', var_export($c->isInstantiable(), true), ' internal=', var_export($c->isInternal(), true),
        ' user=', var_export($c->isUserDefined(), true), ' anonymous=', var_export($c->isAnonymous(), true),
        ' cloneable=', var_export($c->isCloneable(), true), ' modifiers=', $c->getModifiers(), "\n";
    echo '  file=', $c->getFileName(), ' lines=', $c->getStartLine(), '-', $c->getEndLine(),
        ' doc=', var_export($c->getDocComment(), true), "\n";
    echo '  parent=', $c->getParentClass() ? $c->getParentClass()->getName() : 'false',
        ' interfaces=', implode(',', $c->getInterfaceNames()), ' traits=', implode(',', $c->getTraitNames()), "\n";
    echo '  methods=', implode(',', array_map(fn($m) => $m->class . '::' . $m->name, $c->getMethods())), "\n";
    echo '  constants=', json_encode($c->getConstants()), ' has KIND=', var_export($c->hasConstant('KIND'), true),
        ' KIND=', var_export($c->getConstant('KIND'), true), "\n";
    $ctor = $c->getConstructor();
    echo '  constructor=', $ctor ? $ctor->class . '::' . $ctor->name : 'none', ' hasMethod(init)=',
        var_export($c->hasMethod('INIT'), true), "\n";
}
print_r(new \ReflectionClass(new Item()));
echo "
";
$order = new \ReflectionClass(Order::class);
var_dump($order->isSubclassOf(Base::class), $order->isSubclassOf(Order::class), $order->implementsInterface('Countable'),
    $order->implementsInterface(Named::class), $order->isInstance(new Order()), $order->isInstance(new Item()));

// the container's path
$item = (new \ReflectionClass(Item::class))->newInstance('A-1', 2);
dumpObject($item);
dumpObject((new \ReflectionClass(Item::class))->newInstanceArgs(['B-2']));
dumpObject((new \ReflectionClass(Plain::class))->newInstanceWithoutConstructor());
$init = $order->getMethod('init');
var_dump($init->isPublic(), $init->isStatic(), $init->isAbstract(), $init->isFinal(), $init->isConstructor(),
    $init->getModifiers(), $init->class, $init->name, $init->getDeclaringClass()->name, $init->getShortName());
params($init);
params($order->getMethod('plain'));
params($order->getMethod('note'));
params($order->getMethod('make'));
params(new \ReflectionFunction('App\Model\helper'));
params($order->getConstructor());
params(new \ReflectionMethod(Item::class, '__construct'));
$o = new Order();
$out = null;
var_dump($init->invoke($o, new Item(), null, 7, $out, 'a', 'b'), $out);
var_dump($init->invokeArgs($o, [new Item(), null, 8]));
$secret = new \ReflectionMethod(Order::class, 'secret');
var_dump($secret->isPrivate(), $secret->isProtected(), $secret->invoke($o, 1));
$baseSecret = new \ReflectionMethod(Base::class, 'secret');
var_dump($baseSecret->invoke($o, 2), $baseSecret->class);
$shape = new \ReflectionMethod(Order::class . '::shape');
var_dump($shape->isProtected(), $shape->invoke($o));
var_dump((new \ReflectionMethod(Order::class, 'make'))->invoke(null)::class);
var_dump((new \ReflectionMethod(Base::class, 'shape'))->isAbstract(), (new \ReflectionMethod(Named::class, 'name'))->isAbstract());
var_dump((new \ReflectionMethod($o, 'byRef'))->returnsReference(), $order->getMethod('name')->isFinal());
$m = (new \ReflectionMethod(Order::class, 'count'));
$m->setAccessible(true);
var_dump($m->invoke($o), $m->getClosure($o)());
var_dump(array_map(fn($m) => $m->name, $order->getMethods(\ReflectionMethod::IS_STATIC)));
var_dump(array_map(fn($m) => $m->name, $order->getMethods(\ReflectionMethod::IS_PRIVATE | \ReflectionMethod::IS_PROTECTED)));
var_dump(\ReflectionMethod::IS_PUBLIC, \ReflectionMethod::IS_PROTECTED, \ReflectionMethod::IS_PRIVATE,
    \ReflectionMethod::IS_STATIC, \ReflectionMethod::IS_FINAL, \ReflectionMethod::IS_ABSTRACT);

// closures
$k = 3;
$closure = function (int $a, $b = 2) use ($k) { return $a + $b + $k; };
$f = new \ReflectionFunction($closure);
echo $f->getName(), ' ', $f->getFileName(), "
";
var_dump($f->isClosure(), $f->getStartLine(), $f->getEndLine(),
    $f->invoke(1), $f->invokeArgs([1, 1]), $f->getClosureThis(), $f->getClosureScopeClass(), $f->isUserDefined());
params($f);
$bound = \Closure::bind(fn() => $this->id, new Order(), Order::class);
$f = new \ReflectionFunction($bound);
var_dump(get_class($f->getClosureThis()), $f->getClosureScopeClass()->name);
echo $f->getName(), "
";
$f = new \ReflectionFunction('App\Model\helper');
var_dump($f->isClosure(), $f->isInternal(), $f->getShortName(), $f->getNamespaceName(), $f->getDocComment());

// what isn't there
foreach ([fn() => new \ReflectionClass('App\Missing'), fn() => $order->getMethod('missing'),
          fn() => new \ReflectionMethod(Order::class, 'missing'), fn() => new \ReflectionMethod('nonsense'),
          fn() => new \ReflectionFunction('missing_function'), fn() => (new \ReflectionClass(NeedsArgs::class))->newInstance(),
          fn() => (new \ReflectionClass(PrivateCtor::class))->newInstance(),
          fn() => (new \ReflectionClass(Base::class))->newInstance(),
          fn() => $init->invoke(new Item(), new Item()),
          fn() => (new \ReflectionMethod(Order::class, 'init'))->invoke(null, new Item())] as $attempt) {
    try {
        $attempt();
        echo "no exception\n";
    } catch (\ReflectionException $e) {
        echo 'ReflectionException: ', $e->getMessage(), "\n";
    } catch (\Error $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}
var_dump((new \ReflectionClass(PrivateCtor::class))->getConstructor()->isPrivate());
var_dump(new \ReflectionException('x') instanceof \Exception, (new \ReflectionClass(Order::class)) instanceof \Reflector);
print_r($init);
echo "
";
