<?php
$fibers = [];
for ($i = 0; $i < 50; $i++) {
    $f = new Fiber(function () { for ($k = 0; $k < 3; $k++) { Fiber::suspend($k); } return 1; });
    $f->start(); $fibers[] = $f;
}
foreach ($fibers as $f) { while (!$f->isTerminated()) { $f->resume(); } }
echo "done 50\n";
