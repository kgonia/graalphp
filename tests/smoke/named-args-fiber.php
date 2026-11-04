<?php
// Fiber::start() passes its extra named arguments on to the fiber's function (Z_PARAM_VARIADIC_WITH_NAMED). Truffle
// PHP has no Fiber class yet (issue 031): this test waits for it.

function main() {
    $fiber = new Fiber(function ($a = 'a', $b = 'b') {
        echo "fiber: $a $b\n";
        return Fiber::suspend("$a$b");
    });
    echo $fiber->start(b: 'B'), "\n";
}

main();
