<?php
// Where a temporary's destructor runs against what the same expression does next (opcodes.php's cases only show it
// against the markers around the expression): a call that echoes ("f") right after the opcode that frees the
// temporary. Expected: PHP 8.5 without opcache (sequence.expected).
class D {
    const C = 'c';
    public static $sp = 'sp';
    public $n;
    public $p;
    public $obj;
    public $arr = [];
    function __construct($n, $obj = null) { $this->n = $n; $this->obj = $obj; }
    function __destruct() { echo "~{$this->n} "; }
    function m() { echo "m{$this->n} "; return $this->n; }
    function __toString(): string { return "s{$this->n}"; }
    function __get($name) { echo "get{$this->n} "; return null; }
    function __isset($name) { echo "isset{$this->n} "; return true; }
    static function sm() { echo "sm "; return 'sm'; }
}
function make($n, $obj = null) { return new D($n, $obj); }
function arr($n) { return [new D($n)]; }
function f() { echo "f "; return 'f'; }
function t() { echo "t "; return true; }

$i = 0;
$t = function (string $label, callable $case) use (&$i) { echo ++$i, " $label: "; $case(); echo "|\n"; };
$t('&& then call', function () { echo (make('a') && t()) ? 'y ' : 'n ', "after "; });
$t('|| then call', function () { echo (!make('b') || t()) ? 'y ' : 'n ', "after "; });
$t('concat then call', function () { echo 'x' . make('c') . f(), " after "; });
$t('concat chain', function () { echo 'x' . 'y' . make('d') . f() . f(), " after "; });
$t('first two of chain', function () { echo make('e') . f() . f(), " after "; });
$t('cast in concat', function () { echo (string) make('g') . f(), " after "; });
$t('static call echoes', function () { echo make('i')::sm(), " after "; });
$t('instanceof class expr', function () { echo (f() instanceof (make('j'))) ? 'y ' : 'n ', "after "; });
$t('fetch then method', function () { echo make('k', new D('k2'))->obj->m(), " after "; });
$t('fetch then fetch', function () { echo make('l', new D('l2'))->obj->n, " after "; });
$t('fetch then call arg', function () { echo strlen(make('o', new D('o2'))->obj->n . f()), " after "; });
$t('match arm calls', function () { echo match (make('q')) { default => f() }, " after "; });
$t('nullsafe then call', function () { echo make('r')?->m() . f(), " after "; });
$t('nullsafe fetch', function () { echo make('s', new D('s2'))?->obj->m(), " after "; });
$t('coalesce then call', function () { echo (make('u')->p ?? f()), " after "; });
$t('coalesce magic', function () { echo (make('v')->zz ?? f()), " after "; });
$t('isset nested magic', function () { echo isset(make('w', new D('w2'))->obj->zz) ? 'y ' : 'n ', "after "; });
$t('builtin array arg then call', function () { echo (array_key_exists(0, arr('x')) ? f() : 'n'), " after "; });
$t('builtin array arg nested', function () { echo strlen(implode(',', array_keys(arr('y'))) . f()), " after "; });
$t('array cast foreach', function () { foreach ((array) arr('z') as $v) { echo "body "; } echo "loop "; $v = null; echo "after "; });
$t('array cast of object', function () { echo count((array) make('aa')) . f(), " after "; });
$t('class const then call', function () { echo make('ab')::C . f(), " after "; });
$t('static prop then call', function () { echo make('ac')::$sp . f(), " after "; });
$t('class name then call', function () { echo make('ad')::class . f(), " after "; });
$t('ternary cond then call', function () { echo make('ae') ? f() : 'n', " after "; });
$t('if cond then body', function () { if (make('af')) { f(); } echo "after "; });
$t('while cond', function () { $k = 0; while (make("ag$k") && $k++ < 1) { f(); } echo "after "; });
$t('xor', function () { echo (make('ah') xor t()) ? 'y ' : 'n ', "after "; });
$t('not then call', function () { echo !make('ai') . f(), " after "; });
$t('binary op then call', function () { echo (make('aj') === 1) . f(), " after "; });
$t('method result then call', function () { echo make('ak')->m() . f(), " after "; });
$t('array_values result', function () { $x = array_values(arr('al')); echo "set "; $x = null; echo "after "; });
$t('return of temp prop', function () { $g = fn() => make('am', new D('am2'))->obj; $o = $g(); echo "got "; $o = null; echo "after "; });
echo "end\n";
