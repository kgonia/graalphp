<?php
// Pass 1 review P0-2 and P0-4: direct builtins keep their operands alive and free sent temporaries in order.
class S {
    public $tag;
    function __construct($tag) { $this->tag = $tag; }
    function __toString(): string { global $victim; $victim = null; echo "toString {$this->tag}\n"; return "str" . $this->tag; }
    function __destruct() { echo "S{$this->tag} gone\n"; }
}
class D {
    public $n;
    function __construct($n) { $this->n = $n; }
    function __destruct() { echo "D{$this->n} gone\n"; }
}
function side() { echo "side\n"; return true; }

// array_unshift($a, $a) passes the array by value: the array separates instead of containing itself.
function selfUnshift() { $a = [1, 2]; array_unshift($a, $a); var_dump($a); }
selfUnshift();
// The same through a reference to the array.
function refUnshift() { $a = [7]; $r = &$a; array_unshift($a, $r); var_dump($a); }
refUnshift();

// __toString drops the caller's only variable holding the operand while the builtin uses it.
function lengthOfVictim() { global $victim; $victim = new S(1); echo strlen($victim), "\n"; echo "after strlen\n"; }
lengthOfVictim();
function replaceWithVictim() { global $victim; $victim = new S(2); echo str_replace($victim, "X", "a str2 b"), "\n"; echo "after str_replace\n"; }
replaceWithVictim();
function trimVictim() { global $victim; $victim = new S(3); echo trim($victim), "|", "\n"; echo "after trim\n"; }
trimVictim();

// is_*() in a condition frees its argument before the condition goes on.
function typeTestAnd() { if (is_object(new D(1)) && side()) echo "both\n"; }
typeTestAnd();
function typeTestChoose() { $r = is_object(new D(2)) ? side() : 0; var_dump($r); }
typeTestChoose();
function typeTestNot() { if (!is_array(new D(3))) side(); }
typeTestNot();
echo "end\n";
