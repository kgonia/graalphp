<?php
// Statement lists resumed in the middle, for loops with comma-separated init/condition/step, several catch
// clauses (in generators, resumed inside a later one), and goto blocks run from a label.
class AError extends Exception {}
class BError extends Exception {}
function gen($throwAt) {
    echo "start ";
    for ($i = 0, $j = 10; print('') || $i < 4, $j > 0; $i++, $j -= 2) {
        echo "i$i ";
        $x = yield $i => $j;
        echo "got[$x] ";
        try {
            if ($i === $throwAt) {
                throw $i % 2 ? new BError("b$i") : new AError("a$i");
            }
            yield 100 + $i;
        } catch (AError $e) {
            echo "A:", $e->getMessage(), ' ';
        } catch (BError $e) {
            echo "B:", $e->getMessage(), ' ';
            yield 200 + $i;
            echo "after-b ";
        } finally {
            echo "fin$i ";
        }
    }
    echo "end ";
    return 'done';
}
foreach ([-1, 1, 2] as $throwAt) {
    $g = gen($throwAt);
    $out = [];
    foreach ($g as $k => $v) {
        $out[] = "$k=>$v";
        if ($v > 150) {
            $g->send('s');
        }
    }
    echo "\n", implode(',', $out), ' ', $g->getReturn(), "\n";
}
function blocks() {
    $n = 0;
    yield 'a';
    $n++;
    yield 'b';
    $n++;
    yield 'c' . $n;
}
echo implode('', iterator_to_array(blocks())), "\n";

function jumps($limit) {
    $out = '';
    $i = 0;
    top:
    $out .= "[$i]";
    $i++;
    try {
        if ($i % 3 == 0) {
            throw new BError("b");
        }
        if ($i % 5 == 0) {
            throw new AError("a");
        }
    } catch (AError $e) {
        $out .= 'A';
    } catch (BError $e) {
        $out .= 'B';
    }
    if ($i < $limit) {
        goto top;
    }
    goto done;
    $out .= 'never';
    done:
    return $out;
}
for ($k = 0; $k < 3; $k++) {
    echo jumps(10 + $k), "\n";
}
