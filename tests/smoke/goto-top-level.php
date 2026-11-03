<?php
// Issues 007: goto in a file's top-level code, whose labels are the file's: forward, backward as a loop, over a
// try (php-src's gh22280), out of a loop and a switch (jump15), out of a try's finally-less body, in a block.
$i = 0;
again:
echo "i=$i\n";
if (++$i < 3) {
    goto again;
}
goto d;
try {
    echo "not run\n";
} catch (Throwable) {
}
d: try {
    echo "try\n";
} finally {
    echo "finally\n";
}
foreach ([1, 2, 3] as $val) {
    switch ($val) {
        case 1:
            echo "$val: ok\n";
            break;
        case 2:
            echo "$val: ok\n";
            goto L1;
        case 3:
            echo "bug\n";
            break;
    }
}
echo "bug\n";
L1:
try {
    echo "3: ok\n";
    goto out;
} finally {
    echo "finally runs on the way out\n";
}
echo "skipped\n";
out:
{
    echo "in a block\n";
    goto block_end;
    echo "skipped\n";
    block_end:
}
echo "done\n";
