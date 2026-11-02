<?php
// isset(), empty(), ?? and ??= in functions on bytecode (-Dpolyglot.php.Interpreter=bytecode): variables,
// elements, nested elements, string offsets, ArrayAccess objects, properties with __isset and __get, calls on the
// left of ??, and the order in which containers, keys and values are evaluated.

class Bag implements ArrayAccess {
    public $items = [];

    function __construct($items) {
        $this->items = $items;
    }

    function offsetExists($offset): bool {
        echo "[exists ", json_encode($offset), "]";
        return isset($this->items[$offset]);
    }

    function offsetGet($offset): mixed {
        echo "[get ", json_encode($offset), "]";
        return $this->items[$offset];
    }

    function offsetSet($offset, $value): void {
        echo "[set ", json_encode($offset), "]";
        if ($offset === null) {
            $this->items[] = $value;
        } else {
            $this->items[$offset] = $value;
        }
    }

    function offsetUnset($offset): void {
        echo "[unset ", json_encode($offset), "]";
        unset($this->items[$offset]);
    }
}

class Magic {
    private $data = ['a' => 1, 'z' => 0, 'n' => null, 'arr' => ['k' => 'v']];
    public $plain = 'plain';
    public $nothing = null;

    function __isset($name) {
        echo "[__isset $name]";
        return array_key_exists($name, $this->data);
    }

    function __get($name) {
        echo "[__get $name]";
        return $this->data[$name];
    }

    function __set($name, $value) {
        echo "[__set $name]";
        $this->data[$name] = $value;
    }
}

class Node {
    public $next;
    public $value;
    public $list = [];

    function __construct($value, $next = null) {
        $this->value = $value;
        $this->next = $next;
    }

    function check() {
        return [isset($this->value), isset($this->next->value), isset($this->next->next->value),
                isset($this->list['x']), $this->next->value ?? 'none', empty($this->list)];
    }

    function fill() {
        $this->value ??= 'filled';
        $this->list['k'] ??= 'list default';
        return [$this->value, $this->list];
    }
}

function side($label, $value) {
    echo "[$label]";
    return $value;
}

function show($label, $value) {
    echo $label, ': ', json_encode($value), "\n";
}

function variables($p) {
    $set = 1;
    $null = null;
    $zero = 0;
    show('isset vars', [isset($set), isset($null), isset($zero), isset($undefined), isset($p), isset($set, $zero),
                        isset($set, $null)]);
    show('empty vars', [empty($set), empty($null), empty($zero), empty($undefined), empty($p), empty('0'),
                        empty([]), empty('a')]);
    show('coalesce vars', [$set ?? 'd', $null ?? 'd', $zero ?? 'd', $undefined ?? 'd', $null ?? $undefined ?? 'last']);
}

function references() {
    $null = null;
    $ref = &$null;
    show('reference', [isset($ref), $ref ?? 'via ref']);
    $null = 5;
    show('reference set', [isset($ref), $ref ?? 'via ref']);
}

function elements() {
    $a = ['x' => 1, 'n' => null, 'z' => 0, 'list' => [10, null, 30], 'deep' => ['a' => ['b' => 'c']], 5 => 'five'];
    $k = 'x';
    show('isset elements', [isset($a['x']), isset($a['n']), isset($a['z']), isset($a['missing']), isset($a[$k]),
                            isset($a['list'][0]), isset($a['list'][1]), isset($a['list'][9]),
                            isset($a['deep']['a']['b']), isset($a['deep']['a']['x']), isset($a['x']['y']),
                            isset($a['5']), isset($a[true])]);
    show('empty elements', [empty($a['x']), empty($a['n']), empty($a['z']), empty($a['missing']),
                            empty($a['deep']['a']), empty($a['list'][1])]);
    show('coalesce elements', [$a['x'] ?? 'd', $a['n'] ?? 'd', $a['missing'] ?? 'd', $a['deep']['a']['b'] ?? 'd',
                               $a['deep']['q']['b'] ?? 'd', $a['list'][2] ?? 'd', $nope['a']['b'] ?? 'undefined']);
    $s = 'abc';
    show('string offsets', [isset($s[0]), isset($s[2]), isset($s[3]), isset($s[-1]), isset($s[-4]), isset($s['1']),
                            isset($s['x']), isset($s['1x']), isset($s[0][0]), isset($s[0][1]), $s[1] ?? 'd',
                            $s[7] ?? 'd', empty($s[0]), empty($s[9])]);
    $n = null;
    $i = 7;
    show('scalars', [isset($n['a']), isset($i['a']), $i['a'] ?? 'int', empty($i[0]), isset($a['x']->p)]);
    show('key order', [isset($n[side('key of null', 'k')]), $n[side('key of null', 'k')] ?? 'd',
                       isset($a[side('key', 'x')][side('inner', 0)])]);
}

function access($bag) {
    show('isset bag', [isset($bag['a']), isset($bag['n']), isset($bag['missing'])]);
    echo "\n";
    show('empty bag', [empty($bag['a']), empty($bag['z']), empty($bag['missing'])]);
    echo "\n";
    show('coalesce bag', [$bag['a'] ?? 'd', $bag['n'] ?? 'd', $bag['missing'] ?? 'd', $bag['arr']['k'] ?? 'd']);
    echo "\n";
    show('nested isset bag', [isset($bag['arr']['k']), isset($bag['arr']['zz'])]);
    echo "\n";
}

function properties($m, $node) {
    show('isset magic', [isset($m->a), isset($m->z), isset($m->n), isset($m->missing), isset($m->plain),
                         isset($m->nothing), isset($m->arr['k']), isset($m->arr['q'])]);
    echo "\n";
    show('empty magic', [empty($m->a), empty($m->z), empty($m->missing), empty($m->plain)]);
    echo "\n";
    show('coalesce magic', [$m->a ?? 'd', $m->n ?? 'd', $m->missing ?? 'd', $m->arr['k'] ?? 'd']);
    echo "\n";
    $name = 'a';
    show('computed name', [isset($m->$name), isset($node->$name)]);
    echo "\n";
    show('chain', [isset($node->next->value), isset($node->next->next), $node->next->next->value ?? 'end',
                   isset($node->missing->value)]);
    show('method check', $node->check());
    $none = null;
    show('null object', [isset($none->p), $none->p ?? 'null object', empty($none->p), isset($none->p->q)]);
}

function calls() {
    show('calls', [side('call', null) ?? 'default', side('call', 0) ?? 'default', side('call', ['k' => 1])['k'] ?? 'd',
                   side('call', ['k' => 1])['q'] ?? 'd', isset(side('call', ['k' => 1])['k']),
                   empty(side('call', '')), empty(side('call', [0])[0])]);
    show('right not evaluated', 'set' ?? side('never', 'x'));
    echo "\n";
}

function coalesce_assign() {
    $a = null;
    $a ??= side('first', 1);
    $a ??= side('second', 2);
    $arr = [];
    $arr['x'] ??= side('x', 'X');
    $arr['x'] ??= side('x again', 'Y');
    $arr['n'] = null;
    $arr['n'] ??= 'replaced null';
    $i = 0;
    $arr[$i] ??= 'zero';
    $keys = ['k1', 'k2'];
    $j = 0;
    $arr[$keys[$j++]] ??= 'once';
    $arr[$keys[$j++]] ??= 'twice';
    $result = ($undefined ??= 'from undefined');
    echo "\n";
    show('??=', [$a, $arr, $j, $result, $undefined]);
    $m = new Magic();
    $m->a ??= side('magic set', 'never');
    $m->fresh ??= side('magic fresh', 'fresh');
    echo "\n";
    $node = new Node(null);
    show('fill', $node->fill());
    $o = new stdClass();
    $o->list ??= [];
    $o->name ??= 'named';
    $o->name ??= 'renamed';
    show('object', $o);
    $copy = $arr;
    $copy['x'] ??= 'no';
    $copy['new'] ??= 'yes';
    show('copy on write', [$arr, $copy]);
}

function gen() {
    $data = ['a' => 1];
    $got = (yield isset($data['a'])) ?? 'nothing sent';
    echo "got $got\n";
    $data['b'] ??= yield 'needs b';
    yield $data;
    yield empty($data['c']) ? 'c empty' : 'c set';
}

variables(1);
variables(null);
references();
elements();
access(new Bag(['a' => 1, 'n' => null, 'z' => 0, 'arr' => ['k' => 'v']]));
properties(new Magic(), new Node(1, new Node(2)));
calls();
coalesce_assign();
$g = gen();
show('gen', $g->current());
show('gen', $g->send(null));
show('gen', $g->send('B'));
$g->next();
show('gen', $g->current());
