<?php
$all = [];
for ($i = 0; $i < 127; $i++) {
    $f = new Fiber(function () use ($i) {
        try {
            Fiber::suspend();
        } finally {
            usleep(2000); // let the previous fiber's thread end, so the prune finds it
            $g = new Fiber(function () { Fiber::suspend(); });
            $g->start();
            echo "fin $i\n";
        }
    });
    $f->start();
    $all[] = $f;
}
echo "end of script\n";
exit(1);
