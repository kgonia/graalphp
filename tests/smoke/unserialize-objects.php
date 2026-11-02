<?php
// unserialize() of objects as var_unserializer.re makes them: mangled names ("\0Class\0name", "\0*\0name") and
// bare ones restored into the declared property they name (a parent's private one too), a mangled name of another
// class kept as a dynamic property; __wakeup() and __unserialize() delayed until the whole value is read (after
// the warnings, inner objects first, none after one that throws); Serializable's C: format, whose unserialize()
// shares the outer call's back references; __PHP_Incomplete_Class round trips; allowed_classes, max_depth and
// unserialize_callback_func; classes that can't be unserialized.
set_error_handler(function ($type, $message, $file, $line) {
    echo "  error $type: $message (line $line)\n";
    return true;
});
function show($value)
{
    return is_array($value) || is_object($value) ? str_replace("\0", '~', serialize($value)) : var_export($value, true);
}
function s($string)
{
    return 's:' . strlen($string) . ':"' . $string . '";';
}
function attempt($callback)
{
    try {
        echo show($callback()), "\n";
    } catch (Throwable $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}

echo "-- WP_Error\n";
class WP_Error
{
    public $errors = array();
    public $error_data = array();
    protected $additional_data = array();

    public function additional()
    {
        return $this->additional_data;
    }
}
$cached = 'O:8:"WP_Error":3:{' . s('errors') . 'a:1:{s:4:"code";a:1:{i:0;s:3:"msg";}}' . s('error_data') . 'a:0:{}'
    . s("\0*\0additional_data") . 'a:1:{s:4:"code";a:1:{i:0;i:7;}}}';
$error = unserialize($cached);
var_dump($error->additional(), serialize($error) === $cached);
// written by an engine that didn't mangle: the bare name finds the protected property
$bare = unserialize('O:8:"WP_Error":1:{' . s('additional_data') . 'a:1:{i:0;i:1;}}');
var_dump($bare->additional());
echo show($bare), "\n";

echo "-- visibility\n";
class Base
{
    private $secret = 's';
    protected $shared = 'p';
    public $open = 'o';
    public static $counter = 0;

    public function secret()
    {
        return $this->secret;
    }
}
#[AllowDynamicProperties]
class Derived extends Base
{
    private $own = 'd';

    public function own()
    {
        return $this->own;
    }
}
$d = unserialize('O:7:"Derived":4:{' . s('secret') . 's:1:"A";' . s('own') . 's:1:"B";' . s("\0*\0shared")
    . 's:1:"C";' . s('counter') . 'i:5;}');
var_dump($d->secret(), $d->own());
echo show($d), "\n";
$d = unserialize('O:7:"Derived":4:{' . s("\0Base\0secret") . 'i:1;' . s("\0Derived\0own") . 'i:2;'
    . s("\0Base\0own") . 'i:3;' . s("\0*\0open") . 'i:4;}');
var_dump($d->secret(), $d->own());
echo show($d), "\n";
$d = unserialize('O:7:"Derived":1:{' . s("\0derived\0own") . 'i:9;}');
var_dump($d->own());
echo show(unserialize('O:7:"Derived":1:{' . s("\0") . 'i:1;}')), "\n";
echo show(unserialize('O:7:"Derived":1:{' . s("\0ab") . 'i:1;}')), "\n";
echo show(unserialize('O:7:"Derived":1:{' . s("\0\0b") . 'i:1;}')), "\n";
echo show(unserialize('O:8:"stdClass":1:{' . s("\0") . 'i:1;}')), "\n";
echo show(unserialize('O:8:"stdClass":2:{' . s("\0A\0x") . 'i:1;i:7;i:2;}')), "\n";

echo "-- __wakeup and __unserialize\n";
class Waker
{
    public $name;
    public $child;

    public function __wakeup()
    {
        echo "  wakeup {$this->name}", $this->child ? " (child {$this->child->name})" : '', "\n";
    }
}
class Unpacker
{
    public $got;

    public function __unserialize(array $data): void
    {
        echo "  __unserialize ", show($data), "\n";
        $this->got = $data;
    }
}
$tree = 'a:2:{i:0;O:5:"Waker":2:{' . s('name') . 's:5:"outer";' . s('child') . 'O:5:"Waker":2:{' . s('name')
    . 's:5:"inner";' . s('child') . 'N;}}i:1;O:8:"Unpacker":2:{i:0;r:4;s:1:"k";O:5:"Waker":1:{' . s('name')
    . 's:4:"last";}}}';
echo "reading\n";
$value = unserialize($tree);
echo "read\n";
var_dump($value[1]->got[0] === $value[0]->child);
echo "malformed after complete objects:\n";
var_dump(unserialize('a:2:{i:0;O:5:"Waker":1:{' . s('name') . 's:3:"one";}i:1;x}'));
class Refuses
{
    public function __wakeup()
    {
        throw new RuntimeException('refused');
    }
}
attempt(fn() => unserialize('a:3:{i:0;O:5:"Waker":1:{' . s('name') . 's:1:"a";}i:1;O:7:"Refuses":0:{}i:2;O:5:"Waker":1:{'
    . s('name') . 's:1:"b";}}'));
class Nested
{
    public $inner;

    public function __wakeup()
    {
        // a nested unserialize() runs apart: its own back references, its own delayed calls
        echo "  nested: ", show(unserialize('a:2:{i:0;O:5:"Waker":1:{' . s('name') . 's:6:"nested";}i:1;r:2;}')), "\n";
    }
}
echo show(unserialize('O:6:"Nested":1:{' . s('inner') . 'O:5:"Waker":1:{' . s('name') . 's:5:"first";}}')), "\n";

echo "-- Serializable\n";
if (true) {
    class Legacy implements Serializable
    {
        public $data;

        public function serialize()
        {
            return serialize($this->data);
        }

        public function unserialize($data)
        {
            echo "  Legacy::unserialize($data)\n";
            $this->data = unserialize($data);
        }
    }
    class WakesLegacy implements Serializable
    {
        public function serialize()
        {
            return '';
        }

        public function unserialize($data)
        {
        }

        public function __wakeup()
        {
            echo "  never called for C:\n";
        }
    }
}
$shared = new stdClass();
$legacy = new Legacy();
$legacy->data = $shared;
$data = serialize([$shared, $legacy, $legacy]);
echo show($data), "\n";
$back = unserialize($data);
var_dump($back[1]->data === $back[0], $back[2] === $back[1]);
echo show(unserialize('C:6:"Legacy":10:{a:1:{i:0;r:1;}}')), "\n";
echo show(unserialize('C:11:"WakesLegacy":0:{}')), "\n";
echo show(unserialize('O:6:"Legacy":0:{}')), "\n";
echo show(unserialize('a:2:{i:0;C:6:"Legacy":6:{i:5;x}i:1;i:1;}')), "\n";

echo "-- incomplete classes\n";
$incomplete = unserialize('O:7:"Missing":2:{' . s('a') . 'i:1;' . s("\0*\0b") . 'i:2;}');
echo get_class($incomplete), "\n";
echo show($incomplete), "\n";
echo show([$incomplete, $incomplete]), "\n";
echo show(unserialize(serialize(new WP_Error()), ['allowed_classes' => false])), "\n";
echo show(unserialize(serialize(new WP_Error()), ['allowed_classes' => ['wp_error']])), "\n";
echo show(unserialize(serialize(new WP_Error()), ['allowed_classes' => ['stdClass']])), "\n";
echo show(unserialize(serialize(new WP_Error()), ['allowed_classes' => true])), "\n";
echo show(unserialize('O:8:"WP_Error":0:{}', ['allowed_classes' => []])), "\n";
echo show(unserialize('O:5:"a-b-c":0:{}', ['allowed_classes' => []])), "\n";

echo "-- options\n";
attempt(fn() => unserialize('i:1;', ['allowed_classes' => 1]));
attempt(fn() => unserialize('i:1;', ['allowed_classes' => null]));
attempt(fn() => unserialize('i:1;', ['allowed_classes' => [1]]));
attempt(fn() => unserialize('i:1;', ['allowed_classes' => ['a b']]));
attempt(fn() => unserialize('i:1;', ['allowed_classes' => [new stdClass()]]));
attempt(fn() => unserialize('i:1;', ['max_depth' => '1']));
attempt(fn() => unserialize('i:1;', ['max_depth' => -1]));
attempt(fn() => unserialize('i:1;', 'options'));
attempt(fn() => unserialize('i:1;', []));
attempt(fn() => unserialize(5));

echo "-- class lookup\n";
spl_autoload_register(function ($class) {
    echo "  autoload $class\n";
    if ($class === 'Autoloaded') {
        class Autoloaded
        {
            public $v = 1;
        }
    }
    if ($class === 'Throws') {
        throw new LogicException("no $class");
    }
});
echo show(unserialize('O:10:"Autoloaded":0:{}')), "\n";
attempt(fn() => unserialize('O:6:"Throws":0:{}'));
function define_late($class)
{
    echo "  callback $class\n";
    if ($class === 'Late') {
        class Late
        {
        }
    }
}
ini_set('unserialize_callback_func', 'define_late');
echo show(unserialize('O:4:"Late":0:{}')), "\n";
echo show(unserialize('O:5:"Never":0:{}')), "\n";
ini_restore('unserialize_callback_func');
attempt(fn() => unserialize('O:7:"Closure":0:{}'));
attempt(fn() => unserialize('O:9:"Generator":0:{}'));
abstract class Shape
{
}
attempt(fn() => unserialize('O:5:"Shape":0:{}'));
interface Named
{
}
attempt(fn() => unserialize('O:5:"Named":0:{}'));
