<?php
// included by variable-variables.php from a function: $$name here is the including call's variable
var_dump($made, $$m, $$n);
$k = 'fromInclude';
$$k = 'set by the include';
$$m .= ' and the include';
