<?php
// Issue 064: var_dump of the same object from inside its __debugInfo prints *RECURSION*.
// The marks are on the values, per context: var_dump and print_r share one on objects, var_export and
// json_encode have their own; arrays have one every walk shares, frozen (literal) arrays none.
class A { function __debugInfo() { var_dump($this); return []; } }
$keep[] = $o = new A; var_dump($o);
class B { function __debugInfo() { print_r($this); echo "\n"; return ['b'=>1]; } }
$keep[] = $o = new B; var_dump($o);
class C implements JsonSerializable { function jsonSerialize(): mixed { var_dump($this); print_r($this); echo "\n"; return 1; } }
echo json_encode(new C), "\n";
class D { public $x = 1; function __debugInfo() { var_dump(json_encode($this), json_last_error_msg()); return []; } }
$keep[] = $o = new D; var_dump($o);
$GLOBALS['arr'] = [new class { function __debugInfo() { var_dump($GLOBALS['arr']); return []; } }];
var_dump($GLOBALS['arr']);
const LIT = [1, 2];
class E { function __debugInfo() { var_dump(LIT); return []; } }
$keep[] = $o = new E; var_dump([LIT, $o]);
class F { public $p; function __debugInfo() { var_export($this); echo "\n"; return []; } }
$keep[] = $o = new F; var_dump($o);
$GLOBALS['arr2'] = [new class implements JsonSerializable { function jsonSerialize(): mixed { var_dump($GLOBALS['arr2']); return 1; } }];
echo json_encode($GLOBALS['arr2']), "\n";
$GLOBALS['arr3'] = [new class { function __debugInfo() { var_dump(json_encode($GLOBALS['arr3']), json_last_error_msg()); return []; } }];
var_dump($GLOBALS['arr3']);
$inner = [
 'var_dump' => function ($o) { ob_start(); var_dump($o); return str_contains(ob_get_clean(), 'RECURSION') ? 'R' : '-'; },
 'print_r' => function ($o) { return str_contains(print_r($o, true), 'RECURSION') ? 'R' : '-'; },
 'export' => function ($o) { $r = @var_export($o, true); return str_contains($r, 'NULL') && !str_contains($r, "'p'") ? 'R' : '-'; },
 'json' => function ($o) { return json_encode($o) === false ? 'R' : '-'; },
];
class P { public $p = 1; public static $f; public static $res; function __debugInfo() { P::$res = (P::$f)($this); return []; } }
class PM { public $p = 1; function __get($n) { return 1; } function __debugInfo() { P::$res = (P::$f)($this); return []; } }
class J implements JsonSerializable { public $p = 1; function jsonSerialize(): mixed { P::$res = (P::$f)($this); return 1; } }
class JM implements JsonSerializable { public $p = 1; function __get($n) { return 1; } function jsonSerialize(): mixed { P::$res = (P::$f)($this); return 1; } }
foreach ($inner as $iname => $fn) {
  P::$f = $fn;
  $row = [];
  foreach (['P', 'PM'] as $c) { ob_start(); var_dump($keep[] = new $c); ob_end_clean(); $row[] = "vd/$c=" . P::$res; }
  if ($iname !== "print_r") foreach (["P", "PM"] as $c) { print_r($keep[] = new $c, true); $row[] = "pr/$c=" . P::$res; }
  foreach (['J', 'JM'] as $c) { json_encode($keep[] = new $c); $row[] = "js/$c=" . P::$res; }
  foreach (['J', 'JM'] as $c) { json_encode([$keep[] = new $c]); $row[] = "js[]/$c=" . P::$res; }
  echo str_pad($iname, 9), implode(' ', $row), "\n";
}
