<?php
// Static properties in functions and methods on bytecode (BytecodeTranslator's RefPlace): reads by every class
// reference (a name, self, parent, static, a variable, an expression), writes, compound assignments, increments,
// isset(), empty(), ??, ??=, unset() (an Error), by-reference and by-value arguments, references taken (=&, &, return
// by reference, foreach by reference), elements under a static property (read, written, appended, compound,
// increments, isset, ??=, unset, by reference), static properties as foreach and list() targets, the order of
// evaluation, and the errors for undeclared properties.

class Registry {
    public static $count = 0;
    public static $name = 'registry';
    public static $items = [];
    public static $map = ['a' => ['b' => 1], 'list' => [3, 1, 2]];
    public static $nothing = null;
    public static $text = 'x';
    public static $float = 1.5;
    protected static $hidden = 'hidden';

    static function bump() {
        self::$count++;
        ++self::$count;
        $post = self::$count--;
        $pre = --self::$count;
        static::$count += 10;
        self::$count -= 3;
        self::$count *= 2;
        self::$text .= 'y';
        self::$float /= 2;
        return [$post, $pre, self::$count, self::$text, self::$float];
    }

    static function hidden() {
        return self::$hidden . ' ' . static::$hidden;
    }

    static function add($item) {
        self::$items[] = $item;
        static::$map[$item] = count(self::$items);
        self::$map['nested'][$item][] = $item;
        return count(self::$items);
    }

    static function &itemsRef() {
        return self::$items;
    }

    static function cached($key) {
        return self::$map['cache'][$key] ??= strtoupper($key);
    }
}

class Child extends Registry {
    public static $own = 'own';

    static function names() {
        return [self::$own, parent::$name, static::$name, self::$name];
    }

    static function shared() {
        // an inherited static property is the parent's
        self::$count = 100;
        return Registry::$count;
    }
}

function show($label, $value) {
    echo $label, ': ', json_encode($value), "\n";
}

function reads() {
    show('named', Registry::$name);
    $class = 'Registry';
    show('variable class', $class::$name);
    $object = new Child();
    show('object class', $object::$own);
    show('expression class', (fn() => 'Child')()::$own);
    show('names', Child::names());
    show('hidden', Registry::hidden());
    show('in an expression', Registry::$count + 1);
    show('element', Registry::$map['a']['b']);
    show('interpolated', "name " . Registry::$name . " {$class}");
}

function writes() {
    Registry::$name = 'renamed';
    $value = Registry::$count = 5;
    show('assigned', [$value, Registry::$count, Registry::$name]);
    show('bump', Registry::bump());
    show('shared', Child::shared());
    Registry::$count = 0;
    $a = [1, 2];
    Registry::$items = $a;
    $a[] = 3;
    show('copied on write', [$a, Registry::$items]);
    Registry::$items = [];
}

function elements() {
    show('add', [Registry::add('p'), Registry::add('q')]);
    show('map', Registry::$map);
    Registry::$map['a']['b'] += 5;
    Registry::$map['a']['c'] = 'new';
    Registry::$map['a']['b']++;
    $old = Registry::$map['a']['b']--;
    Registry::$map['a']['s'] = 'a';
    Registry::$map['a']['s'] .= 'b';
    show('nested', [$old, Registry::$map['a']]);
    unset(Registry::$map['a']['c'], Registry::$map['missing']['x']);
    show('unset', Registry::$map['a']);
    show('cached', [Registry::cached('k'), Registry::cached('k'), Registry::$map['cache']]);
    $key = 'dyn';
    Registry::$map[$key] = 1;
    Registry::$map[strtoupper($key)] = 2;
    show('keys', [Registry::$map['dyn'], Registry::$map['DYN']]);
    $self = Registry::$map;
    Registry::$map['self'] = Registry::$map;
    show('self copy', count(Registry::$map['self']) === count($self));
    unset(Registry::$map['self']);
}

function tests() {
    show('isset', [isset(Registry::$name), isset(Registry::$nothing), isset(Registry::$undeclared),
        isset(Registry::$map['a']['b']), isset(Registry::$map['zz']['b']), isset(Child::$own)]);
    show('empty', [empty(Registry::$name), empty(Registry::$nothing), empty(Registry::$undeclared),
        empty(Registry::$map['a']), empty(Registry::$map['zz'])]);
    show('coalesce', [Registry::$nothing ?? 'default', Registry::$undeclared ?? 'undeclared',
        Registry::$map['a']['b'] ?? 'd', Registry::$map['x']['y'] ?? 'd', Registry::$name ?? 'd']);
    Registry::$nothing ??= 'filled';
    Registry::$nothing ??= 'not again';
    Registry::$map['fresh'] ??= [];
    Registry::$map['fresh']['k'] ??= 'v';
    show('coalesce assigned', [Registry::$nothing, Registry::$map['fresh']]);
    Registry::$nothing = null;
}

function addOne(&$list) {
    $list[] = is_array($list) ? count($list) : 0;
}

function byValue($value) {
    return is_array($value) ? count($value) : $value;
}

function arguments() {
    addOne(Registry::$items);
    addOne(Registry::$items);
    addOne(Registry::$map['new']);
    sort(Registry::$map['list']);
    show('by reference', [Registry::$items, Registry::$map['new'], Registry::$map['list']]);
    show('by value', [byValue(Registry::$items), byValue(Registry::$map['list']), strlen(Registry::$name),
        byValue(Registry::$map['missing'] ?? 'none')]);
    $class = 'Registry';
    addOne($class::$items);
    show('variable class by reference', count(Registry::$items));
    array_push(Registry::$items, 'pushed');
    show('array_push', Registry::$items);
    Registry::$items = [];
}

function references() {
    $r = &Registry::$count;
    $r = 42;
    show('reference to', Registry::$count);
    $element = &Registry::$map['refd'];
    $element = 'through a reference';
    show('reference to an element', Registry::$map['refd']);
    $list = [];
    $list[] = &Registry::$name;
    $list[0] = 'via array';
    show('array element bound', Registry::$name);
    $literal = ['k' => &Registry::$text];
    show('array literal of a reference', $literal);
    $items = &Registry::itemsRef();
    $items[] = 'returned by reference';
    show('return by reference', Registry::$items);
    Registry::$items = [1, 2, 3];
    foreach (Registry::$items as &$item) {
        $item *= 10;
    }
    unset($item);
    show('foreach by reference', Registry::$items);
    foreach (Registry::$map['list'] as &$n) {
        $n = -$n;
    }
    unset($n);
    show('foreach by reference over an element', Registry::$map['list']);
    $other = 'other';
    Registry::$map['bound'] = &$other;
    $other = 'changed';
    show('element bound', Registry::$map['bound']);
    Registry::$count = 0;
}

function targets() {
    foreach (['a', 'b'] as Registry::$name) {
    }
    foreach (['k' => 'v'] as Registry::$text => Registry::$map['value']) {
    }
    [Registry::$count, Registry::$map['listed']] = [7, 8];
    ['x' => Registry::$float] = ['x' => 2.5];
    show('targets', [Registry::$name, Registry::$text, Registry::$map['value'], Registry::$count,
        Registry::$map['listed'], Registry::$float]);
}

function order() {
    $log = [];
    $class = function () use (&$log) {
        $log[] = 'class';
        return 'Registry';
    };
    $value = function () use (&$log) {
        $log[] = 'value';
        return 'v';
    };
    $key = function () use (&$log) {
        $log[] = 'key';
        return 'k';
    };
    $class()::$text = $value();
    $class()::$map[$key()] = $value();
    $class()::$text .= $value();
    show('order', $log);
}

function errors() {
    foreach ([
        fn() => Registry::$undeclared,
        fn() => Child::$undeclared = 1,
        function () {
            $class = 'Child';
            return $class::$missing;
        },
        fn() => Registry::$undeclared['k'] = 1,
        fn() => Registry::$undeclared++,
        function () {
            unset(Registry::$name);
        },
        function () {
            unset(Child::$undeclared);
        },
        function () {
            $class = 'Child';
            unset($class::$name);
        },
        fn() => Missing::$x,
        fn() => isset(Missing::$x),
        function () {
            Registry::$count = 5;
            Registry::$count[] = 'x';
        },
    ] as $f) {
        try {
            $f();
            echo "no error\n";
        } catch (Error $e) {
            echo get_class($e), ': ', $e->getMessage(), "\n";
        }
    }
}

reads();
writes();
elements();
tests();
arguments();
references();
targets();
order();
errors();

// static:: in a closure written outside a class: the class it is bound to, else an Error
$late = function () {
    return static::$name . ' ' . static::$own;
};
show('static in a bound closure', Closure::bind($late, null, Child::class)());
try {
    $late();
} catch (Error $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
