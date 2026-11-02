<?php
// serialize() as php-src's php_var_serialize_intern: private and protected properties under their mangled names
// ("\0Class\0name", "\0*\0name"), a parent's first; repeated objects (r:) and shared references (R:) numbered as
// var_hash numbers them; __serialize, __sleep (with its warnings), the Serializable interface (C:), classes that
// refuse it, resources, and floats at every serialize_precision. A cached WP_Error must be written as PHP writes it.
set_error_handler(function ($type, $message, $file, $line) {
    echo "error $type: $message (line $line)\n";
    return true;
});
function show($value)
{
    try {
        echo str_replace("\0", '~', serialize($value)), "\n";
    } catch (Throwable $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}

class WP_Error
{
    public $errors = array();
    public $error_data = array();
    protected $additional_data = array();

    public function __construct($code = '', $message = '')
    {
        if ($code !== '') {
            $this->errors[$code][] = $message;
        }
    }
}
echo "-- visibility\n";
show(new WP_Error('http_request_failed', 'cURL error 28'));

trait Counts
{
    private $count = 0;
    protected $step = 1;
}
class Base
{
    private $secret = 's';
    protected $shared = 'p';
    public $open = 'o';
}
#[AllowDynamicProperties]
class Derived extends Base
{
    use Counts;
    private $own = 'd';
    public $more = 'm';
}
$d = new Derived();
show($d);
$d->dynamic = 'x';
unset($d->open);
show($d);
$d->open = 'again';
show($d);

echo "-- references and repeated objects\n";
$o = new stdClass();
$o->self = $o;
show($o);
$x = 1;
$list = [&$x, &$x, $x];
show($list);
$shared = new stdClass();
show([$shared, $shared, [$shared]]);
$ref = new stdClass();
$holder = [$ref, &$ref, &$ref];
show($holder);
$a = ['v' => 1];
$a['w'] = &$a['v'];
show([$a, 'n' => [1, [2, 3]], 'after' => &$a['v']]);
$one = [1];
$r = &$one[0];
unset($r);
show([$one, $one]);
$loop = [];
$loop[0] = &$loop;
show($loop);

echo "-- __serialize\n";
class Point
{
    public $x = 1;
    private $y = 2;

    public function __serialize(): array
    {
        return ['x' => $this->x, 5 => $this->y, 'self' => $this];
    }

    public function __unserialize(array $data): void
    {
    }
}
$p = new Point();
show([$p, $p]);
class BadSerialize
{
    public function __serialize()
    {
        return 'no';
    }
}
show(new BadSerialize());

echo "-- __sleep\n";
class Sleepy
{
    public $a = 1;
    protected $b = 2;
    private $c = 3;
    public $skipped = 4;

    public function __sleep()
    {
        return ['c', 'b', 'a', 'missing', 'a', 7];
    }
}
show(new Sleepy());
class SleepsBadly
{
    public $a = 1;

    public function __sleep()
    {
        return 'a';
    }
}
show([new SleepsBadly(), 'after' => 1]);
class SleepsParent extends Base
{
    public function __sleep()
    {
        return ['secret', 'shared', 'open'];
    }
}
show(new SleepsParent());
class SleepsNested
{
    public $inner;

    public function __sleep()
    {
        // a serialize() __sleep runs numbers its values apart from the outer one
        echo "inner: ", serialize([$this->inner, $this->inner]), "\n";
        return ['inner'];
    }
}
$nested = new SleepsNested();
$nested->inner = new stdClass();
show([$nested->inner, $nested]);

echo "-- Serializable\n";
if (true) {
    class Legacy implements Serializable
    {
        public $data = [1, 2];

        public function serialize()
        {
            return serialize($this->data);
        }

        public function unserialize($data)
        {
            $this->data = unserialize($data);
        }
    }
    class LegacyNull implements Serializable
    {
        public function serialize()
        {
            return null;
        }

        public function unserialize($data)
        {
        }
    }
    class LegacyInt implements Serializable
    {
        public function serialize()
        {
            return 5;
        }

        public function unserialize($data)
        {
        }
    }
    class Both implements Serializable
    {
        public $v = 1;

        public function serialize()
        {
            return 'legacy';
        }

        public function unserialize($data)
        {
        }

        public function __serialize(): array
        {
            return ['v' => $this->v];
        }

        public function __unserialize(array $data): void
        {
        }
    }
    abstract class AbstractLegacy implements Serializable
    {
    }
    class SharesNumbering implements Serializable
    {
        public $o;

        public function serialize()
        {
            // called without the lock: this serialize() continues the outer one's numbering
            return serialize($this->o);
        }

        public function unserialize($data)
        {
        }
    }
}
$legacy = new Legacy();
show([$legacy, $legacy]);
$null = new LegacyNull();
show([$null, $null, 'r' => &$null]);
show(new LegacyInt());
show(new Both());
$inner = new stdClass();
$numbered = new SharesNumbering();
$numbered->o = $inner;
show([$inner, $numbered, $inner]);

echo "-- refused\n";
show(function () {
});
function gen()
{
    yield 1;
}
show(gen());
show(new class {
});
show(fopen('php://memory', 'r'));

echo "-- floats\n";
$floats = [0.1, 1 / 3, 1e100, -0.0, 0.0, 1.5, 2.5, 123456789.0, 1e-7, INF, -INF, NAN];
foreach ([-1, 17, 5, 3, 2, 1, 0] as $precision) {
    ini_set('serialize_precision', $precision);
    echo $precision, ': ', serialize($floats), "\n";
}
var_dump(ini_set('serialize_precision', -2), ini_get('serialize_precision'));
ini_restore('serialize_precision');
echo serialize(0.1), "\n";
