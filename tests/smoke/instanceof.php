<?php
// instanceof and self::/parent:: calls: classes, parents, interfaces (also inherited ones), traits (never an
// ancestor), closures, and sites that see more classes than their inline cache holds.
interface Shape { function area(); }
interface Named extends Shape {}
trait Greets { function hello() { return 'hi from ' . static::class; } }
abstract class Base implements Named {
    use Greets;
    function area() { return 0; }
    static function make() { return new static(); }
    function describe() { return 'base'; }
}
class Square extends Base {
    function area() { return 4; }
    function describe() { return 'square<' . parent::describe() . '>'; }
    function viaSelf() { return self::helper(); }
    function helper() { return 'helper of ' . get_class($this); }
}
final class Cube extends Square {
    function describe() { return 'cube<' . parent::describe() . '>'; }
}
class Plain {
    function callStatic() { return Square2::helperStatic() . Square2::who(); }
}
class Other implements Countable { function count(): int { return 1; } }
class C1 {} class C2 extends C1 {} class C3 extends C2 {} class C4 extends C3 {} class C5 extends C4 {}
class C6 extends C5 {}

$values = [new Square(), new Cube(), Square::make(), new Plain(), new Other(), function () {}, 'Square', 42,
    null, [1], new C1(), new C2(), new C3(), new C4(), new C5(), new C6()];
$names = ['Shape', 'Named', 'Base', 'Square', 'Cube', 'Greets', 'Countable', 'Closure', 'C1', 'C3', 'C6'];
for ($round = 0; $round < 3; $round++) {
    foreach ($values as $i => $v) {
        $row = [];
        foreach ($names as $name) {
            $row[] = $v instanceof $name ? 1 : 0;
        }
        $fixed = ($v instanceof Shape ? 'S' : '-') . ($v instanceof Named ? 'N' : '-')
            . ($v instanceof Base ? 'B' : '-') . ($v instanceof Square ? 'Q' : '-') . ($v instanceof Cube ? 'C' : '-')
            . ($v instanceof Greets ? 'G' : '-') . ($v instanceof Countable ? 'O' : '-')
            . ($v instanceof Closure ? 'L' : '-') . ($v instanceof C1 ? '1' : '-') . ($v instanceof C3 ? '3' : '-')
            . ($v instanceof Missing ? 'M' : '-');
        if ($round == 2) {
            echo $i, ' ', implode('', $row), ' ', $fixed, "\n";
        }
    }
}
$cube = new Cube();
echo $cube->describe(), ' ', $cube->viaSelf(), ' ', $cube->hello(), ' ', $cube->area(), "\n";
echo (new Square())->describe(), ' ', (new Square())->viaSelf(), "\n";
class Square2 extends Square {
    static function helperStatic() { return 'static'; }
    static function who() { return ' ' . static::class; }
}
function check(object $o) {
    return $o instanceof Square ? get_class($o) . ' is a Square' : get_class($o) . ' is not';
}
foreach ([new Square(), new Cube(), new Square2(), new C1(), new Other(), new C6()] as $o) {
    echo check($o), "\n";
}
for ($i = 0; $i < 3; $i++) {
    echo (new Plain())->callStatic(), "\n";
}
