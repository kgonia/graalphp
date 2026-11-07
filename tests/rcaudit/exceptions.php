<?php
// Exception objects' lifetimes (thrown, caught, rethrown, dropped by a catch without a variable, kept by a trace).
// Expected: PHP 8.5's output (tests/rcaudit/exceptions.expected).
class E extends Exception {
    function __construct(public $n) { parent::__construct($n); }
    function __destruct() { echo "~{$this->n} "; }
}
function thrower($n) { throw new E($n); }
function rethrow($n) { try { thrower($n); } catch (E $e) { echo "re "; throw $e; } }
echo "1: "; try { throw new E("a"); } catch (E $e) { echo "c "; } echo "after "; $e = null; echo "| ";
echo "\n2: "; try { throw new E("b"); } catch (E) { echo "c "; } echo "after | ";
echo "\n3: "; try { thrower("c"); } catch (E $e) { echo "c "; } $e = new E("d"); echo "after "; $e = null; echo "| ";
echo "\n4: "; try { rethrow("r"); } catch (E $x) { echo "c "; } unset($x); echo "after | ";
echo "\n5: "; try { try { thrower("f"); } finally { echo "fin "; } } catch (E) { echo "c "; } echo "after | ";
echo "\n6: "; try { throw new E("p", ); } catch (E $e) { $p = new E("q"); echo "c "; } $e = null; $p = null; echo "| ";
echo "\n7: "; function g() { try { thrower("g"); } catch (E $e) { return "ret "; } } echo g(); echo "after | ";
echo "\n8: "; try { thrower("t"); } catch (Throwable $t) { echo get_class($t), " "; } $t = null; echo "| ";
echo "\nend\n";
