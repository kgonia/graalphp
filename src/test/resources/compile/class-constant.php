<?php
// A class constant read where the value can't stay at the site: `static::NAME` looks the constant up per call
// (ClassConstant.perCall), as every read does in a server's shared engine. The lookup's first-read part translates
// and runs the initializer: compiled code must not follow it in. It did (2026-11-09), and every function with such
// a read failed to compile for good ("Too deep inlining"): WordPress's whole WP_HTML_Tag_Processor and
// WP_Theme_JSON ran interpreted.
// IR budget: Shape::area 700
class Shape {
    const SIDES = 4;
    const SCALE = self::SIDES * 2 + 1;
    const NAMES = ['a' => 1, 'b' => self::SCALE];
    public function area($n) {
        return $n * static::SIDES + static::SCALE + static::NAMES['b'];
    }
}
class Square extends Shape {
    const SIDES = 5;
}
$shapes = [new Shape(), new Square()];
$sum = 0;
for ($i = 0; $i < 3000; $i++) {
    $sum += $shapes[$i & 1]->area($i);
}
echo $sum, "\n";
