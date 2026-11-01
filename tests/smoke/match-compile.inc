<?php
echo "the included file runs\n";
$x = 1;
echo match ($x) {
    1 => "one",
    default => "first default",
    2 => "two",
    default => "second default",
};
