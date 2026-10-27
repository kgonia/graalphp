<?php
require __DIR__ . '/callable-lib.php';
$call = Closure::bind(magic_caller(), new Magic(), Magic::class);
echo $call(), "\n";
