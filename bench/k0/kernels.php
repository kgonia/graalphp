<?php
// K0: language-level kernels, independent of WordPress. The Benchmarks Game programs that graalphp measured
// (fannkuchredux, spectralnorm, binarytrees, nbody; https://benchmarksgame-team.pages.debian.net/benchmarksgame/,
// revised BSD licence) with their PHP semantics intact, unlike graalphp's simplified copies, plus small arithmetic
// kernels. Each kernel returns its output instead of printing it, so the driver can checksum it.

/* fannkuchredux.php-1: contributed by Isaac Gouy, transliterated from Mike Pall's Lua program */
function k0_fannkuch(int $n): array {
    $p = $q = $s = array();
    $sign = 1; $maxflips = $sum = 0; $m = $n - 1;
    for ($i = 0; $i < $n; $i++) { $p[$i] = $i; $q[$i] = $i; $s[$i] = $i; }
    do {
        $q0 = $p[0];
        if ($q0 != 0) {
            for ($i = 1; $i < $n; $i++) $q[$i] = $p[$i];
            $flips = 1;
            do {
                $qq = $q[$q0];
                if ($qq == 0) {
                    $sum += $sign * $flips;
                    if ($flips > $maxflips) $maxflips = $flips;
                    break;
                }
                $q[$q0] = $q0;
                if ($q0 >= 3) {
                    $i = 1; $j = $q0 - 1;
                    do { $t = $q[$i]; $q[$i] = $q[$j]; $q[$j] = $t; $i++; $j--; } while ($i < $j);
                }
                $q0 = $qq; $flips++;
            } while (true);
        }
        if ($sign == 1) {
            $t = $p[1]; $p[1] = $p[0]; $p[0] = $t; $sign = -1;
        } else {
            $t = $p[1]; $p[1] = $p[2]; $p[2] = $t; $sign = 1;
            for ($i = 2; $i < $n; $i++) {
                $sx = $s[$i];
                if ($sx != 0) { $s[$i] = $sx - 1; break; }
                if ($i == $m) return array($sum, $maxflips);
                $s[$i] = $i;
                $t = $p[0]; for ($j = 0; $j <= $i; $j++) { $p[$j] = $p[$j + 1]; } $p[$i + 1] = $t;
            }
        }
    } while (true);
}

/* spectralnorm.php-2: contributed by Isaac Gouy, modified by anon */
function k0_A($i, $j) {
    return 1.0 / ((((($i + $j) * ($i + $j + 1)) >> 1) + $i + 1));
}

function k0_Av($n, $v) {
    $Av = array();
    for ($i = 0; $i < $n; ++$i) {
        $sum = 0.0;
        foreach ($v as $j => $v_j) {
            $sum += k0_A($i, $j) * $v_j;
        }
        $Av[$i] = $sum;
    }
    return $Av;
}

function k0_Atv($n, $v) {
    $Atv = array();
    for ($i = 0; $i < $n; ++$i) {
        $sum = 0.0;
        foreach ($v as $j => $v_j) {
            $sum += k0_A($j, $i) * $v_j;
        }
        $Atv[$i] = $sum;
    }
    return $Atv;
}

function k0_spectralnorm(int $n): string {
    $u = array_fill(0, $n, 1.0);
    for ($i = 0; $i < 10; $i++) {
        $v = k0_Atv($n, k0_Av($n, $u));
        $u = k0_Atv($n, k0_Av($n, $v));
    }
    $vBv = 0.0;
    $vv = 0.0;
    foreach ($v as $i => $val) {
        $vBv += $u[$i] * $val;
        $vv += $val * $val;
    }
    return sprintf("%0.9f", sqrt($vBv / $vv));
}

/* binarytrees.php-3: contributed by Peter Baltruschat, modified by Levi Cameron */
function k0_bottomUpTree($depth) {
    if (!$depth) return array(null, null);
    $depth--;
    return array(k0_bottomUpTree($depth), k0_bottomUpTree($depth));
}

function k0_itemCheck($treeNode) {
    return 1
        + ($treeNode[0][0] === null ? 1 : k0_itemCheck($treeNode[0]))
        + ($treeNode[1][0] === null ? 1 : k0_itemCheck($treeNode[1]));
}

function k0_binarytrees(int $n): string {
    $out = '';
    $minDepth = 4;
    $maxDepth = max($minDepth + 2, $n);
    $stretchDepth = $maxDepth + 1;
    $stretchTree = k0_bottomUpTree($stretchDepth);
    $out .= sprintf("stretch tree of depth %d\t check: %d\n", $stretchDepth, k0_itemCheck($stretchTree));
    unset($stretchTree);
    $longLivedTree = k0_bottomUpTree($maxDepth);
    $iterations = 1 << ($maxDepth);
    do {
        $check = 0;
        for ($i = 1; $i <= $iterations; ++$i) {
            $t = k0_bottomUpTree($minDepth);
            $check += k0_itemCheck($t);
            unset($t);
        }
        $out .= sprintf("%d\t trees of depth %d\t check: %d\n", $iterations, $minDepth, $check);
        $minDepth += 2;
        $iterations >>= 2;
    } while ($minDepth <= $maxDepth);
    $out .= sprintf("long lived tree of depth %d\t check: %d\n", $maxDepth, k0_itemCheck($longLivedTree));
    return $out;
}

/* nbody.php-3: contributed by anon, modified by Sergey Khripunov */
function k0_energy(&$b) {
    $e = 0.0;
    for ($i = 0, $m = sizeof($b); $i < $m; $i++) {
        $b1 = $b[$i];
        $e += 0.5 * $b1[6] * ($b1[3] * $b1[3] + $b1[4] * $b1[4] + $b1[5] * $b1[5]);
        for ($j = $i + 1; $j < $m; $j++) {
            $b2 = $b[$j];
            $dx = $b1[0] - $b2[0]; $dy = $b1[1] - $b2[1]; $dz = $b1[2] - $b2[2];
            $e -= ($b1[6] * $b2[6]) / sqrt($dx * $dx + $dy * $dy + $dz * $dz);
        }
    }
    return $e;
}

function k0_nbody(int $n): string {
    $pi = 3.141592653589793;
    $solar_mass = 4 * $pi * $pi;
    $days_per_year = 365.24;
    $bodies = array(array(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, $solar_mass),
        array(4.84143144246472090E+00, -1.16032004402742839E+00, -1.03622044471123109E-01,
            1.66007664274403694E-03 * $days_per_year, 7.69901118419740425E-03 * $days_per_year,
            -6.90460016972063023E-05 * $days_per_year, 9.54791938424326609E-04 * $solar_mass),
        array(8.34336671824457987E+00, 4.12479856412430479E+00, -4.03523417114321381E-01,
            -2.76742510726862411E-03 * $days_per_year, 4.99852801234917238E-03 * $days_per_year,
            2.30417297573763929E-05 * $days_per_year, 2.85885980666130812E-04 * $solar_mass),
        array(1.28943695621391310E+01, -1.51111514016986312E+01, -2.23307578892655734E-01,
            2.96460137564761618E-03 * $days_per_year, 2.37847173959480950E-03 * $days_per_year,
            -2.96589568540237556E-05 * $days_per_year, 4.36624404335156298E-05 * $solar_mass),
        array(1.53796971148509165E+01, -2.59193146099879641E+01, 1.79258772950371181E-01,
            2.68067772490389322E-03 * $days_per_year, 1.62824170038242295E-03 * $days_per_year,
            -9.51592254519715870E-05 * $days_per_year, 5.15138902046611451E-05 * $solar_mass));
    $px = $py = $pz = 0.0;
    foreach ($bodies as &$e) {
        $px += $e[3] * $e[6];
        $py += $e[4] * $e[6];
        $pz += $e[5] * $e[6];
    }
    unset($e);
    $bodies[0][3] = -$px / $solar_mass;
    $bodies[0][4] = -$py / $solar_mass;
    $bodies[0][5] = -$pz / $solar_mass;
    $pairs = array();
    for ($i = 0, $m = count($bodies); $i < $m; $i++)
        for ($j = $i + 1; $j < $m; $j++)
            $pairs[] = array(&$bodies[$i], &$bodies[$j]);
    $out = sprintf("%0.9f\n", k0_energy($bodies));
    $i = 0;
    do {
        foreach ($pairs as &$p) {
            $a = &$p[0]; $b = &$p[1];
            $dx = $a[0] - $b[0]; $dy = $a[1] - $b[1]; $dz = $a[2] - $b[2];
            $dist = sqrt($dx * $dx + $dy * $dy + $dz * $dz);
            $mag = 0.01 / ($dist * $dist * $dist);
            $mag_a = $a[6] * $mag; $mag_b = $b[6] * $mag;
            $a[3] -= $dx * $mag_b; $a[4] -= $dy * $mag_b; $a[5] -= $dz * $mag_b;
            $b[3] += $dx * $mag_a; $b[4] += $dy * $mag_a; $b[5] += $dz * $mag_a;
        }
        unset($p, $a, $b);
        foreach ($bodies as &$b) {
            $b[0] += 0.01 * $b[3]; $b[1] += 0.01 * $b[4]; $b[2] += 0.01 * $b[5];
        }
        unset($b);
    } while (++$i < $n);
    return $out . sprintf("%0.9f\n", k0_energy($bodies));
}

/* Arithmetic kernels (this project). */
function k0_fib(int $n): int {
    return $n < 2 ? $n : k0_fib($n - 1) + k0_fib($n - 2);
}

function k0_intloop(int $n): int {
    $s = 0;
    for ($i = 0; $i < $n; $i++) {
        $s = ($s + $i * $i + ($i >> 3) - ($i & 7)) % 1000003;
    }
    return $s;
}

function k0_floatloop(int $n): string {
    $pi = 0.0;
    $sign = 1.0;
    for ($i = 0; $i < $n; $i++) {
        $pi += $sign / (2 * $i + 1);
        $sign = -$sign;
    }
    return sprintf('%.12f', 4 * $pi);
}

function k0_mandelbrot(int $n): int {
    $hash = 0;
    for ($y = 0; $y < $n; $y++) {
        $ci = 2.0 * $y / $n - 1.0;
        for ($x = 0; $x < $n; $x++) {
            $cr = 2.0 * $x / $n - 1.5;
            $zr = $zi = $tr = $ti = 0.0;
            $i = 0;
            do {
                $zi = 2.0 * $zr * $zi + $ci;
                $zr = $tr - $ti + $cr;
                $tr = $zr * $zr;
                $ti = $zi * $zi;
            } while ($tr + $ti <= 4.0 && ++$i < 50);
            $hash = ($hash * 31 + ($tr + $ti <= 4.0 ? $x + 1 : 0)) % 1000000007;
        }
    }
    return $hash;
}

function k0_sieve(int $n): int {
    $flags = array_fill(0, $n + 1, true);
    $count = 0;
    for ($i = 2; $i <= $n; $i++) {
        if ($flags[$i]) {
            $count++;
            for ($k = $i * $i; $k <= $n; $k += $i) {
                $flags[$k] = false;
            }
        }
    }
    return $count;
}

function k0_bubblesort(int $n): int {
    $a = [];
    $seed = 42;
    for ($i = 0; $i < $n; $i++) {
        $seed = ($seed * 1103515245 + 12345) % 2147483648;
        $a[] = $seed % 100000;
    }
    for ($i = 0; $i < $n; $i++) {
        for ($j = 0; $j < $n - 1 - $i; $j++) {
            if ($a[$j] > $a[$j + 1]) {
                $t = $a[$j]; $a[$j] = $a[$j + 1]; $a[$j + 1] = $t;
            }
        }
    }
    $sum = 0;
    foreach ($a as $i => $v) {
        $sum = ($sum + $i * $v) % 1000000007;
    }
    return $sum;
}

return [
    'fannkuch' => fn() => k0_fannkuch(9),
    'spectralnorm' => fn() => k0_spectralnorm(250),
    'binarytrees' => fn() => k0_binarytrees(12),
    'nbody' => fn() => k0_nbody(50000),
    'fib' => fn() => k0_fib(27),
    'intloop' => fn() => k0_intloop(3000000),
    'floatloop' => fn() => k0_floatloop(3000000),
    'mandelbrot' => fn() => k0_mandelbrot(400),
    'sieve' => fn() => k0_sieve(500000),
    'bubblesort' => fn() => k0_bubblesort(1500),
];
