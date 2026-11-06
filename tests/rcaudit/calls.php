<?php
// The count audit's fixture for counts held by running calls (audit points, -Dphp.rc.audit.every=1):
//   JAVA_OPTS="-Dphp.rc.destructors=true -Dphp.rc.audit=true -Dphp.rc.audit.every=1" ./bin-php.sh tests/rcaudit/calls.php
// PHP 8.5: "~arg ~l ~l ~l ~tmp", then "~l ~l ~z1 ~gen ~cmp", then "~a ~a.kid ~b1 ~b1.kid ~spl ~b2 ~b2.kid ~z2", "end".
// Known findings:
// - 'arg' LATE: php-src moves a temporary argument into the callee (SEND_VAL), which frees it in its epilogue
//   before its own $l; here the caller's hidden local keeps it to the statement's end ("~l ~l ~arg").
// - the Generator EARLY: its count reaches -1 (released once more than counted).
class D {
    public $n;
    public $kids = [];
    function __construct($n) { $this->n = $n; }
    function __destruct() { echo "~{$this->n} "; }
    function m($x) { $l = new D("l"); return $this; }
}
function make($n) { $o = new D($n); $o->kids[] = new D("$n.kid"); return $o; }
function use_it() {
    $a = make('a');
    $b = [make('b1'), make('b2')];
    $a->m(new D('arg'))->m(1);
    (new D('tmp'))->m(2);
    $s = new SplObjectStorage; $s->attach(new D('spl'));
    foreach ($b as $x) { $x->m(3); }
    $g = (function () { $in = new D('gen'); yield 1; yield 2; })();
    foreach ($g as $v) { $z = new D("z$v"); }
    usort($b, function ($p, $q) { $t = new D('cmp'); return 0; });
    echo "\n";
}
use_it();
echo "\nend\n";
