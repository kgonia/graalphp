<?php
// A class whose trait is declared differently in different runs: run in several contexts on one engine, the
// contexts alternate between the two declarations of Policy (a counter in a temp file), so a cache keyed on Gate
// alone would hand one context the other's method or property names. A mismatch is remembered in the file for a
// minute, so the last context reports one an earlier context saw.
$state = sys_get_temp_dir() . '/truffle-php-smoke-trait-variants';
$counter = 0;
$failures = 0;
if (is_file($state)) {
    [$counter, $failures, $time] = array_map('intval', explode(' ', file_get_contents($state)));
    if (time() - $time > 60) {
        $failures = 0;
    }
}
$variant = $counter % 2 === 0 ? 'A' : 'B';
if ($variant === 'A') {
    trait Policy {
        public $fromA = 1;
        function allowed() { return 'A'; }
    }
} else {
    trait Policy {
        public $fromB = 2;
        public $alsoB = 3;
        function allowed() { return 'B'; }
    }
}
if (true) {
    class Gate {
        use Policy;
        public $own = 0;
    }
}
$ok = true;
for ($i = 0; $i < 50; $i++) {
    $gate = new Gate();
    $expected = $variant === 'A' ? ['fromA', 'own'] : ['alsoB', 'fromB', 'own'];
    $names = array_keys(get_object_vars($gate));
    sort($names);
    if ($gate->allowed() !== $variant || $names !== $expected || !($gate instanceof Gate)) {
        $ok = false;
    }
}
if (!$ok) {
    $failures++;
}
file_put_contents($state, ($counter + 1) . ' ' . $failures . ' ' . time());
echo $ok && $failures === 0 ? "ok\n" : "FAIL: a class's trait came from another context\n";
