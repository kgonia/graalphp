<?php
// One case per php-src opcode that takes a TMP or VAR operand (bench/destructors/vm_operands.py lists them from
// Zend/zend_vm_def.h): a fresh object with a destructor as that operand, markers before and after, so where the
// destructor runs against the opcode shows. Expected: PHP 8.5's output (opcodes.expected); which opcodes the cases
// reach: bench/destructors/opcode_coverage.sh (php-src's own opcode dump of this file).
class D {
    const C = 'c';
    public static $sp = 'sp';
    public $n;
    public $p;
    public $arr = [];
    function __construct($n) { $this->n = $n; }
    function __destruct() { echo "~{$this->n} "; }
    function m() { return $this->n; }
    function self() { return $this; }
    static function sm() { return 'sm'; }
    function __toString(): string { return "s{$this->n}"; }
    function __invoke() { return 'inv'; }
}
class E extends Exception {
    function __construct(public $n) { parent::__construct($n); }
    function __destruct() { echo "~{$this->n} "; }
}
function make($n) { return new D($n); }
function arr($n) { return [new D($n)]; }
function one($x) { echo "in "; }
function ret($n): D { return new D($n); }
function gen($n) { yield new D($n); echo "g "; }
function gen2($n) { yield from [new D($n)]; }
function args() { return count(func_get_args()); }
function cs($n) { switch (make($n)->m()) { case $n: return 'case'; } return 'none'; }

$i = 0;
$t = function (string $label, callable $case) use (&$i) { echo ++$i, " $label: "; $case(); echo "|\n"; };
$t('FETCH_OBJ_R', function () { echo (new D('a'))->n, " after "; });
$t('FETCH_DIM_R', function () { echo arr('b')[0]->n, " after "; });
$t('ISSET_ISEMPTY_PROP_OBJ', function () { echo isset((new D('c'))->n) ? 'set ' : 'unset ', "after "; });
$t('ISSET_ISEMPTY_DIM_OBJ', function () { echo isset(arr('d')[0]) ? 'set ' : 'unset ', "after "; });
$t('INIT_METHOD_CALL', function () { echo make('e')->m(), " after "; });
$t('INIT_DYNAMIC_CALL', function () { echo (new D('f'))(), " after "; });
$t('INIT_STATIC_METHOD_CALL', function () { echo (new D('g'))::sm(), " after "; });
$t('SEND_VAL', function () { one(new D('h')); echo "after "; });
$t('SEND_VAR_NO_REF', function () { one(make('i')); echo "after "; });
$t('SEND_USER', function () { call_user_func('one', new D('j')); echo "after "; });
$t('SEND_UNPACK', function () { one(...[new D('k')]); echo "after "; });
$t('SEND_ARRAY', function () { call_user_func_array('one', [new D('l')]); echo "after "; });
$t('ASSIGN (TMP)', function () { $x = new D('m'); echo "set "; $x = null; echo "after "; });
$t('ASSIGN_OBJ (OP_DATA)', function () { $o = new D('n0'); $o->p = new D('n'); echo "set "; $o->p = null; echo "after "; });
$t('ASSIGN_DIM (OP_DATA)', function () { $a = []; $a[] = new D('o'); echo "set "; $a = []; echo "after "; });
$t('ASSIGN_OP (.=)', function () { $s = 'x'; $s .= make('p'); echo "$s after "; });
$t('QM_ASSIGN', function () { $c = true; $x = $c ? new D('q') : null; echo "set "; $x = null; echo "after "; });
$t('JMP_SET (?:)', function () { echo (make('r') ?: 'none')->n, " after "; });
$t('COALESCE', function () { echo (new D('s'))->p ?? 'null', " after "; });
$t('JMPZ', function () { if (new D('t')) { echo "body "; } echo "after "; });
$t('JMPNZ_EX (||)', function () { echo (new D('u') || false) ? 'y ' : 'n ', "after "; });
$t('JMPZ_EX (&&)', function () { echo (new D('v') && true) ? 'y ' : 'n ', "after "; });
$t('BOOL', function () { echo (bool) new D('w') ? 'y ' : 'n ', "after "; });
$t('BOOL_NOT', function () { echo !new D('x') ? 'y ' : 'n ', "after "; });
$t('CAST (array)', function () { echo count((array) new D('y')), " after "; });
$t('CAST (string)', function () { echo (string) new D('z'), " after "; });
$t('TYPE_CHECK', function () { echo is_object(new D('aa')) ? 'y ' : 'n ', "after "; });
$t('INSTANCEOF', function () { echo new D('ab') instanceof D ? 'y ' : 'n ', "after "; });
$t('IS_IDENTICAL', function () { echo make('ac') === null ? 'y ' : 'n ', "after "; });
$t('IS_EQUAL', function () { echo make('ad') == make('ad2') ? 'y ' : 'n ', "after "; });
$t('CONCAT', function () { echo make('ae') . 'x', " after "; });
$t('ROPE', function () { $o = 'o'; echo "{$o}" . "<" . make('af') . ">{$o}", " after "; });
$t('ECHO', function () { echo make('ag'); echo " after "; });
$t('PRINT', function () { print make('ah'); echo " after "; });
$t('COUNT', function () { echo count(arr('ai')), " after "; });
$t('GET_CLASS', function () { echo get_class(new D('aj')), " after "; });
$t('GET_TYPE', function () { echo gettype(new D('ak')), " after "; });
$t('STRLEN', function () { echo strlen(new D('al')), " after "; });
$t('INIT_ARRAY (discarded)', function () { [new D('am')]; echo "after "; });
$t('ADD_ARRAY_UNPACK', function () { echo count([...[new D('an')]]), " after "; });
$t('FE_RESET_R / FE_FREE (array)', function () { foreach ([new D('ao')] as $v) { echo "body "; } echo "loop "; unset($v); echo "after "; });
$t('FE_RESET_R (object)', function () { foreach (new D('ap') as $k => $v) { echo "$k "; } echo "after "; });
$t('FE_RESET_RW', function () { foreach (arr('aq') as &$v) { echo "body "; } unset($v); echo "after "; });
$t('RETURN', function () { echo ret('ar')->n, " after "; });
$t('YIELD', function () { foreach (gen('as') as $v) { echo "body "; } echo "loop "; unset($v); echo "after "; });
$t('YIELD_FROM', function () { foreach (gen2('at') as $v) { echo "body "; } echo "loop "; unset($v); echo "after "; });
$t('THROW', function () { try { throw new E('au'); } catch (E) { echo "catch "; } echo "after "; });
$t('CLONE', function () { echo (clone make('av'))->n, " after "; });
$t('FREE', function () { make('aw'); echo "after "; });
$t('MATCH', function () { echo match (make('ax')->m()) { 'ax' => 'hit', default => 'miss' }, " after "; });
$t('MATCH (subject object)', function () { $o = new D('ay0'); echo match (make('ay')) { $o => 'same', default => 'other' }, " after "; });
$t('SWITCH / CASE', function () { echo cs('az'), " after "; });
$t('PRE_INC_OBJ', function () { $o = new D('ba0'); $o->p = 1; echo ++$o->self()->p, " after "; });
$t('FETCH_LIST_R', function () { [$x, $y] = [new D('bb'), 1]; echo "set "; $x = null; echo "after "; });
$t('CALLABLE_CONVERT', function () { $f = make('bc')->m(...); echo "made "; echo $f(), " "; $f = null; echo "after "; });
$t('JMP_NULL (?->)', function () { echo make('bd')?->m(), " after "; });
$t('BEGIN_SILENCE', function () { echo @make('be')->n, " after "; });
$t('VERIFY_RETURN_TYPE', function () { ret('bf'); echo "after "; });
$t('FUNC_GET_ARGS', function () { echo args(new D('bg')), " after "; });
$t('FETCH_CLASS_NAME', function () { echo (new D('bh'))::class, " after "; });
$t('FETCH_CLASS_CONSTANT', function () { echo (new D('bi'))::C, " after "; });
$t('FETCH_STATIC_PROP_R', function () { echo (new D('bj'))::$sp, " after "; });
$t('FETCH_OBJ_W (nested)', function () { make('bl')->arr[] = 1; echo "after "; });
$t('PIPE', function () { echo make('bm') |> (fn($x) => $x->n), " after "; });
$t('NEW args', function () { $o = new D(new D('bn')); echo "made "; $o = null; echo "after "; });
$t('ARRAY_KEY_EXISTS', function () { echo array_key_exists(0, arr('bo')) ? 'y ' : 'n ', "after "; });
$t('IN_ARRAY', function () { echo in_array(1, [make('bp'), 1], true) ? 'y ' : 'n ', "after "; });
echo "end\n";
