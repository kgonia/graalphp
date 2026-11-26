<?php
declare(strict_types=1);
$take = function (int $x) { return $x; };
try { $take('5'); }
catch (TypeError $e) { echo $e->getMessage(), "\n"; }
