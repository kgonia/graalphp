<?php
// Return by reference (ZEND_RETURN_BY_REF) and the contexts that bind to a call's reference (ASSIGN_REF of a call,
// SEND_VAR_NO_REF, FE_RESET_RW of a call), with php-src's notices where a value comes instead of a reference.
set_error_handler(function ($type, $message, $file, $line) {
    echo "[$type] $message (line $line)\n";
    return true;
});

function &element(array &$array, $key) { return $array[$key]; }
$a = ['x' => 1];
$r = &element($a, 'y');   // the missing element is created, as a write would
$r = 5;
var_dump($a);

function &literal() { return 5; }
$v = &literal();
var_dump($v);
var_dump(literal() + 1);

function byValue() { return 7; }
$q = &byValue();
var_dump($q);
function &returnsByValueCall() { return byValue(); }
$p = &returnsByValueCall();
var_dump($p);

function &counter() { static $n = 0; $n++; return $n; }
function &chained() { return counter(); }
$c = &chained();
$c += 100;
var_dump(counter());

function &noReturn()
{
    echo "falls off the end\n";
}
$n = &noReturn();
var_dump($n);
function &emptyReturn() { return; }
$n = &emptyReturn();
var_dump($n);

class Box {
    public $data = [];
    public static $shared = 1;
    public $list = [1, 2];
    function &get($k) { return $this->data[$k]; }
    static function &shared() { return self::$shared; }
    function &self() { return $this; }
    function &fresh() { return new stdClass; }
    function &listRef() { return $this->list; }
    function plain() { return $this->list; }
}
$o = new Box;
$x = &$o->get('a');
$x = 'A';
$s = &Box::shared();
$s = 42;
var_dump($o->data, Box::$shared);
$t = &$o->self();
var_dump($t === $o);
$f = &$o->fresh();
var_dump(get_class($f));
$pl = &$o->plain();
$pl[] = 3;
var_dump($o->list);
$name = 'get';
$dyn = &$o->$name('dyn');
$dyn = 'D';
$cb = [$o, 'get'];
$viaArray = &$cb('cb');
$viaArray = 'CB';
var_dump($o->data);
$viaCallUserFunc = &call_user_func([$o, 'get'], 'cuf');
var_dump(array_key_exists('cuf', $o->data));

$arr = [1];
$closure = function &() use (&$arr) { return $arr[0]; };
$e = &$closure();
$e = 9;
$arrow = fn&() => $GLOBALS['arr']['k'];
$k = &$arrow();
$k = 'K';
$byName = 'counter';
$n = &$byName();
$n = 200;
var_dump($arr, counter());

foreach ($o->listRef() as &$item) {
    $item *= 10;
}
unset($item);
var_dump($o->list);
foreach ([1, 2] as &$item) {
    $item *= 2;
}
var_dump($item);
unset($item);
function pair() { return [1, 2]; }
foreach (pair() as &$item) {
    $item *= 2;
}
unset($item);
var_dump(pair());

$list = [];
$list[] = &$o->get('appended');
$list[0] = 'AP';
$list['key'] = &byValue();
$holder = new stdClass;
$holder->p = &$o->get('prop');
$holder->p = 'PR';
var_dump($o->data, $list);

function setTo(&$target, $value) { $target = $value; }
setTo($o->get('param'), 'PA');
var_dump($o->data['param']);
setTo(byValue(), 1);
$words = 'a,b,c';
var_dump(end(explode(',', $words)));

function &typed(): int { static $v = "12"; return $v; }
$ty = &typed();
var_dump($ty);
$ty = "34";
var_dump(typed());

// copy-on-write through a returned reference: a value copy is separate, a reference is shared
function &staticList() { static $l = [1, 2]; return $l; }
$copy = staticList();
$copy[] = 3;
$alias = &staticList();
$alias[] = 4;
var_dump(staticList(), $copy);
function &localList() { $l = [1, 2]; return $l; }
$first = &localList();
$second = $first;
$second[] = 3;
var_dump($first, $second);
$values = localList();
$values[] = 5;
var_dump($values);

// returning a property by reference makes it a reference; one nothing else holds is copied as a value by clone
$box = new Box;
$value = $box->listRef();
$cloned = clone $box;
$cloned->list = [];
$shared = &$box->listRef();
$sharedClone = clone $box;
$sharedClone->list = ['shared'];
var_dump($box->list);

// a tree of arrays returned by reference, as graalphp's binary-trees benchmark builds it
function &tree($depth)
{
    if (!$depth) {
        $leaf = [-1, -1];
        return $leaf;
    }
    $depth--;
    $node = [tree($depth), tree($depth)];
    return $node;
}
function check(&$node)
{
    return 1 + ($node[0][0] == -1 ? 1 : check($node[0])) + ($node[1][0] == -1 ? 1 : check($node[1]));
}
$tree = &tree(6);
var_dump(check($tree));
unset($tree);
