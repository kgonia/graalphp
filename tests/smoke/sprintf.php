<?php
// sprintf, printf and vsprintf: every conversion with flags, widths, precisions, padding characters, argument
// numbers and star arguments; negative zero, rounding, huge and tiny floats, INF and NAN; the errors.
$floats = [-0.04, -0.0, 0.0, -0.4, -0.5, 0.5, 1.5, 2.5, -2.5, 0.125, 0.375, 1.005, 2.675, 1e15, 1e16, 1.5e300,
    -1e-10, 123.456, -123.456, 0.1, 1/3, 9.9999, 99.995, 1e22, 5e-324, PHP_INT_MAX + 1.0, -1.0, 100.0];
$formats = ['%f', '%.1f', '%.0f', '%5.1f', '%-6.1f|', '%05.1f', '%+.1f', '%+05.1f', '%-+08.2f|', "%'*10.2f",
    '%.3F', '%e', '%.0e', '%.2e', '%E', '%+e', '%012.3e', '%g', '%.3g', '%G', '%.0g', '%h', '%.10H', '%+g',
    '%010g', '%-10g|', '%.15g', '%.20f', '%.60f', '%d', '%5d', '%-5d|', '%05d', '%+d', '%u', '%x', '%o', '%b',
    '%s', '%10.4f', "%' 9.2f", '%1$f %1$e %1$g'];
foreach ($floats as $v) {
    foreach ($formats as $f) {
        echo $f, ' => [', @sprintf($f, $v), "]\n";
    }
}
foreach ([INF, -INF, NAN] as $v) {
    foreach (['%f', '%10f', '%-10f|', '%010f', '%+f', '%+010f', '%e', '%g', '%G', '%d', '%s', '%5.1F'] as $f) {
        echo $f, ' => [', @sprintf($f, $v), "]\n";
    }
}
$ints = [0, 1, -1, 42, -42, PHP_INT_MAX, PHP_INT_MIN, 255, -255];
foreach ($ints as $v) {
    foreach (['%d', '%5d', '%-5d|', '%05d', '%-05d|', '%+d', '%+05d', '%u', '%x', '%X', '%o', '%b', '%08x',
        '%-8x|', '%.3x', '%5.1x', '%c', '%10u', '%+u', '%e', '%.2f', "%'#10d", '%-+6d|', '% d', '%+-08d|'] as $f) {
        echo $f, ' => [', @sprintf($f, $v), "]\n";
    }
}
$strings = ['', 'a', 'hello', "bytes\xff\x00x", '12abc', '3.7', 'é'];
foreach ($strings as $v) {
    foreach (['%s', '%10s', '%-10s|', '%010s', '%.2s', '%5.1s', '%-5.3s|', "%'_8s", '%.0s', '%.s', '%d', '%f',
        '%x', '%+s'] as $f) {
        echo $f, ' => [', @sprintf($f, $v), "]\n";
    }
}
echo sprintf('%2$s %1$s %2$05d %1$\'x6s', 'a', 7), "\n";
echo sprintf('%*d|%-*d|%.*f|%*.*f', 5, 42, 4, 7, 2, M_PI, 8, 3, -M_E), "\n";
echo sprintf('%1$*2$d|%3$.*4$f', 9, 6, 1.25, 1), "\n";
echo sprintf('%.*g|%.*G|%.*h', -1, 0.1 + 0.2, -1, 1e25, -1, 1/3), "\n";
echo sprintf('100%% %s%%', 'x'), ' ', sprintf('%5%', 1), ' ', sprintf('%-5%|', 1), "\n";
echo sprintf('%ld %lu %lf', 5, 6, 7.5), "\n";
echo sprintf('no args'), sprintf(''), "\n";
echo sprintf('%s %s %s', true, false, null), "\n";
echo sprintf('%d %d %d %d', '12abc', '0x1A', 3.99, -3.99), "\n";
echo sprintf('%.2f %.2f %.2f', '1.005', '2.675', '-0.001'), "\n";
echo sprintf("%c%c%c", 80, 72, 80 + 256), "\n";
echo vsprintf('%2$s-%1$s-%3$05.1f', ['a', 'b', -0.04]), "\n";
$n = printf("%s=%05.2f\n", 'pi', M_PI);
var_dump($n);
foreach (['%', '%5', '%z', "%\0", '%0$s', '%*s', '%.*s', '%2$s', '%.-1f', '%*.*f', "%'", '%999999999999s',
    '%.999999999999f', '%1$*s'] as $bad) {
    try {
        var_dump(sprintf($bad, 'x'));
    } catch (\Throwable $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}
foreach (['%s %s', '%3$s'] as $bad) {
    try {
        var_dump(vsprintf($bad, ['x']));
    } catch (\Throwable $e) {
        echo get_class($e), ': ', $e->getMessage(), "\n";
    }
}
try {
    var_dump(sprintf('%*d', 'x', 5));
} catch (\Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
try {
    var_dump(sprintf('%.*f', -1, 5));
} catch (\Throwable $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
