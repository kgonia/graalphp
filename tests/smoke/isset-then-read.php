<?php
// isset($a[$k]) followed at once by the read of $a[$k]: the engine keeps the element the isset found for the read
// (BytecodeTranslator.keepFor, IssetElementKeep). What the pair shows must stay php-src's: an ArrayAccess object's
// offsetExists() then offsetGet(), a string's offset, a key's conversion and its diagnostics by both, references,
// copy-on-write of the value read, destructor order when the read replaces its own container.
// PHP's deprecations for a float or null key (issue 102) are not shown: the engine does not raise them yet, and what
// is read is the same with and without them. Warnings are shown without their file.
set_error_handler(function ($level, $message, $file, $line) {
    if ($level !== E_DEPRECATED) {
        echo "[warning on line $line: $message]";
    }
    return true;
});

class Bag implements ArrayAccess {
    public $items = [];
    public $lie = false;

    function __construct($items = []) {
        $this->items = $items;
    }

    function offsetExists($offset): bool {
        echo "[exists ", json_encode($offset), "]";
        return $this->lie || isset($this->items[$offset]);
    }

    function offsetGet($offset): mixed {
        echo "[get ", json_encode($offset), "]";
        return $this->items[$offset] ?? 'made up';
    }

    function offsetSet($offset, $value): void {
        $this->items[$offset] = $value;
    }

    function offsetUnset($offset): void {
        unset($this->items[$offset]);
    }
}

class Noisy {
    public $name;

    function __construct($name) {
        $this->name = $name;
    }

    function __destruct() {
        echo "[destruct {$this->name}]";
    }
}

function show($value) {
    echo is_object($value) ? get_class($value) : json_encode($value), "\n";
}

// ---- the statement shapes ----

function assign($a, $k) {
    $v = 'none';
    if (isset($a[$k])) {
        $v = $a[$k];
    }
    return $v;
}

function assignConst($a) {
    $v = 'none';
    if (isset($a['name'])) {
        $v = $a['name'];
        $v .= '!';
    }
    if (isset($a[2])) {
        $v = $a[2];
    }
    return $v;
}

function returned($a, $k) {
    if (isset($a[$k])) {
        return $a[$k];
    }
    return 'none';
}

function returnedTyped(array $a, string $k): string {
    if (isset($a[$k]))
        return $a[$k];
    return 'none';
}

function &returnedRef(array &$a, $k) {
    static $none = 'none';
    if (isset($a[$k])) {
        return $a[$k];
    }
    return $none;
}

function ternary($a, $k) {
    return isset($a[$k]) ? $a[$k] : 'none';
}

function ternaryNested($a, $k) {
    $v = isset($a[$k]) ? $a[$k]['x'] ?? 'no x' : 'none';
    return $v;
}

function twoArgs($a, $k) {
    if (isset($k, $a[$k])) {
        $a = $a[$k];
        return $a;
    }
    return 'none';
}

function andChain($a, $k, $on) {
    if ($on && isset($a[$k])) {
        $v = $a[$k];
        return $v;
    }
    if (isset($a[$k]) && $on) {
        $v = $a[$k];
        return [$v];
    }
    return 'none';
}

function nestedRead($a, $k) {
    if (isset($a[$k])) {
        $v = $a[$k]['x'];
        return $v;
    }
    return 'none';
}

function nestedReturn($a, $k, $j) {
    if (isset($a[$k])) {
        return $a[$k][$j];
    }
    return 'none';
}

function chain($a, $k) {
    if (isset($a[$k])) {
        $v = $a[$k];
    } elseif (isset($a['other'])) {
        $v = $a['other'];
    } else {
        $v = 'none';
    }
    return $v;
}

function generated($a, $keys) {
    foreach ($keys as $k) {
        if (isset($a[$k])) {
            yield $k => $a[$k];
        }
        if (isset($a[$k])) {
            $v = $a[$k];
            yield $v;
        }
    }
    if (isset($a['last'])) {
        return $a['last'];
    }
}

function withGoto($a, $k, $jump) {
    $v = 'none';
    if ($jump) {
        goto inside;
    }
    if (isset($a[$k])) {
        $v = $a[$k];
        inside:
        $v .= '+';
    }
    return $v;
}

function withFinally($a, $k) {
    try {
        if (isset($a[$k])) {
            return $a[$k];
        }
    } finally {
        if (isset($a['f'])) {
            $f = $a['f'];
            echo "[finally $f]";
        }
    }
    return 'none';
}

$record = ['name' => 'Ada', 2 => 'two', 'nothing' => null, 'zero' => 0, 'empty' => '', 'list' => [1, 2],
    'x' => ['x' => 'deep'], 'f' => 'F', 'last' => 'L', '7' => 'seven', 'other' => 'O'];
foreach (['name', 2, '2', 'nothing', 'zero', 'empty', 'missing', 'list', 7, '7', '07', -1, ''] as $k) {
    echo json_encode($k), ': ';
    show([assign($record, $k), returned($record, $k), ternary($record, $k), twoArgs($record, $k),
        andChain($record, $k, true), andChain($record, $k, false), chain($record, $k)]);
}
show(assignConst($record));
show(assignConst([2 => 'only two']));
show(assignConst([]));
show(returnedTyped($record, 'name'));
show(returnedTyped($record, 'missing'));
show(ternaryNested($record, 'x'));
show(ternaryNested($record, 'list'));
show(ternaryNested($record, 'missing'));
show(nestedRead($record, 'x'));
show(nestedReturn($record, 'list', 1));
show(nestedReturn($record, 'missing', 1));
show(withGoto($record, 'name', false));
show(withGoto($record, 'name', true));
show(withGoto($record, 'missing', false));
show(withFinally($record, 'name'));
show(withFinally($record, 'missing'));
show(withFinally([], 'missing'));
$generator = generated($record, ['name', 'missing', 2]);
foreach ($generator as $k => $v) {
    echo json_encode($k), ' => ', json_encode($v), "\n";
}
show($generator->getReturn());

$byRef = ['a' => 1];
$got = &returnedRef($byRef, 'a');
$got = 5;
show($byRef);
$none = &returnedRef($byRef, 'b');
show($none);
unset($got, $none);

// ---- other keys: both the isset and the read convert them, with their diagnostics ----

function keyed($a, $k) {
    if (isset($a[$k])) {
        $v = $a[$k];
        return $v;
    }
    return 'none';
}

$keys = [1 => 'one', 0 => 'zero', '' => 'empty key', 'true' => 't'];
foreach ([1.0, 1.7, true, false, null, '1', '1.0', ' 1', "1\n"] as $k) {
    echo var_export($k, true), ': ';
    show(keyed($keys, $k));
}
// an array or an object as the key is isset's TypeError; its message is not shown (issue 102: the engine's is the
// read's "on array", php-src's "in isset or empty")
try {
    show(keyed($keys, []));
} catch (Throwable $e) {
    echo get_class($e), "\n";
}
try {
    show(keyed($keys, new stdClass));
} catch (Throwable $e) {
    echo get_class($e), "\n";
}

function undefinedKey($a) {
    if (isset($a[$k])) {
        return $a[$k];
    }
    return 'none';
}

function undefinedContainer($k) {
    if (isset($a[$k])) {
        return $a[$k];
    }
    return 'none';
}
show(undefinedKey($keys));
show(undefinedContainer('x'));

// ---- other containers: the read is the read as written ----

$bag = new Bag(['here' => 'value', 'null' => null]);
foreach (['here', 'null', 'gone', 3] as $k) {
    show(assign($bag, $k));
    show(returned($bag, $k));
    show(ternary($bag, $k));
    show(twoArgs($bag, $k));
}
$bag->lie = true;
show(assign($bag, 'gone'));
show(ternary($bag, 'gone'));

foreach ([0, 2, 3, -1, -4, '1', 'x', '1x', 1.5, null, true, '1.5', ' 1', '1e0', [], new stdClass] as $k) {
    echo var_export($k, true), ': ';
    try {
        show([assign('abc', $k), ternary('abc', $k)]);
    } catch (Throwable $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}
foreach ([null, 5, 5.5, true, false, new stdClass, fn() => 1, new ArrayObject(['k' => 'in ArrayObject'])] as $container) {
    try {
        show([assign($container, 'k'), returned($container, 0)]);
    } catch (Throwable $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}

// ---- one place meets them all, as often as compiled code needs ----

function mixed($a, $k) {
    if (isset($k, $a[$k])) {
        $a = $a[$k];
        return $a;
    }
    return null;
}

$quiet = new ArrayObject(['k' => 'object']);
$containers = [['k' => 'hash'], [5, 6, 7], 'string', $quiet, ['k' => null], [], null, ['k' => ['k' => 1]],
    [1 => 1.5], ['k' => 'hash again']];
$sum = '';
for ($i = 0; $i < 30000; $i++) {
    $container = $containers[$i % count($containers)];
    $got = mixed($container, $i % 3 === 0 ? 1 : 'k');
    if ($i < 20 || $i % 9973 === 0) {
        $sum .= json_encode($got) . ' ';
    }
}
echo $sum, "\n";

function wpArrayGet($input_array, $path, $default_value = null) {
    foreach ($path as $path_element) {
        if (!is_array($input_array)) {
            return $default_value;
        }
        if (is_string($path_element) || is_int($path_element) || null === $path_element) {
            if (isset($path_element, $input_array[$path_element])) {
                $input_array = $input_array[$path_element];
                continue;
            }
            if (isset($path_element) && array_key_exists($path_element, $input_array)) {
                $input_array = $input_array[$path_element];
                continue;
            }
        }
        return $default_value;
    }
    return $input_array;
}

$settings = ['color' => ['palette' => ['default' => [['slug' => 'black']], 'theme' => []], 'gradients' => null],
    'spacing' => ['units' => ['px', 'em']], 3 => [4 => 'int path']];
$paths = [['color', 'palette', 'default'], ['color', 'gradients'], ['typography', 'lineHeight'],
    ['spacing', 'units', 1], [3, 4], ['3', '4'], [], ['color', 'palette', 'default', 0, 'slug', 'more'], [null]];
$sum = '';
for ($i = 0; $i < 30000; $i++) {
    $got = wpArrayGet($settings, $paths[$i % count($paths)], 'default');
    if ($i < count($paths)) {
        $sum .= json_encode($got) . ' ';
    }
}
echo $sum, "\n";

// ---- references and copies ----

function throughReferences() {
    static $kept = ['k' => ['v' => 1]];
    global $shared;
    $inner = 'referenced';
    $local = ['k' => &$inner, 'n' => null];
    if (isset($local['k'])) {
        $v = $local['k'];
        $v .= ' copy';
    }
    echo $v, ' / ', $inner, "\n";
    if (isset($kept['k'])) {
        $copy = $kept['k'];
        $copy['v']++;
        $copy[] = 'added';
    }
    echo json_encode($copy), ' / ', json_encode($kept), "\n";
    if (isset($shared['k'])) {
        $shared = $shared['k'];
    }
    echo json_encode($shared), "\n";
}
$shared = ['k' => ['k' => 'global']];
throughReferences();
throughReferences();

function byReference(array &$a, $k) {
    if (isset($a[$k])) {
        $a = $a[$k];
        return true;
    }
    return false;
}
$tree = ['l' => ['l' => ['l' => 'leaf']]];
while (is_array($tree) && byReference($tree, 'l')) {
    echo json_encode($tree), "\n";
}

// ---- the read replaces its own container: what the container alone held goes then, as in php-src ----

function replaced() {
    $a = ['k' => ['o' => new Noisy('inner')], 'sibling' => new Noisy('sibling')];
    if (isset($a['k'])) {
        $a = $a['k'];
        echo "[after the write]";
    }
    echo "[end of the function]";
    return count($a);
}
show(replaced());
echo "\n";

function replacedObject() {
    $a = ['k' => new Noisy('element'), 'sibling' => new Noisy('sibling 2')];
    $k = 'k';
    if (isset($a[$k])) {
        $a = $a[$k];
        echo "[after the write]";
    }
    $a = null;
    echo "[after null]";
}
replacedObject();
echo "\n";

// ---- in methods and closures; two pairs in one function ----

class Registry {
    private $items = ['a' => 'A'];

    function get($name) {
        $items = $this->items;
        if (isset($items[$name])) {
            return $items[$name];
        }
        $fallback = ['b' => 'B'];
        return isset($fallback[$name]) ? $fallback[$name] : 'none';
    }

    static function pick(array $from, ...$names) {
        $picked = [];
        foreach ($names as $name) {
            if (isset($from[$name])) {
                $picked[] = $from[$name];
            }
        }
        return $picked;
    }
}
$registry = new Registry;
show([$registry->get('a'), $registry->get('b'), $registry->get('c')]);
show(Registry::pick($record, 'name', 'missing', 2, 'nothing', 'x'));
$closure = function ($k) use ($record) {
    return isset($record[$k]) ? $record[$k] : 'none';
};
show([$closure('name'), $closure('missing')]);
$arrow = fn($a, $k) => isset($a[$k]) ? $a[$k] : 'none';
show([$arrow($record, 2), $arrow($record, 3), $arrow('str', 1), $arrow($bag, 'here')]);
