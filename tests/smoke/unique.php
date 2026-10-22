<?php
// array_unique (string comparison: 1 == "1"), array_diff, array_intersect; keys are preserved.
echo json_encode(array_unique(['a', 'b', 'a', 1, '1', 1.0, 'B', true, ''])), "\n";
echo json_encode(array_unique(['x' => 'http', 'y' => 'https', 'z' => 'http'])), "\n";
echo json_encode(array_diff(['a', 'b', 'c', 1], ['b'], ['1'])), "\n";
echo json_encode(array_intersect(['a', 'b', 'c', 2], ['c', 'a', '2'])), "\n";
