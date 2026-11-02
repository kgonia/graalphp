<?php
// goto in functions (on bytecode): forward out of a switch and nested ifs, backward as a loop, out of loops, past
// a finally block (which runs), as WordPress's HTML processor and compat-utf8.php use it.
function step($op) {
    switch ($op) {
        case 'a':
            if (strlen($op) === 1) {
                goto anything_else;
            }
            return 'unreachable';
        case 'b':
            return 'b';
    }
    anything_else:
    return "else($op)";
}
echo step('a'), ' ', step('b'), ' ', step('c'), "\n";

function count_down($n) {
    $out = [];
    again:
    $out[] = $n;
    if (--$n > 0) {
        goto again;
    }
    return implode(',', $out);
}
echo count_down(5), "\n";

function find_first($rows, $needle) {
    $found = null;
    foreach ($rows as $i => $row) {
        foreach ($row as $j => $cell) {
            if ($cell === $needle) {
                $found = "$i:$j";
                goto done;
            }
        }
    }
    $found = 'none';
    done:
    return $found;
}
echo find_first([[1, 2], [3, 4]], 4), ' ', find_first([[1]], 9), "\n";

function with_finally() {
    try {
        echo "try ";
        goto out;
    } finally {
        echo "finally ";
    }
    echo "skipped ";
    out:
    echo "out\n";
}
with_finally();

function utf8_like($s) {
    $i = 0;
    $bad = 0;
    while ($i < strlen($s)) {
        $c = ord($s[$i]);
        if ($c > 0x7f) {
            goto invalid;
        }
        $i++;
        continue;
        invalid:
        $bad++;
        $i++;
    }
    return $bad;
}
echo utf8_like("ab\xffc\xfe"), "\n";
