<?php
foreach (["12abc", "7.8z", new stdClass(), [1], "bad"] as $x) {
    try { echo $x % 5, "\n"; } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
}
try { echo 1 % 0; } catch (DivisionByZeroError $e) { echo $e->getMessage(), "\n"; }
echo (int) INF, "\n";
echo (int) -INF, "\n";
echo (int) 1e19, "\n";
echo intval(INF), "\n";
echo intval('ff',16), "/", intval(' -0b101z',0), "/", intval('077',0), "/", intval('0o77',8), "\n";
echo intval('FFFFFFFFFFFFFFFF',16), "/", intval('-FFFFFFFFFFFFFFFF',16), "/", intval('123',1), "\n";
