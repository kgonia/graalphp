<?php
class D { function __destruct() { throw new Exception('drop'); } }
function flight() { try { return new D(); } finally { throw new Exception('finally'); } }
try { flight(); } catch (Exception $e) { $p = $e->getPrevious(); echo $e->getMessage(), $p->getMessage(), "\n"; }
class C { static function f($x) { throw new Exception(); } }
function trace() { $f = static function () { $cb = ['C','f']; $cb(8); }; $f(); }
try { trace(); } catch (Exception $e) {
    $t = $e->getTrace(); echo is_array($t), count($t), $t[0]['type'], is_array($t[0]['args']), $t[1]['function'];
}
