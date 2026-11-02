<?php
// $this and properties in functions and methods on bytecode (-Dpolyglot.php.Interpreter=bytecode): reads and
// writes, by name and computed, compound assignments and increments, unset() and isset(), element writes through a
// property, properties by reference, visibility from the calling scope (methods, subclasses, bound closures), the
// magic methods, and copy-on-write of arrays in properties. The code that creates objects and calls methods stays
// on the tree for now; the functions and methods it calls have only what bytecode covers.

class Point {
    public $x = 1;
    public $y = 2;
    protected $hidden = 'protected';
    private $secret = 'private';
    public $items = [];
    public $map = [];
    public $count = 0;
    public $text = 'a';

    function sum() {
        return $this->x + $this->y;
    }

    function move($dx, $dy) {
        $this->x += $dx;
        $this->y -= $dy;
        $this->text .= 'b';
        $this->count++;
        ++$this->count;
        $old = $this->count--;
        return $old;
    }

    function secrets() {
        return $this->hidden . ' ' . $this->secret;
    }

    function setSecret($value) {
        $this->secret = $value;
        $this->hidden = $value;
    }

    function add($item) {
        $this->items[] = $item;
        $this->map[$item] = count($this->items);
        $key = 'k';
        $this->map[$key] = $item;
        return count($this->items);
    }

    function hasThis() {
        return isset($this);
    }

    static function staticHasThis() {
        return isset($this);
    }

    static function staticThis() {
        return $this;
    }

    function self() {
        return $this;
    }

    function issets() {
        echo isset($this->x) ? 'x set' : 'x unset', ', ';
        echo isset($this->secret) ? 'secret set' : 'secret unset', ', ';
        echo isset($this->nothing) ? 'nothing set' : 'nothing unset', "\n";
    }
}

class Sub extends Point {
    function hiddenFromSub() {
        return $this->hidden;
    }

}

function show($label, $value) {
    echo $label, ': ', json_encode($value), "\n";
}

function tryRun($label, $fn) {
    try {
        show($label, $fn());
    } catch (Error $e) {
        echo $label, ': ', get_class($e), ': ', $e->getMessage(), "\n";
    }
}

$p = new Point();
show('sum', $p->sum());
show('move', $p->move(10, 20));
show('after move', [$p->x, $p->y, $p->text, $p->count]);
show('secrets', $p->secrets());
$p->setSecret('changed');
show('secrets changed', $p->secrets());
show('add', [$p->add('a'), $p->add('b')]);
show('items', [$p->items, $p->map]);
show('hasThis', [$p->hasThis(), Point::staticHasThis()]);
tryRun('static this', fn() => Point::staticThis());
show('self', $p->self() === $p);
$p->issets();

$s = new Sub();
show('hidden from sub', $s->hiddenFromSub());

// functions outside the class: public properties only
function readProps($o) {
    return [$o->x, $o->y];
}
function writeProps($o, $v) {
    $o->x = $v;
    $o->y = $o->x * 2;
    return $o->y;
}
function readHidden($o) {
    return $o->hidden;
}
function writeSecret($o) {
    $o->secret = 'from outside';
}
function issetOutside($o) {
    return [isset($o->x), isset($o->hidden), isset($o->secret), isset($o->missing)];
}
show('read', readProps($p));
show('write', writeProps($p, 5));
tryRun('read protected', fn() => readHidden($p));
tryRun('write private', fn() => writeSecret($p));
show('isset outside', issetOutside($p));

// computed names
function byName($o, $name, $value) {
    $o->$name = $value;
    $after = $o->$name;
    $isset = isset($o->$name);
    unset($o->$name);
    return [$after, $isset, isset($o->$name)];
}
function readByName($o, $name) {
    return $o->$name;
}
$o = new stdClass();
$o->dyn = 'dynamic';
show('read by name', readByName($o, 'dyn'));
show('by name', byName($o, 'dyn', 'new'));
show('by name, a number', byName($o, 12, 'twelve'));
show('after', (array) $o);

// unset and isset of declared and dynamic properties, nested
function unsets($o) {
    unset($o->x);
    $wasSet = isset($o->x);
    $o->x = 'again';
    return [$wasSet, $o->x];
}
show('unset', unsets(new Point()));
function nested($o) {
    return [isset($o->inner->value), isset($o->inner->missing), isset($o->none->value)];
}
$outer = new stdClass();
$outer->inner = new stdClass();
$outer->inner->value = 'deep';
show('nested', nested($outer));
show('nested on null', nested(new stdClass()));

// errors of writes to what isn't an object (the tree's $null->x++ says "assign", php-src's and bytecode's
// "increment/decrement")
function assignToNull($v) {
    $v->x = 1;
}
function compoundOnNull($v) {
    $v->x += 1;
}
function appendOnNull($v) {
    $v->list[] = 1;
}
function unsetOnNull($v) {
    unset($v->x);
    return 'unset of a property of null does nothing';
}
function issetOnNull($v) {
    return isset($v->x);
}
function readOnArray($v) {
    return $v->x;
}
tryRun('assign to null', fn() => assignToNull(null));
tryRun('compound on null', fn() => compoundOnNull(null));
tryRun('append on null', fn() => appendOnNull(null));
tryRun('unset on null', fn() => unsetOnNull(null));
tryRun('isset on null', fn() => issetOnNull(null));

// magic methods
class Magic {
    public $log = [];
    private $data = ['a' => 1];
    private $hiddenWithMagic = 'hidden';

    function __get($name) {
        $this->log[] = 'get ' . $name;
        return $this->data[$name] ?? null;
    }

    function __set($name, $value) {
        $this->log[] = 'set ' . $name;
        $this->data[$name] = $value;
    }

    function __isset($name) {
        $this->log[] = 'isset ' . $name;
        return isset($this->data[$name]);
    }

    function __unset($name) {
        $this->log[] = 'unset ' . $name;
        unset($this->data[$name]);
    }
}
function useMagic($m) {
    $a = $m->a;
    $m->b = 2;
    $m->a += 10;
    $m->c .= 'x';
    $m->b++;
    $isset = [isset($m->a), isset($m->zzz), isset($m->hiddenWithMagic)];
    unset($m->a);
    $hidden = $m->hiddenWithMagic;
    $m->hiddenWithMagic = 'outside';
    return [$a, $isset, $hidden];
}
$m = new Magic();
show('magic', useMagic($m));
show('magic log', $m->log);

// references in properties: writes go through them
function writeThrough($o) {
    $o->x = 'through the reference';
    $o->count += 5;
}
$q = new Point();
$ref = &$q->x;
$cnt = &$q->count;
writeThrough($q);
show('reference', [$ref, $cnt]);
function byRef(&$value) {
    $value = 'by reference';
}
function byValue($value) {
    $value = 'by value';
}
function sendProps($o) {
    byValue($o->y);
    byRef($o->x);
    byRef($o->fresh);
    return [$o->x, $o->y, $o->fresh];
}
$o = new stdClass();
$o->x = 'x';
$o->y = 'y';
show('send', sendProps($o));

// copy-on-write of arrays in properties
function cow($o) {
    $a = [1, 2];
    $o->items = $a;
    $o->items[] = 3;
    $b = $o->items;
    $o->items[0] = 9;
    $o->map = $o->items;
    $o->map['k'] = 'v';
    return [$a, $b, $o->items, $o->map];
}
show('cow', cow(new Point()));

// a property holding an ArrayAccess object takes element writes
class Bag implements ArrayAccess {
    public $data = [];
    function offsetExists($k): bool { return isset($this->data[$k]); }
    function offsetGet($k): mixed { return $this->data[$k]; }
    function offsetSet($k, $v): void { if ($k === null) { $this->data[] = $v; } else { $this->data[$k] = $v; } }
    function offsetUnset($k): void { unset($this->data[$k]); }
}
function intoBag($o) {
    $o->items['a'] = 1;
    $o->items[] = 2;
    return $o->items->data;
}
$holder = new stdClass();
$holder->items = new Bag();
show('bag', intoBag($holder));

// closures bound to an object see its private properties; unbound ones don't
$peek = function () {
    return $this->secret;
};
show('bound', Closure::bind($peek, new Point(), Point::class)());
tryRun('bound outside the class', fn() => Closure::bind($peek, new Point(), null)());
$peekStatic = static function ($o) {
    return $o->secret;
};
show('scoped static closure', Closure::bind($peekStatic, null, Point::class)(new Point()));

// generators read and write properties around their yields
function counter($o) {
    while ($o->count < 3) {
        $o->count++;
        yield $o->count => $o->text;
        $o->text .= '+';
    }
}
$c = new Point();
foreach (counter($c) as $k => $v) {
    echo $k, ' ', $v, "\n";
}
show('generator', [$c->count, $c->text]);

// in a generator, which runs on bytecode in either mode: ++ and -- of a property of null say "increment/decrement"
// as php-src's do (the tree's say "assign")
function incOnNull($v) {
    try {
        $v->x++;
    } catch (Error $e) {
        yield $e->getMessage();
    }
    try {
        --$v->x;
    } catch (Error $e) {
        yield $e->getMessage();
    }
}
foreach (incOnNull(null) as $message) {
    echo $message, "\n";
}
