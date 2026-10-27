<?php
require __DIR__ . '/callable-lib.php';
$call = magic_caller();
echo $call(), "\n";
