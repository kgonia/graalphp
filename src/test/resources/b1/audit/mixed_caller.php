<?php
function relay($p) { return $p->items(); }
function mutate(&$v) { $v = "changed"; }
function show($first, $second) { echo $first->tag . "\n"; }
function runMixed($p) {
    $items = relay($p);
    echo "received\n";
    $items = 0;
    mutate($p->slot());
    echo $p->value . "\n";
    $p->install();
    show($p->slot(), mutate($p->slot()));
    relay($p);
    echo "done\n";
}
