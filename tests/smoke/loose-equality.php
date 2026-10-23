<?php
// `==`, `!=` and switch (which compares with ==) across type pairs: numeric strings, leading whitespace, signs,
// floats, null, booleans, arrays, and sites that see many type pairs.
$values = ['1', '01', '1.0', ' 1', '1 ', '+1', '-0', '0', '', 'abc', '1e0', '.5', '0.5', "\t2", '2abc', 'null',
    1, 0, -0.0, 0.5, 1.0, 2, true, false, null, [], [1], ['1'], '10', '1e1', 'ABC', 'abc '];
foreach ($values as $i => $a) {
    $row = '';
    foreach ($values as $b) {
        $row .= ($a == $b ? '1' : '0') . (($a != $b) === ($a == $b) ? '!' : '');
    }
    echo str_pad((string) $i, 3), $row, "\n";
}
function classify($v) {
    switch ($v) {
        case 'abc':
            return 'abc';
        case '1':
            return 'one';
        case 0.5:
            return 'half';
        case null:
            return 'null-ish';
        case 10:
            return 'ten';
        case true:
            return 'truthy';
        default:
            return 'default';
    }
}
function fallthrough($v) {
    $out = [];
    switch ($v) {
        case 1:
            $out[] = 'a';
        case '2':
            $out[] = 'b';
            break;
        case 3.0:
            $out[] = 'c';
        default:
            $out[] = 'd';
        case 'x':
            $out[] = 'e';
    }
    return implode('', $out);
}
for ($round = 0; $round < 3; $round++) {
    $line = [];
    foreach ($values as $v) {
        $line[] = classify($v) . '/' . fallthrough($v);
    }
    if ($round == 2) {
        echo implode(' ', $line), "\n";
    }
}
$strings = ['post', 'page', 'attachment', 'revision', 'nav_menu_item', '0', '00', '0.0', ' 0'];
$count = 0;
for ($i = 0; $i < 2000; $i++) {
    $s = $strings[$i % count($strings)];
    if ($s == 'post' || 'page' == $s || $s == '0' || $s != 'x') {
        $count++;
    }
    switch ($s) {
        case 'attachment': $count += 2; break;
        case 'revision': $count += 3; break;
        case 0: $count += 5; break;
    }
}
echo $count, "\n";
