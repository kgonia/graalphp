<?php
// PHP 8.4 property hooks: get/set, backed and virtual properties, parent::$prop::get(), inheritance, interfaces,
// references, magic fallbacks, and how var_dump, get_object_vars, foreach, serialize and clone see them.

interface Named {
    public string $name { get; }
}

class Person implements Named {
    public string $first = 'Ada';
    public string $last = 'Lovelace';
    public string $name {
        get => $this->first . ' ' . $this->last;
        set {
            [$this->first, $this->last] = explode(' ', $value, 2);
        }
    }
    public int $age = 36 {
        get => $this->age;
        set {
            if ($value < 0) {
                throw new InvalidArgumentException("negative age");
            }
            $this->age = $value;
        }
    }
    public $log = [] {
        set => array_merge($this->log, $value);
    }
    private $secret = 's' {
        get => strtoupper($this->secret);
    }

    public function secret() {
        return $this->secret;
    }
}

$p = new Person;
var_dump($p->name, $p->age);
$p->name = 'Grace Hopper';
var_dump($p->first, $p->last, $p->name);
$p->age = 85;
var_dump($p->age);
try {
    $p->age = -1;
} catch (InvalidArgumentException $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
$p->log = ['a'];
$p->log = ['b'];
var_dump($p->log);
var_dump($p->secret());
try {
    var_dump($p->secret);
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
var_dump(isset($p->name), isset($p->nope));
var_dump($p instanceof Named);
var_dump(get_object_vars($p));
foreach ($p as $key => $value) {
    echo $key, ' => ', is_array($value) ? implode(',', $value) : $value, "\n";
}
echo serialize($p), "\n";
$q = clone $p;
$q->age = 1;
var_dump($q->age, $p->age);

// virtual properties: read-only, write-only
class Temperature {
    public function __construct(public float $celsius = 20.0) {}
    public float $fahrenheit {
        get => $this->celsius * 9 / 5 + 32;
        set => $this->celsius = ($value - 32) * 5 / 9;
    }
    public $readOnly {
        get => 'ro';
    }
    public $writeOnly {
        set { echo "written $value\n"; }
    }
}

$t = new Temperature();
var_dump($t->fahrenheit);
$t->fahrenheit = 212;
var_dump($t->celsius);
var_dump($t->readOnly);
foreach (['readOnly' => 1, 'writeOnly' => null] as $name => $value) {
    try {
        if ($value === null) {
            var_dump($t->$name);
        } else {
            $t->$name = $value;
        }
    } catch (Error $e) {
        echo $e->getMessage(), "\n";
    }
}
$t->writeOnly = 5;
try {
    unset($t->fahrenheit);
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
var_dump($t);

// inheritance and parent::$prop::get()
class Base {
    public $value = 1 {
        get {
            echo __METHOD__, "\n";
            return $this->value;
        }
    }
}

class Child extends Base {
    public $value {
        get => parent::$value::get() * 10;
        set {
            echo __FUNCTION__, ' ', __PROPERTY__, "\n";
            parent::$value::set($value + 1);
        }
    }
}

$c = new Child;
var_dump($c->value);
$c->value = 4;
var_dump($c->value);

class Plain extends Base {
    public $value = 7;
}
var_dump((new Plain)->value);

// compound assignments and increments go through both hooks
class Counter {
    public int $count = 0 {
        get {
            echo "get\n";
            return $this->count;
        }
        set {
            echo "set $value\n";
            $this->count = $value;
        }
    }
}
$counter = new Counter;
$counter->count++;
$counter->count += 5;
var_dump($counter->count);

// indirect modification and references
class Holder {
    public array $items = [] {
        get => $this->items;
    }
    private array $data = [];
    public array $refs {
        &get => $this->data;
    }
}
$h = new Holder;
try {
    $h->items[] = 1;
} catch (Error $e) {
    echo $e->getMessage(), "\n";
}
$h->refs[] = 'x';
$h->refs[] = 'y';
var_dump($h->refs);

// __get/__set don't apply to hooked properties
class Magic {
    public $hooked {
        get => 'hooked';
    }
    public function __get($name) {
        return "magic $name";
    }
}
$m = new Magic;
var_dump($m->hooked, $m->other);

// an exception from a hook carries its frame
class Thrower {
    public $prop {
        get => throw new RuntimeException('from hook');
    }
}
try {
    (new Thrower)->prop;
} catch (RuntimeException $e) {
    echo $e->getMessage(), ' at line ', $e->getLine(), "\n";
}
