<?php
// One write site ($o->v = ...) meeting typed properties of several classes (issue 090: the site keeps its first
// class's type and tests values inline): values taken as they are, int to float, the coercive conversions, the
// TypeErrors, a subclass that redeclares the property and an untyped class, in a loop long enough to compile the
// site. (A reference bound to a typed property: issue 092.)

class IntBox { public int $v = 0; }
class FloatBox { public float $v = 0.0; }
class StringBox { public ?string $v = null; }
class SubBox extends IntBox { public int $v = 1; }
class PlainBox { public $v; }
class ObjBox { public ?IntBox $v = null; }

function put(object $o, mixed $value): string {
    try {
        $o->v = $value;
        return get_class($o) . ' ' . var_export($o->v, true);
    } catch (TypeError $e) {
        return get_class($o) . ' ' . $e->getMessage();
    }
}

$values = [7, 2.0, '12', '1e2', ' 3', 'x', true, null, [1], new IntBox()];
$boxes = [new IntBox(), new FloatBox(), new StringBox(), new SubBox(), new PlainBox(), new ObjBox()];
$seen = [];
for ($round = 0; $round < 3000; $round++) {
    foreach ($boxes as $box) {
        foreach ($values as $value) {
            $line = put($box, $value);
            if ($round === 0 || $round === 2999) {
                $seen[$round][] = $line;
            }
        }
    }
}
echo implode("\n", $seen[0]), "\n";
echo $seen[0] === $seen[2999] ? "same at the end\n" : implode("\n", $seen[2999]) . "\n";

