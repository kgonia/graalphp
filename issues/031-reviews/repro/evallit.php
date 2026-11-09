<?php
$r = (int)$argv[1];
for ($i = 0; $i < 500; $i++) {
    $v = eval('return "' . str_repeat("x", 64) . $r . '_' . $i . '";');
}
