<?php
// The count audit's fixture for temporaries sent as arguments and live across exceptions (wiki/destructors.md):
//   JAVA_OPTS="-Dphp.rc.destructors=true -Dphp.rc.audit=true -Dphp.rc.audit.every=1" ./bin-php.sh tests/rcaudit/moves.php
// The expected output is PHP 8.5's (tests/rcaudit/moves.expected).
class D {
    function __construct(public $n) {}
    function __destruct() { echo "~{$this->n} "; }
    function m($x) { $l = new D("l"); return $this; }
    function boom() { throw new Exception("boom"); }
}
function one($x) { $l = new D("l1"); echo "in "; }
function none() { $l = new D("l0"); echo "in "; }
function rest(...$xs) { $l = new D("lr"); echo "in "; }
function two($x, $y) { echo "in "; }
function typed(int $i, $x) { echo "in "; }
function thrower() { throw new Exception("t"); }
function make($n) { return new D($n); }
function ret() { $l = new D("lret"); return (new D("t"))->m(1); }

echo "1: "; one(new D("arg")); echo "| ";
echo "\n2: "; one(make("call")); echo "| ";
echo "\n3: "; none(new D("extra")); echo "| ";
echo "\n4: "; rest(new D("v1"), new D("v2")); echo "| ";
echo "\n5: "; echo spl_object_id(new D("builtin")) > 0 ? "id " : "", "after "; echo "| ";
echo "\n6: "; try { two(new D("e"), thrower()); } catch (Exception $e) { echo "catch "; } echo "| ";
echo "\n7: "; try { typed("x", new D("te")); } catch (TypeError $e) { echo "catch "; } echo "| ";
echo "\n8: "; try { (new D("tmp"))->boom(); } catch (Exception $e) { echo "catch "; } echo "| ";
echo "\n9: "; $r = ret(); echo "| "; unset($r); echo "| ";
echo "\n10: "; (new D("a"))->m(new D("b"))->m(new D("c")); echo "| ";
echo "\n11: "; function inner() { try { two(new D("e2"), thrower()); } finally { echo "finally "; } }
try { inner(); } catch (Exception $e) { echo "catch "; } echo "| ";
echo "\nend\n";
echo "\n12: "; function typed2(int $i) { echo "in "; }
$d = new D("var"); try { typed2($d); } catch (TypeError $e) { echo "catch "; } echo "after "; $e = null; echo "| ";
echo "\n13: "; try { typed2(new D("moved")); } catch (TypeError $e) { echo "catch "; } echo "after "; $e = null; echo "| ";
echo "\n14: "; try { typed(1, new D("ok")); } catch (TypeError $e) { echo "catch "; } echo "| ";
echo "\nend2\n";
