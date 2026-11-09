<?php
// The count audit's fixture (wiki/destructors.md, issue 036): known refcount gaps, each one a finding of
//   JAVA_OPTS="-Dphp.rc.destructors=true -Dphp.rc.audit=true -Dphp.rc.audit.every=1" ./bin-php.sh tests/rcaudit/known-gaps.php
// PHP 8.5 prints "~cast ~templit ~ret" before "end", then "~keep ~cyc" at shutdown.
// - 'cast', 'templit': LATE (gap 2: an array with no holders keeps its elements' counts);
// - 'ret': LATE (a returned array nobody stores);
// - 'keep': EXTRA (gap 1: a closure never releases its captures);
// - H: EARLY, then FREED while held (gap 1: a closure's $this isn't counted).
class D { public $n; function __construct($n) { $this->n = $n; } function __destruct() { echo "~{$this->n} "; } }
$keep = new D('keep');
$k = (object)['d' => new D('cast')]; unset($k);
count([new D('templit')]);
function mk() { return [new D('ret')]; }
mk();
$f = function () use ($keep) { return 1; }; unset($f);
class H { function m() { return function () { return $this; }; } }
$h = new H; $g = $h->m(); unset($h);
$cyc = new D('cyc'); $cyc->self = $cyc; unset($cyc);
echo "\nend\n";
