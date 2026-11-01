<?php
// foreach over a plain object's properties: the names once came as a JDK list, whose iterator partial evaluation
// can't see through (performance warnings on List.iterator, Iterator.hasNext, Iterator.next).
class Point {
    public $x = 1;
    public $y = 2;
    protected $z = 3;
    public function sum(): int {
        $s = 0;
        foreach ($this as $name => $value) {
            $s += $value;
        }
        return $s;
    }
}
function visible(object $o): int {
    $s = 0;
    foreach ($o as $name => $value) {
        $s += strlen($name) + $value;
    }
    return $s;
}
$p = new Point();
$total = 0;
for ($i = 0; $i < 3000; $i++) {
    $total += visible($p) + $p->sum();
}
echo $total, "\n";
