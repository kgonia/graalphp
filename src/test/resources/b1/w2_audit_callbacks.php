<?php
declare(strict_types=1);
function typed(int $x) { return $x; }
function nested(int $x) { return typed('7'); }
var_dump(array_map('typed', ['5']));
$result = array_map('typed', ['five' => '5']);
echo is_int($result['five']), "/", $result['five'], "\n";
try { array_map('nested', ['6']); }
catch (TypeError $e) { echo $e->getMessage(), "\n"; }
try { array_map('typed', ['bad']); }
catch (TypeError $e) { echo $e->getMessage(), "\n"; }
try { str_repeat('a', '3'); }
catch (TypeError $e) { echo $e->getMessage(), "\n"; }
try { intval('ff', '16'); }
catch (TypeError $e) { echo $e->getMessage(), "\n"; }
