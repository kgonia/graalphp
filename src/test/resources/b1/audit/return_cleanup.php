<?php
class First { function __destruct() { echo "first\n"; throw new Exception("first"); } }
class Second { function __destruct() { echo "second\n"; throw new Exception("second"); } }
class Extra { function __destruct() { echo "extra\n"; } }
class Result { function __destruct() { echo "return discarded\n"; } }
function f() {
    $a = new First();
    $b = new Second();
    return new Result();
}
try { $r = f(new Extra()); } catch (Exception $e) {
    $p = $e->getPrevious();
    echo $e->getMessage() . "/" . $p->getMessage() . "\n";
}
try { f(new Extra()); } catch (Exception $e) { echo "unused:" . $e->getMessage() . "\n"; }
