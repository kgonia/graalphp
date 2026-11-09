<?php
// The count audit's fixture for closures (gap 1 of issue 036): a Closure holds its captures, $this and statics,
// and gives them up when its last holder goes. Compare with PHP 8.5 (OPcache off), with the audit's points on:
//   JAVA_OPTS="-Dphp.rc.destructors=true -Dphp.rc.audit=true -Dphp.rc.audit.every=1" ./bin-php.sh tests/rcaudit/closures.php
class D { public $n; public $f; function __construct($n) { $this->n = $n; } function __destruct() { echo "~{$this->n} "; }
    function getClosure() { return function () { return $this->n; }; }
    function m() { return $this->n; } }
echo "use: "; $d = new D('use'); $f = function () use ($d) { return $d->n; }; unset($d); echo "| "; unset($f); echo "\n";
echo "use&: "; $d = new D('useref'); $f = function () use (&$d) { return $d; }; unset($f); echo "| "; unset($d); echo "\n";
echo "this: "; $d = new D('this'); $f = $d->getClosure(); unset($d); echo $f(), " | "; unset($f); echo "\n";
echo "arrow: "; $d = new D('arrow'); $f = fn() => $d->n; unset($d); echo $f(), " | "; unset($f); echo "\n";
echo "array: "; $a = [function () use (&$a) {}, 'x' => (function () { $o = new D('inarr'); return fn() => $o; })()]; echo "| "; unset($a); echo "\n";
echo "returned: "; function mk() { $o = new D('ret'); return function () use ($o) { return $o; }; } $f = mk(); echo "| "; unset($f); echo "\n";
echo "bind: "; $d = new D('bind'); $g = Closure::bind(function () { return $this->n; }, $d, D::class); unset($d); echo $g(), " | "; unset($g); echo "\n";
echo "fromCallable: "; $d = new D('fc'); $g = Closure::fromCallable([$d, 'm']); unset($d); echo $g(), " | "; unset($g); echo "\n";
echo "fcc: "; $d = new D('fcc'); $g = $d->m(...); unset($d); echo $g(), " | "; unset($g); echo "\n";
echo "static: "; $f = function () { static $s; $s ??= new D('static'); }; $f(); echo "| "; unset($f); echo "\n";
echo "arg: "; array_map(function ($x) { return $x; }, [new D('mapped')]); echo "| \n";
echo "temp: "; (function () { $o = new D('temp'); return fn() => $o; })(); echo "| \n";
echo "prop: "; $d = new D('prop'); $d->f = function () {}; unset($d); echo "| \n";
echo "cycle: "; $d = new D('cycle'); $d->f = function () use ($d) {}; unset($d); echo "| \n";
echo "autoload: "; $o = new D('autoload'); spl_autoload_register(function ($c) use ($o) {}); unset($o); echo "| \n";
echo "end\n";
