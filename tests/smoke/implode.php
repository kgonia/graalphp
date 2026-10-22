<?php
// implode/join over ints (incl. negatives and PHP_INT_MIN), floats, strings, substrings, bools and null.
$s = 'abcdef';
echo implode(',', [0, 7, -12, PHP_INT_MAX, PHP_INT_MIN]), "\n";
echo implode('|', [1.5, 'x', substr($s, 2, 3), true, false, null, '']), "\n";
echo join('-', ['a' => 'k', 'b' => 2]), "\n";
echo implode([1, 2, 3]), "\n";
echo implode(', ', []), "|\n";
