<?php
// Places whose object or container is an expression, in functions on bytecode: properties of calls, of elements and
// of property chains written, combined, incremented, appended to, bound and unset, in php-src's order (a call as
// the object and the keys before the value, the chain fetched after it); `??=` on them and on computed names;
// arguments that are elements of a call's result (by reference: a copy only the callee sees), `$x[]` (by reference
// only), properties by a computed name, and php-src's Errors for the others.

function show($label, $value) {
    echo $label, ': ', json_encode($value), "\n";
}

function report($label, $e) {
    echo $label, ': ', get_class($e), ': ', $e->getMessage(), "\n";
}

function trace($label, $value) {
    echo "  eval $label\n";
    return $value;
}

function setRef(&$x, $value = 'set') {
    $x = $value;
}

class Node {
    public $value = 0;
    public $list = [];
    public $child;
    public $items = [];
    public $name = 'node';
    public $missing;
    public $other;

    function get() {
        echo "  get()\n";
        return $this;
    }

    function child() {
        return $this->child ??= new Node();
    }
}

function writes() {
    $n = new Node();
    trace('object', $n)->value = trace('value', 1);
    show('call object', $n->value);
    $n->get()->name = 'renamed';
    show('method object', $n->name);
    $nodes = [new Node(), new Node()];
    $nodes[trace('key', 1)]->value = trace('value', 2);
    show('element object', $nodes[1]->value);
    $n->child()->child()->value = 'deep';
    show('chain', $n->child->child->value);
    $n->items['a'] = new Node();
    $n->items['a']->list['k'] = 'v';
    $n->items['a']->list[] = 'appended';
    $n->get()->list[trace('key', 'x')]['y'] = trace('value', 'nested');
    show('element of a property of a call', $n->list);
    $nodes[0]->list[] = 'one';
    $nodes[0]->list[] = 'two';
    show('appended', $nodes[0]->list);
    $name = 'name';
    $nodes[0]->$name = 'by name';
    $n->get()->{'na' . 'me'} = trace('value', 'computed');
    show('computed names', [$nodes[0]->name, $n->name]);
    $kind = 'list';
    $nodes[1]->{$kind}[] = 'appended by name';
    $nodes[1]->$kind['key'] = 'written by name';
    $nodes[trace('key', 1)]->{trace('name', 'items')}[] = trace('value', 'ordered');
    show('elements by name', [$nodes[1]->list, $nodes[1]->items]);
}

function updates() {
    $nodes = [new Node()];
    $nodes[0]->value += 5;
    $nodes[0]->value .= '!';
    show('compound', $nodes[0]->value);
    $n = new Node();
    $n->get()->value++;
    ++$n->get()->value;
    show('increment', $n->value);
    show('post', $n->get()->value--);
    $n->child()->list['count'] = 1;
    $n->child()->list['count'] *= 10;
    show('compound element', $n->child->list);
    $n->get()->list['i'] = 1;
    $n->get()->list['i']++;
    show('increment element', $n->list);
    unset($n->get()->list['i']);
    unset($nodes[0]->list);
    show('unset', [$n->list, isset($nodes[0]->list)]);
}

function coalesce() {
    $n = new Node();
    $n->get()->name ??= trace('value', 'unused');
    $n->get()->missing ??= trace('value', 'filled');
    show('??=', [$n->name, $n->missing]);
    $prop = 'other';
    $n->$prop ??= 'computed';
    $n->$prop ??= 'again';
    show('??= computed', $n->other);
    $nodes = [new Node()];
    $nodes[0]->list['k'] ??= 'element';
    show('??= element', $nodes[0]->list);
}

function references() {
    $nodes = [new Node()];
    $r = &$nodes[0]->value;
    $r = 'through a reference';
    show('reference to', $nodes[0]->value);
    $target = 'bound';
    $nodes[0]->name = &$target;
    $target = 'bound and changed';
    show('bound', $nodes[0]->name);
    $n = new Node();
    $n->list = [1, 2];
    foreach ($n->get()->list as &$item) {
        $item *= 10;
    }
    unset($item);
    show('foreach by reference', $n->list);
    setRef($n->get()->name, 'set by reference');
    show('argument property of a call', $n->name);
    $prop = 'value';
    setRef($n->$prop, 'computed by reference');
    show('argument computed name', $n->value);
    show('argument computed name by value', strtoupper($n->$prop));
    $data = $n;
    $data = &$data->$prop;
    $data = 'through a computed reference';
    show('reference by name', $n->value);
}

function pair() {
    return ['first', 'second'];
}

function &shared() {
    static $kept = ['kept'];
    return $kept;
}

function temporaries() {
    show('element of a call', strtoupper(pair()[1]));
    show('element of a literal', strtoupper(['a', 'b'][1]));
    setRef(pair()[0]);
    setRef(pair()[5]);
    setRef(shared()[0], 'changed copy');
    show('through the returned reference', shared());
    $f = 'setRef';
    $f(pair()[0]);
    try {
        $f(['literal'][0]);
    } catch (Error $e) {
        report('literal by reference', $e);
    }
    $list = [];
    setRef($list[]);
    setRef($list[], 'second');
    show('append by reference', $list);
    $n = new Node();
    setRef($n->list[]);
    show('append to a property', $n->list);
    try {
        $g = 'strlen';
        $g($list[]);
    } catch (Error $e) {
        report('append by value', $e);
    }
}

function errors() {
    $nodes = [];
    try {
        $nodes[0]->value = 1;
    } catch (Error $e) {
        report('null object', $e);
    }
    try {
        pair()->value = 1;
    } catch (Error $e) {
        report('array object', $e);
    }
}

writes();
updates();
coalesce();
references();
temporaries();
errors();
