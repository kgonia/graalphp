<?php
function k($a, $b = 1) { return $a; }
function z() { return 1; }
class C { public $p = 1;
function prop($v, $x) {
    $v = k($this->p);
    $v = k($this->p);
    $v = k($this->p);
    $v = k($this->p);
    $v = k($this->p);
    $v = k($this->p);
    $v = k($this->p);
    $v = k($this->p);
    $v = k($this->p);
    $v = k($this->p);
    $v = k($this->p);
    $v = k($this->p);
    $v = k($this->p);
    $v = k($this->p);
    $v = k($this->p);
    $v = k($this->p);
    $v = k($this->p);
    $v = k($this->p);
    $v = k($this->p);
    $v = k($this->p);
    return $v;
}
function elem($v, $x) {
    $v = k($x['k']);
    $v = k($x['k']);
    $v = k($x['k']);
    $v = k($x['k']);
    $v = k($x['k']);
    $v = k($x['k']);
    $v = k($x['k']);
    $v = k($x['k']);
    $v = k($x['k']);
    $v = k($x['k']);
    $v = k($x['k']);
    $v = k($x['k']);
    $v = k($x['k']);
    $v = k($x['k']);
    $v = k($x['k']);
    $v = k($x['k']);
    $v = k($x['k']);
    $v = k($x['k']);
    $v = k($x['k']);
    $v = k($x['k']);
    return $v;
}
function callarg($v, $x) {
    $v = k(z());
    $v = k(z());
    $v = k(z());
    $v = k(z());
    $v = k(z());
    $v = k(z());
    $v = k(z());
    $v = k(z());
    $v = k(z());
    $v = k(z());
    $v = k(z());
    $v = k(z());
    $v = k(z());
    $v = k(z());
    $v = k(z());
    $v = k(z());
    $v = k(z());
    $v = k(z());
    $v = k(z());
    $v = k(z());
    return $v;
}
function meth($v, $x) {
    $v = $this->m($x);
    $v = $this->m($x);
    $v = $this->m($x);
    $v = $this->m($x);
    $v = $this->m($x);
    $v = $this->m($x);
    $v = $this->m($x);
    $v = $this->m($x);
    $v = $this->m($x);
    $v = $this->m($x);
    $v = $this->m($x);
    $v = $this->m($x);
    $v = $this->m($x);
    $v = $this->m($x);
    $v = $this->m($x);
    $v = $this->m($x);
    $v = $this->m($x);
    $v = $this->m($x);
    $v = $this->m($x);
    $v = $this->m($x);
    return $v;
}
function m($a) { return $a; }
}
$c = new C(); $a = ['k' => 2];
for ($i = 0; $i < 5000; $i++) { $c->prop(1, $a); $c->elem(1, $a); $c->callarg(1, $a); $c->meth(1, $a); }
echo "ok
";
