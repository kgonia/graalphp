<?php
// switch, break N and continue N in functions on bytecode: loose comparison
// against each case in order (the case values evaluated one by one until one matches), default anywhere,
// fall-through, a switch as a level for break and continue, and yields in case values and bodies.

function side($label, $value) {
    echo "[$label]";
    return $value;
}

function joined($list) {
    $s = '';
    foreach ($list as $i => $x) {
        $s .= ($i ? ' ' : '') . $x;
    }
    return $s;
}

function kind($v) {
    switch ($v) {
        case 1:
            return 'one';
        case 'two':
            return 'two';
        case null:
            return 'null-ish';
        case [1, 2]:
            return 'array';
        case 2.5:
            return 'float';
        default:
            return 'other';
    }
}

function fall($v) {
    $out = [];
    switch ($v) {
        case 'a':
            $out[] = 'a';
        case 'b':
            $out[] = 'b';
            break;
        case 'c':
            $out[] = 'c';
        default:
            $out[] = 'default';
        case 'd':
            $out[] = 'd';
    }
    return joined($out);
}

function default_first($v) {
    switch ($v) {
        default:
            echo "default ";
        case 1:
            echo "one ";
            break;
        case 2:
            echo "two ";
    }
    echo "\n";
}

function order($v) {
    switch (side('subject', $v)) {
        case side('first', 1):
            echo " matched first\n";
            break;
        case side('second', 2):
            echo " matched second\n";
            break;
        case side('third', 3):
            echo " matched third\n";
            break;
    }
    echo " after\n";
}

function loops() {
    $log = [];
    for ($i = 0; $i < 6; $i++) {
        switch ($i % 3) {
            case 0:
                $log[] = "$i:zero";
                continue 2;
            case 1:
                $log[] = "$i:one";
                break;
            default:
                $log[] = "$i:two";
                if ($i > 4) {
                    break 2;
                }
        }
        $log[] = "$i:after";
    }
    return joined($log);
}

function nested_levels() {
    $log = [];
    foreach ([1, 2, 3] as $a) {
        foreach (['x', 'y'] as $b) {
            switch ($b) {
                case 'x':
                    switch ($a) {
                        case 2:
                            $log[] = "$a$b:continue 3";
                            continue 4;
                        case 3:
                            $log[] = "$a$b:break 4";
                            break 4;
                    }
                    $log[] = "$a$b:inner done";
                    break;
                case 'y':
                    $log[] = "$a$b:y";
                    continue 2;
            }
            $log[] = "$a$b:end";
        }
        $log[] = "$a:outer end";
    }
    $n = 0;
    while (true) {
        $n++;
        do {
            if ($n < 3) {
                continue 2;
            }
            break 2;
        } while (false);
    }
    $log[] = "n=$n";
    for ($i = 0; $i < 3; $i++) {
        for ($j = 0; $j < 3; $j++) {
            if ($j == 1) {
                continue 2;
            }
            if ($i == 2) {
                break 2;
            }
            $log[] = "$i$j";
        }
    }
    return joined($log);
}

function empty_switch($v) {
    switch (side('only subject', $v)) {
    }
    switch ($v) {
        default:
    }
    echo "\n";
}

function strings($s) {
    switch ($s) {
        case '1e1':
            return 'ten as 1e1';
        case 10:
            return 'ten';
        case '0':
            return 'zero string';
        case 'abc':
            return 'abc';
        case 0:
            return 'zero int';
    }
    return 'none';
}

function in_try($v) {
    try {
        switch ($v) {
            case 1:
                throw new Exception('from case 1');
            case 2:
                return 'returned';
            default:
                break;
        }
    } catch (Exception $e) {
        return 'caught ' . $e->getMessage();
    } finally {
        echo "[finally]";
    }
    return 'fell out';
}

function choose($which) {
    switch (yield 'subject') {
        case $which:
            echo "matched which\n";
        case yield 'case value':
            echo "matched or fell into the yielded case\n";
            $got = yield 'in body';
            echo "body got $got\n";
            break;
        default:
            echo "default: ", yield 'default', "\n";
    }
    return 'done';
}

function drive($g, ...$sends) {
    echo json_encode($g->current()), "\n";
    foreach ($sends as $s) {
        $v = $g->send($s);
        echo json_encode($v), "\n";
    }
    echo 'return: ', json_encode($g->getReturn()), "\n";
}

function loop_gen() {
    foreach ([1, 2, 3, 4] as $i) {
        switch ($i) {
            case 2:
                continue 2;
            case 4:
                break 2;
        }
        yield $i;
    }
}

foreach ([1, '1', true, 'two', null, false, 0, '', [1, 2], [2, 1], 2.5, '2.5', 'x'] as $v) {
    echo json_encode($v), ' => ', kind($v), "\n";
}
foreach (['a', 'b', 'c', 'd', 'e'] as $v) {
    echo "$v: ", fall($v), "\n";
}
default_first(1);
default_first(2);
default_first(3);
order(2);
order(5);
echo loops(), "\n";
echo nested_levels(), "\n";
empty_switch(1);
foreach (['10', 10, '1e1', '0', 0, 'abc', '', null, 'other'] as $v) {
    echo json_encode($v), ' => ', strings($v), "\n";
}
foreach ([1, 2, 3] as $v) {
    echo in_try($v), "\n";
}
drive(choose('a'), 'a', 'x', 'sent');
drive(choose('a'), 'b', 'b', 'sent2');
drive(choose('a'), 'b', 'c', 'D');
echo json_encode(iterator_to_array(loop_gen(), false)), "\n";
