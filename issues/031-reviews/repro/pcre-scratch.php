<?php
$a = new Fiber(fn() => preg_replace_callback('/a/', function ($m) { Fiber::suspend(); return 'x'; }, 'aa'));
$b = new Fiber(fn() => preg_replace_callback('/b/', function ($m) { Fiber::suspend(); return 'z'; }, 'bb'));
$a->start();
$b->start();
$a->resume(); $a->resume();
var_dump($a->getReturn());
var_dump(preg_replace_callback('/c/', fn($m) => 'y', 'cc'));
$b->resume(); $b->resume();
var_dump($b->getReturn());
