<?php
// Everyday constructs in hot functions: arithmetic, strings, arrays (packed and hashed, by value and by
// reference), closures, methods, static calls, exceptions, generators. Each is compiled at both tiers.
interface Shape { public function area(): float; }
final class Rect implements Shape {
    public function __construct(private float $w, private float $h) {}
    public function area(): float { return $this->w * $this->h; }
    public static function square(float $s): self { return new self($s, $s); }
}
function words(string $text): array {
    $out = [];
    foreach (explode(' ', $text) as $i => $word) {
        $out[$word] = ($out[$word] ?? 0) + $i;
    }
    ksort($out);
    return $out;
}
function bump(array &$counts, string $key): void {
    $counts[$key] = isset($counts[$key]) ? $counts[$key] + 1 : 1;
}
function squares(int $n): Generator {
    for ($i = 0; $i < $n; $i++) {
        yield $i => $i * $i;
    }
}
function risky(int $i): int {
    try {
        if ($i % 7 === 0) {
            throw new RuntimeException("seven $i");
        }
        return $i;
    } catch (RuntimeException $e) {
        return strlen($e->getMessage());
    } finally {
        $i++;
    }
}
$scale = 3;
$mul = fn(int $x): int => $x * $scale;
$sum = 0;
$counts = [];
$total = 0.0;
for ($round = 0; $round < 2500; $round++) {
    $sum += array_sum(array_map($mul, [1, 2, 3])) + risky($round);
    foreach (squares(4) as $k => $v) {
        $sum += $k ^ $v;
    }
    bump($counts, 'k' . ($round % 5));
    $w = words("b a c a $round");
    $sum += count($w) + ($w['a'] ?? 0);
    $total += Rect::square($round % 3)->area() + (new Rect(1.5, 2))->area();
    $list = [3, 1, 2];
    foreach ($list as &$item) {
        $item *= 2;
    }
    unset($item);
    $sum += $list[0] + strlen(sprintf('%05d', $round)) + intdiv($round, 3);
}
echo $sum, ' ', $total, ' ', json_encode($counts), "\n";
