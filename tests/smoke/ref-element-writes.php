<?php
// `$q[k] = v` and `$q[] = v` where $q is a reference (a by-reference parameter, `$q = &$this->vars`, a global), a
// plain local, or a property: the array that is the variable's alone is written in place; one shared with another
// variable is copied first; null and false become an array (false with its deprecation); an ArrayAccess object
// gets offsetSet(), whose result is released at once; another object and a scalar are errors. First each case
// once, then the same functions run often enough to be compiled, where all but the exclusive array is one call
// out of line (Places.refArrayForWrite, DimNode.writeToObject).
// PHP's deprecations for false becoming an array (issue 104) and for a float or null key (issue 102) are not shown:
// the engine does not raise them yet, and what is written is the same with and without them.
set_error_handler(fn() => true, E_DEPRECATED);

class Noisy {
    static $gone = 0;
    static $loud = true;
    public $name;

    function __construct($name) {
        $this->name = $name;
    }

    function __destruct() {
        self::$gone++;
        if (self::$loud) {
            echo "[gone {$this->name}]";
        }
    }
}

class Bag implements ArrayAccess {
    public $log = [];
    public $sets = 0;

    function offsetExists($offset): bool {
        return false;
    }

    function offsetGet($offset): mixed {
        return null;
    }

    #[\ReturnTypeWillChange]
    function offsetSet($offset, $value) {
        $this->sets++;
        if (Noisy::$loud) {
            $this->log[] = json_encode($offset) . '=' . json_encode($value);
        }
        if ($value === 'throw') {
            throw new RuntimeException('offsetSet(' . json_encode($offset) . ') threw');
        }
        return new Noisy('set ' . json_encode($offset));
    }

    function offsetUnset($offset): void {
    }
}

function viaRef(&$q, $k, $v) {
    $q['name'] = $v;
    $q[$k] = $v;
    $q[3] = $v;
    $q[] = $v;
}

function direct($q, $k, $v) {
    $q['name'] = $v;
    $q[$k] = $v;
    $q[3] = $v;
    $q[] = $v;
    return $q;
}

function viaGlobal($k, $v) {
    global $G;
    $G['name'] = $v;
    $G[$k] = $v;
    $G[3] = $v;
    $G[] = $v;
}

class Query {
    public $vars;
    static $shared;

    function fill($k, $v) {
        $q = &$this->vars;
        $q['name'] = $v;
        $q[$k] = $v;
        $q[3] = $v;
        $q[] = $v;
        return $q;
    }

    function property($k, $v) {
        $this->vars['name'] = $v;
        $this->vars[$k] = $v;
        $this->vars[3] = $v;
        $this->vars[] = $v;
    }

    static function shared($k, $v) {
        self::$shared['name'] = $v;
        self::$shared[$k] = $v;
        self::$shared[3] = $v;
        self::$shared[] = $v;
    }
}

function steps(&$q) {
    echo "a";
    $q['one'] = 1;
    echo "b";
    $q[] = 2;
    echo "c\n";
}

function show($what, $value) {
    echo $what, ': ', $value instanceof Bag ? 'Bag ' . json_encode($value->log) : json_encode($value), "\n";
}

/** Each state a written variable can be in. */
function states() {
    return [
        'array' => fn() => ['name' => 'old', 'kept' => 1],
        'packed' => fn() => [10, 20],
        'empty' => fn() => [],
        'null' => fn() => null,
        'false' => fn() => false,
        'true' => fn() => true,
        'int' => fn() => 5,
        'float' => fn() => 1.5,
        'bag' => fn() => new Bag,
        'object' => fn() => new stdClass,
    ];
}

function attempt($what, $run) {
    try {
        $run();
    } catch (Throwable $e) {
        echo $what, ': ', get_class($e), ': ', $e->getMessage(), "\n";
    }
}

foreach (states() as $name => $make) {
    echo "== $name\n";
    $a = $make();
    attempt('ref', function () use (&$a) { viaRef($a, 'key', 'v'); });
    show('ref', $a);

    $a = $make();
    $other = $a;
    attempt('ref shared', function () use (&$a) { viaRef($a, 'key', 'v'); });
    show('ref shared', $a);
    show('the other', $other);

    $a = $make();
    attempt('direct', function () use ($a) { show('direct result', direct($a, 'key', 'v')); });
    show('direct argument', $a);

    $G = $make();
    $other = $G;
    attempt('global', function () { viaGlobal('key', 'v'); });
    show('global', $G);
    show('the other', $other);

    $query = new Query;
    $query->vars = $make();
    $other = $query->vars;
    attempt('fill', function () use ($query) { show('fill result', $query->fill('key', 'v')); });
    show('fill', $query->vars);
    show('the other', $other);

    $query = new Query;
    $query->vars = $make();
    $other = $query->vars;
    attempt('property', function () use ($query) { $query->property('key', 'v'); });
    show('property', $query->vars);
    show('the other', $other);

    Query::$shared = $make();
    $other = Query::$shared;
    attempt('static', function () { Query::shared('key', 'v'); });
    show('static', Query::$shared);
    show('the other', $other);
    unset($a, $other, $query);
    $G = null;
    Query::$shared = null;
    echo "\n";
}

echo "== unset\n";
viaRef($never, 'key', 'v');
show('unset', $never);

echo "== keys\n";
$a = [];
viaRef($a, '12', 'numeric string');
viaRef($a, 4.7, 'float');
viaRef($a, true, 'bool');
viaRef($a, null, 'null');
viaRef($a, '', 'empty');
show('keys', $a);
$bag = new Bag;
viaRef($bag, '12', 'numeric string');
viaRef($bag, 4.7, 'float');
viaRef($bag, null, 'null');
echo "\n";
show('bag keys', $bag);

echo "== offsetSet()'s result is released at each write\n";
$bag = new Bag;
steps($bag);

echo "== offsetSet() throws\n";
$bag = new Bag;
attempt('throws', function () use (&$bag) { viaRef($bag, 'key', 'throw'); });
show('after', $bag);
$bag = null;
echo "\n";

echo "== a reference shared by two variables is one array\n";
$a = ['name' => 'old'];
$b = &$a;
$copy = $a;
viaRef($b, 'key', 'v');
show('a', $a);
show('copy', $copy);

echo "== run often\n";
Noisy::$loud = false;
$makers = array_values(states());
$errors = 0;
$sum = 0;
$bags = 0;
$query = new Query;
$a = [];
for ($i = 0; $i < 200000; $i++) {
    // mostly the array that is the variable's own; every 64th round another state
    $state = ($i & 63) === 63 ? ($i >> 6) % count($makers) : 0;
    $a = $makers[$state]();
    $query->vars = $makers[$state]();
    $G = $makers[$state]();
    if (($i & 127) === 5) {
        $other = $a;
        $held = $query->vars;
        $alsoG = $G;
    }
    try {
        viaRef($a, 'k' . ($i & 7), $i);
        $filled = $query->fill('k' . ($i & 7), $i);
        viaGlobal('k' . ($i & 3), $i);
        $query->property('p' . ($i & 3), $i);
        $made = direct($G, 'd', $i);
        if (is_array($a)) {
            $sum += count($a) + count($filled) + count($G) + count($query->vars) + count($made) + $a['name']
                    + $G[3];
        } else {
            $bags += $a->sets + $query->vars->sets + $G->sets;
        }
    } catch (Error $e) {
        $errors++;
    }
    if (($i & 127) === 5) {
        $sum += count($other) + count($held) + count($alsoG) + ($other['name'] === 'old' ? 1 : 1000000);
        unset($other, $held, $alsoG);
    }
}
echo "sum $sum bags $bags errors $errors gone ", Noisy::$gone, "\n";
