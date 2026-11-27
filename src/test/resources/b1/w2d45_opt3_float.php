<?php
// Pass 3 float CVs: a local written only with float arithmetic lives in a JVM double. PHP's float semantics
// must hold: NaN and signed zero, infinities, int operands, division by zero, mixed operands that go through
// the runtime (numeric strings, null, bool) and the errors and warnings they raise.
function series($n) {
    $sum = 0.0;
    $sign = 1.0;
    for ($i = 0; $i < $n; $i++) {
        $sum += $sign / (2 * $i + 1);
        $sign = -$sign;
    }
    return $sum * 4;
}
function nan_compare() {
    $nan = 0.0 * INF;
    $one = 1.0;
    $r = '';
    $r .= ($nan < $one ? 'T' : 'F') . ($nan <= $one ? 'T' : 'F') . ($nan > $one ? 'T' : 'F') . ($nan >= $one ? 'T' : 'F');
    $r .= ($nan == $nan ? 'T' : 'F') . ($nan != $nan ? 'T' : 'F') . ($nan === $nan ? 'T' : 'F') . ($nan !== $nan ? 'T' : 'F');
    $r .= ($one == 1 ? 'T' : 'F') . ($one === 1.0 ? 'T' : 'F') . ($one < 2 ? 'T' : 'F');
    return $r;
}
function zeros() {
    $z = 0.0;
    $neg = $z * -1;
    $big = 1.0e308;
    $big = $big * 10.0;
    $nan = $big - $big;
    $tiny = -1.0e-320;
    return $neg . ' ' . ($neg == 0 ? 'zero' : 'nonzero') . ' ' . $big . ' ' . -$big . ' ' . ($nan != $nan ? 'nan' : 'num')
        . ' ' . ($tiny * 1.0e-10);
}
function divide($a) {
    $x = 1.5;
    $y = $x / $a;
    return $y;
}
function mixed_ops($s) {
    $f = 2.5;
    $f = $f * $s;
    $g = 1.0;
    $g = $g + null;
    $g = $g - true;
    return "$f $g";
}
function chain() {
    $a = $b = $c = 0.25;
    $a = $a + $b + $c;
    $b = $b * 3;
    return $a . ' ' . $b . ' ' . ($a == $b ? 'eq' : 'ne');
}
function loop_compare() {
    $zr = $zi = 0.0;
    $i = 0;
    do {
        $zi = 2.0 * $zr * $zi + 0.3;
        $zr = $zr * $zr - $zi * $zi - 0.5;
    } while ($zr * $zr + $zi * $zi <= 4.0 && ++$i < 50);
    return $i . ' ' . sprintf('%.6f %.6f', $zr, $zi);
}
echo series(1000), "\n";
echo nan_compare(), "\n";
echo zeros(), "\n";
echo divide(4), ' ', divide(0.5), "\n";
try { divide(0); } catch (DivisionByZeroError $e) { echo 'DivisionByZeroError: ', $e->getMessage(), ' @', $e->getLine(), "\n"; }
try { divide(-0.0); } catch (DivisionByZeroError $e) { echo $e->getMessage(), "\n"; }
echo mixed_ops('4'), "\n";
echo mixed_ops('4 apples'), "\n";
try { mixed_ops([]); } catch (TypeError $e) { echo $e->getMessage(), ' @', $e->getLine(), "\n"; }
try { mixed_ops('apples'); } catch (TypeError $e) { echo $e->getMessage(), "\n"; }
echo chain(), "\n";
echo loop_compare(), "\n";
