<?php
// The elementary operations WordPress's code is made of, one per kernel: an array read, a call, a string built, a
// property read (wiki/ceiling.md). Each kernel does OPS of its operation in a loop and returns a checksum; `loop` is
// the loop alone. `aget` is hot.php's kernel of _wp_array_get, the function the others take apart.
// Usage: php ops.php IGNORED ROUNDS [kernel,...]   prints "kernel round ns/op checksum" for every round.
error_reporting(E_ALL);
const OPS = 1000;

function ops_wp_array_get($input_array, $path, $default_value = null) {
    if (!is_array($path) || 0 === count($path)) {
        return $default_value;
    }
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

function ops_inc($x) { return $x + 1; }
function ops_defaults($a, $b = null, $c = []) { return $b === null ? $a : $a + count($c); }
function ops_count2($a, $b) { return count($a) + count($b); }
function ops_separate($a) { $a['x'] = 1; return $a; }
function ops_args(array $args) { return $args['a'] + $args['c']; }

class Ops_P {
    public $id = 0;
    public $name = 'p';
    public ?array $meta = null;
    public function __construct($id = 0) { $this->id = $id; }
    public function get($x) { return $this->id + $x; }
    public static function make($x) { return $x + 2; }
}

class Ops_Q {
    public $vars = ['p' => 0, 'name' => 'x', 'status' => 1];
    public function fill() { // hash_write_const through a reference, as WP_Query's `$q = &$this->query_vars`
        $q = &$this->vars;
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $q['status'] = $i;
            $v += $q['status'];
        }
        return $v;
    }
}

class Ops_Bag implements ArrayAccess {
    public $items = [];
    public function offsetExists($offset): bool { return isset($this->items[$offset]); }
    public function offsetGet($offset): mixed { return $this->items[$offset]; }
    public function offsetSet($offset, $value): void { $this->items[$offset] = $value; }
    public function offsetUnset($offset): void { unset($this->items[$offset]); }
}

$settings = ['color' => ['palette' => ['default' => [['slug' => 'black', 'color' => '#000']], 'theme' => []],
    'gradients' => null, 'custom' => true], 'typography' => ['fontSizes' => ['default' => [1, 2, 3]]],
    'spacing' => ['units' => ['px', 'em']]];
$ints = [];
for ($i = 0; $i < 8; $i++) {
    $ints[] = $i * 3;
}
$keys = ['id', 'name', 'type', 'status'];
$rows = [];
for ($i = 0; $i < 4; $i++) {
    $rows[] = ['id' => $i, 'name' => "row $i", 'type' => $i % 2, 'status' => 7, 'meta' => [$i, $i + 1]];
}
$map = [];
for ($i = 0; $i < 8; $i++) {
    $map["key_$i"] = $i;
}
$objects = [];
for ($i = 0; $i < 4; $i++) {
    $objects[] = new Ops_P($i);
}
$dynamic = [];
for ($i = 0; $i < 4; $i++) {
    $o = new stdClass();
    $o->id = $i;
    $o->name = "std $i";
    $dynamic[] = $o;
}
$words = ['post', 'page', 'attachment', 'revision'];
$closure = static function ($x) { return $x + 3; };

$kernels = [
    'loop' => static function () {
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $v += $i & 3;
        }
        return $v;
    },

    // --- _wp_array_get, and its parts
    'aget' => static function () use ($settings) {
        $v = 0;
        for ($i = 0; $i < OPS / 4; $i++) {
            $v += count(ops_wp_array_get($settings, ['color', 'palette', 'default'], []));
            $v += ops_wp_array_get($settings, ['color', 'gradients'], 5) ?? 1;
            $v += ops_wp_array_get($settings, ['typography', 'lineHeight'], 2);
            $v += count(ops_wp_array_get($settings, ['spacing', 'units']));
        }
        return $v;
    },
    'aget_hit' => static function () use ($settings) { // one call site, one path, every key found by isset
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $v += count(ops_wp_array_get($settings, ['color', 'palette', 'default'], []));
        }
        return $v;
    },
    'aget_inline' => static function () use ($settings) { // what the function does for that path, written out
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $a = $settings;
            if (isset($a['color'])) {
                $a = $a['color'];
                if (isset($a['palette'])) {
                    $a = $a['palette'];
                    if (isset($a['default'])) {
                        $a = $a['default'];
                    }
                }
            }
            $v += count($a);
        }
        return $v;
    },
    'path3' => static function () use ($settings) { // the same three reads as one expression
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $v += count($settings['color']['palette']['default']);
        }
        return $v;
    },

    // --- arrays: reads
    'packed_read' => static function () use ($ints) {
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $v += $ints[$i & 7];
        }
        return $v;
    },
    'hash_read_const' => static function () use ($rows) { // a literal key
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $v += $rows[$i & 3]['status'];
        }
        return $v;
    },
    'hash_read_var' => static function () use ($rows, $keys) { // the key in a variable
        $v = 0;
        $row = $rows[1];
        for ($i = 0; $i < OPS; $i++) {
            $v += strlen((string) $row[$keys[$i & 3]]);
        }
        return $v;
    },
    'isset_const' => static function () use ($rows) {
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $row = $rows[$i & 3];
            $v += isset($row['status']) ? 1 : 0;
            $v += isset($row['missing']) ? 1 : 0;
        }
        return $v;
    },
    'isset_var' => static function () use ($rows, $keys) {
        $v = 0;
        $row = $rows[2];
        for ($i = 0; $i < OPS; $i++) {
            $k = $keys[$i & 3];
            $v += isset($k, $row[$k]) ? 1 : 0;
        }
        return $v;
    },
    'key_exists' => static function () use ($rows, $keys) {
        $v = 0;
        $row = $rows[2];
        for ($i = 0; $i < OPS; $i++) {
            $v += array_key_exists($keys[$i & 3], $row) ? 1 : 0;
        }
        return $v;
    },
    'array_to_local' => static function () use ($rows) { // an array value copied into a variable
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $meta = $rows[$i & 3]['meta'];
            $v += count($meta);
        }
        return $v;
    },
    'foreach_list' => static function () use ($ints) { // per element
        $v = 0;
        for ($i = 0; $i < OPS / 8; $i++) {
            foreach ($ints as $x) {
                $v += $x;
            }
        }
        return $v;
    },
    'foreach_map' => static function () use ($map) { // per element, with its key
        $v = 0;
        for ($i = 0; $i < OPS / 8; $i++) {
            foreach ($map as $k => $x) {
                $v += $x + strlen($k);
            }
        }
        return $v;
    },
    'foreach_literal' => static function () { // a literal list of three, per element
        $v = 0;
        for ($i = 0; $i < OPS / 3; $i++) {
            foreach (['color', 'palette', 'default'] as $x) {
                $v += strlen($x);
            }
        }
        return $v;
    },
    'in_array' => static function () use ($words) {
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $v += in_array($words[$i & 3], ['page', 'revision', 'nav_menu_item'], true) ? 1 : 0;
        }
        return $v;
    },

    // --- arrays: writes
    'hash_write_const' => static function () {
        $v = 0;
        $row = ['id' => 0, 'name' => 'x', 'status' => 1];
        for ($i = 0; $i < OPS; $i++) {
            $row['status'] = $i;
            $v += $row['status'];
        }
        return $v;
    },
    'hash_write_var' => static function () use ($keys) {
        $v = 0;
        $row = ['id' => 0, 'name' => 'x', 'type' => 0, 'status' => 1];
        for ($i = 0; $i < OPS; $i++) {
            $row[$keys[$i & 3]] = $i;
        }
        return $v + $row['id'] + $row['status'];
    },
    'append' => static function () { // per element, a list of eight built
        $v = 0;
        for ($i = 0; $i < OPS / 8; $i++) {
            $list = [];
            for ($j = 0; $j < 8; $j++) {
                $list[] = $j + $i;
            }
            $v += count($list);
        }
        return $v;
    },
    'literal' => static function () { // an array of three keys made and read
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $a = ['a' => $i, 'b' => 2, 'c' => 3];
            $v += $a['a'] + $a['c'];
        }
        return $v;
    },
    'literal_arg' => static function () { // the same passed to a function
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $v += ops_args(['a' => $i, 'b' => 2, 'c' => 3]);
        }
        return $v;
    },
    'separate' => static function () use ($rows) { // a callee writes to its copy of a five-key array
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $v += count(ops_separate($rows[$i & 3]));
        }
        return $v;
    },
    'merge' => static function () use ($rows) {
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $v += count(array_merge(['id' => 0, 'x' => 1, 'y' => 2], $rows[$i & 3]));
        }
        return $v;
    },

    // --- calls
    'call' => static function () {
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $v += ops_inc($i);
        }
        return $v;
    },
    'call_defaults' => static function () {
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $v += ops_defaults($i);
        }
        return $v;
    },
    'call_arrays' => static function () use ($settings) {
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $v += ops_count2($settings, ['color', 'palette', 'default']);
        }
        return $v;
    },
    'call_method' => static function () use ($objects) {
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $v += $objects[$i & 3]->get($i);
        }
        return $v;
    },
    'call_static' => static function () {
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $v += Ops_P::make($i);
        }
        return $v;
    },
    'call_closure' => static function () use ($closure) {
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $v += $closure($i);
        }
        return $v;
    },
    'call_user_func' => static function () {
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $v += call_user_func('ops_inc', $i);
        }
        return $v;
    },

    // --- strings
    'concat' => static function () use ($words) {
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $s = $words[$i & 3] . '-' . $i;
            $v += strlen($s);
        }
        return $v;
    },
    'interpolate' => static function () use ($words) {
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $s = "hook_{$words[$i & 3]}_$i";
            $v += strlen($s);
        }
        return $v;
    },
    'build' => static function () use ($words) { // a string of 1000 pieces built by .=, per piece
        $s = '';
        for ($i = 0; $i < OPS; $i++) {
            $s .= $words[$i & 3];
        }
        return strlen($s);
    },
    'compare' => static function () use ($words) {
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $w = $words[$i & 3];
            $v += ('page' === $w ? 1 : 0) + ('revision' === $w ? 2 : 0);
        }
        return $v;
    },
    'string_functions' => static function () use ($words) { // four built-ins per op
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $w = $words[$i & 3];
            $v += strlen($w) + strpos($w, 't') + strlen(substr($w, 1, 3)) + strlen(strtolower($w));
        }
        return $v;
    },
    'replace' => static function () use ($words) {
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $v += strlen(str_replace('a', 'bb', $words[$i & 3]));
        }
        return $v;
    },
    'sprintf' => static function () use ($words) {
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $v += strlen(sprintf('%s-%d', $words[$i & 3], $i));
        }
        return $v;
    },
    'string_index' => static function () use ($words) {
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $v += ord($words[$i & 3][$i & 3]);
        }
        return $v;
    },

    // --- objects
    'prop_read' => static function () use ($objects) {
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $v += $objects[$i & 3]->id;
        }
        return $v;
    },
    'prop_write' => static function () use ($objects) {
        $v = 0;
        $o = $objects[0];
        for ($i = 0; $i < OPS; $i++) {
            $o->id = $i;
            $v += $o->id;
        }
        $o->id = 0;
        return $v;
    },
    'prop_dynamic' => static function () use ($dynamic) {
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $v += $dynamic[$i & 3]->id;
        }
        return $v;
    },
    'new' => static function () {
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $o = new Ops_P($i);
            $v += $o->id;
        }
        return $v;
    },
    'instanceof' => static function () use ($objects, $dynamic) {
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $v += ($objects[$i & 3] instanceof Ops_P ? 1 : 0) + ($dynamic[$i & 3] instanceof Ops_P ? 1 : 0);
        }
        return $v;
    },

    // --- writes that are not a local array's (added last: the kernels above keep their place in a run)
    'ref_write' => static function () {
        $query = new Ops_Q;
        return $query->fill();
    },
    'object_write' => static function () { // an ArrayAccess object's offsetSet()
        $bag = new Ops_Bag;
        for ($i = 0; $i < OPS; $i++) {
            $bag['status'] = $i;
        }
        return $bag->items['status'];
    },

    // --- isset() and at once the read of the same element (the pair _wp_array_get's loop is; added last too)
    'isset_read' => static function () use ($rows, $keys) {
        $v = 0;
        $row = $rows[2];
        for ($i = 0; $i < OPS; $i++) {
            $k = $keys[$i & 3];
            if (isset($row[$k])) {
                $x = $row[$k];
                $v += $x === 7 ? 1 : 2;
            }
        }
        return $v;
    },
    'isset_ternary' => static function () use ($rows) { // `isset($args['x']) ? $args['x'] : $default`
        $v = 0;
        for ($i = 0; $i < OPS; $i++) {
            $row = $rows[$i & 3];
            $v += isset($row['status']) ? $row['status'] : 1;
            $v += isset($row['missing']) ? $row['missing'] : 1;
        }
        return $v;
    },
];

$rounds = (int) ($argv[2] ?? 100);
$selected = isset($argv[3]) && $argv[3] !== '' ? explode(',', $argv[3]) : array_keys($kernels);
$inner = 100;
foreach ($selected as $name) {
    $kernel = $kernels[$name];
    for ($round = 0; $round < $rounds; $round++) {
        $t = hrtime(true);
        $sum = 0;
        for ($n = 0; $n < $inner; $n++) {
            $sum += $kernel();
        }
        printf("%s %d %.3f %d\n", $name, $round, (hrtime(true) - $t) / ($inner * OPS), $sum);
    }
}
