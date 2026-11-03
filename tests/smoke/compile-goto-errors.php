<?php
// Issue 006: the first error php-src's compile reaches: a function's own errors at its end (pass_two), before the
// code after it; here the goto to an undefined label in f(), not the label defined twice below it.
echo "not printed\n";
function f() {
    goto nowhere;
}
function g() {
    twice:
    twice:
}
