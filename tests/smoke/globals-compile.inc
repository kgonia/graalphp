<?php
echo "the included file runs\n";
$GLOBALS['fine'] = 1;
$GLOBALS['fine'] += 1;
$copy = &$GLOBALS;
