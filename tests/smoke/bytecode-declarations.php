<?php
// Declarations inside function bodies (on bytecode): nested functions and classes declared when their statement
// runs, anonymous classes, promoted constructor parameters, and `xor`.
interface Shape { function area(): float; }
abstract class Base { function describe(): string { return static::class . ' ' . $this->area(); } }

function declare_helpers(bool $twice) {
    if (!function_exists('helper_inner')) {
        function helper_inner($x) { return "inner($x)"; }
    }
    echo helper_inner(1), "\n";
    if ($twice) {
        class DeclaredInFunction { public $v = 3; function get() { return $this->v * 2; } }
        echo (new DeclaredInFunction())->get(), "\n";
    }
}
declare_helpers(true);
declare_helpers(false);
echo helper_inner(2), "\n";

function make_shape(float $w, float $h) {
    return new class($w, $h) extends Base implements Shape {
        function __construct(private float $w, public readonly float $h) {}
        function area(): float { return $this->w * $this->h; }
    };
}
$a = make_shape(2, 3);
$b = make_shape(4, 5);
echo $a->area(), ' ', $b->area(), ' ', $a->h, "\n";
echo get_class($a) === get_class($b) ? "same class\n" : "different classes\n";
echo $a instanceof Shape ? "a Shape\n" : "not a Shape\n";
echo str_contains(get_class($a), "Base@anonymous") ? "named\n" : get_class($a) . "\n";
echo strlen(explode("\0", get_class($a))[0]), "\n";

class Point {
    public function __construct(public int $x = 0, protected int $y = 0, private array $tags = []) {
        $this->x += 1;
    }
    public function dump() { return "$this->x,$this->y," . implode('|', $this->tags); }
}
function points() {
    $p = new Point(1, 2, ['a', 'b']);
    $q = new Point();
    return $p->dump() . ' ' . $q->dump();
}
echo points(), "\n";

function xors($a, $b) {
    return [$a xor $b, (bool) ($a xor $b), $a xor 0];
}
var_dump(xors(1, 0), xors('', null), xors([1], 'x'));
