<?php
// goto: forward out of a switch, backward as a loop, out of nested blocks.
function classify($token)
{
    switch ($token) {
        case 'space':
            return 'ignored';
        case 'text':
            goto anything_else;
        case 'tag':
            return 'tag';
    }
    return 'unknown';

    anything_else:
    return "else($token)";
}
echo classify('space'), ' ', classify('text'), ' ', classify('tag'), ' ', classify('x'), "\n";

function count_down($n)
{
    $out = [];
    loop:
    if ($n > 0) {
        $out[] = $n;
        $n--;
        goto loop;
    }
    return implode(',', $out);
}
echo count_down(5), "\n";

function find($items, $wanted)
{
    foreach ($items as $i => $item) {
        foreach ($item as $j => $value) {
            if ($value === $wanted) {
                goto found;
            }
        }
    }
    return 'missing';
    found:
    return "found at $i,$j";
}
echo find([[1, 2], [3, 4]], 4), ' ', find([[1]], 9), "\n";
