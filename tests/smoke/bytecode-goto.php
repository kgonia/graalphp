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

// into an if, as WP_HTML_Processor::step_in_foreign_content does: the condition isn't evaluated, the statements
// before the label are skipped; into a later clause, a nested if and a block; backward from the else into the then
function cond($v) {
    echo "cond($v) ";
    return $v;
}
function into_if($kind) {
    switch ($kind) {
        case 'then':
            goto in_then;
        case 'elseif':
            goto in_elseif;
        case 'else':
            goto in_else;
        case 'nested':
            goto in_nested;
        case 'block':
            goto in_block;
    }
    echo "start ";
    if (cond(false)) {
        echo "then-before ";
        in_then:
        echo "then ";
    } elseif (cond(false)) {
        echo "elseif-before ";
        in_elseif:
        echo "elseif ";
    } else {
        echo "else-before ";
        in_else:
        echo "else ";
        if (cond(false)) {
            echo "nested-before ";
            in_nested:
            echo "nested ";
        }
    }
    {
        echo "block-before ";
        in_block:
        echo "block ";
    }
    echo "end\n";
}
foreach (['then', 'elseif', 'else', 'nested', 'block', 'none'] as $kind) {
    into_if($kind);
}

function else_to_then($n) {
    if ($n > 0) {
        echo "then-before ";
        in_then:
        echo "then($n) ";
        $n--;
    } else {
        echo "else ";
        $n = 2;
        goto in_then;
    }
    if ($n > 0) {
        goto in_then;
    }
    echo "done\n";
}
else_to_then(0);
