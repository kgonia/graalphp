<?php
// Pass 1 re-review: $obj->$name operands stay alive while PHP code runs, and builtins chain the exceptions of a
// throwing __toString and a throwing __destruct.
#[AllowDynamicProperties]
class Box {
    public $id;
    function __construct($id) { $this->id = $id; }
    function __destruct() { echo "Box{$this->id} gone\n"; }
}
class Name {
    public $text;
    function __construct($text) { $this->text = $text; }
    function __toString(): string { global $o, $k, $v; $o = null; $k = null; $v = null; echo "toString {$this->text}\n"; return $this->text; }
    function __destruct() { echo "Name {$this->text} gone\n"; }
}
class Val { function __destruct() { echo "Val gone\n"; } }

// The name converts through __toString, which drops the object, the name and the value from their variables.
$o = new Box(1); $k = new Name('p'); $v = new Val();
$o->$k = $v;
echo "after convert\n";

// A computed receiver or name stays owned while the value's side effects run.
$o1 = new Box(2); $o2 = new Box(3); $c = true;
function dropO1() { global $o1; $o1 = null; echo "dropO1\n"; return 'w'; }
// (A conditional receiver is a compile error in PHP: "Cannot use temporary expression in write context".)
function pick() { global $o1, $o2, $c; return $c ? $o1 : $o2; }
function receiver() { $y = 'y'; pick()->$y = dropO1(); echo "after receiver\n"; }
receiver();
$arr = [new Box(4)];
function getIdx() { return 0; }
function clearArr() { global $arr; $arr = [new Box(6)]; echo "clearArr\n"; return 'z'; }
// PHP computes the key first and fetches the element after the value ran: the write lands on the replacement.
function element() {
    global $arr; $p = 'q';
    $arr[getIdx()]->$p = clearArr();
    echo "after element: ", $arr[0]->id, " ", $arr[0]->q, "\n";
}
element();
$s1 = new Name('n1'); $s2 = new Name('n2');
function dropS1() { global $s1; $s1 = null; echo "dropS1\n"; return 'u'; }
function name() { global $s1, $s2, $c; $box = new Box(5); $box->{$c ? $s1 : $s2} = dropS1(); var_dump(array_keys(get_object_vars($box))); }
name();

// __toString throws, then the operand's destructor (its last reference was the builtin's) throws too.
class Bad {
    public $tag;
    function __construct($tag) { $this->tag = $tag; }
    function __toString(): string { global $victim; $victim = null; throw new Exception("toString {$this->tag}"); }
    function __destruct() { throw new Exception("destruct {$this->tag}"); }
}
function chained($label, callable $call) {
    try { $call(); }
    catch (Exception $e) { echo $label, ": ", $e->getMessage(), " <- ", $e->getPrevious() ? $e->getPrevious()->getMessage() : "none", "\n"; }
}
chained('strlen', function () { global $victim; $victim = new Bad(1); return strlen($victim); });
chained('str_replace', function () { global $victim; $victim = new Bad(2); return str_replace($victim, 'a', 'b'); });
chained('trim', function () { global $victim; $victim = new Bad(3); return trim($victim); });
// Free the rest in a fixed order (shutdown order over reused object handles is not what this file checks).
$arr = []; $o2 = null; $s2 = null;
echo "end\n";
