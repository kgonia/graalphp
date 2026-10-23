<?php
// get_defined_constants() lists the user's constants after the predefined ones, in definition order.
define('ZETA', 1);
const ALPHA = 2;
define('MIDDLE', [3]);
const BETA = 'b';
var_dump(array_slice(get_defined_constants(), -4, 4, true));
