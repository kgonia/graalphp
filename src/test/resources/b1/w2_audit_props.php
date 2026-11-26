<?php
class C {
    public int $p;
    public ?int $maybe;
    public $plain;
    function take() { $r = &$this->p; }
}
$o = new C();
var_dump($o);
try { $o->take(); } catch (Error $e) { echo $e->getMessage(), "\n"; }
foreach ($o as $k => $v) echo $k, "\n";
echo implode(',', array_keys(get_object_vars($o))), "\n";
$n = &$o->maybe;
echo $n === null, "\n";
$o->p = 3;
$r = &$o->p;
try { $r = 'bad'; } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
try { $o->p = 'bad'; } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
echo $r, "/", implode(',', array_keys(get_object_vars($o))), "\n";
