<?php
// Included by bytecode-include-scope.php into warns(): warnings at this file's lines
trigger_error("warned from the include, value $value", E_USER_WARNING);
$value .= "y";
trigger_error("and again, value $value", E_USER_NOTICE);
