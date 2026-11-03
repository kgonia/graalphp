<?php
declare(strict_types=1);
// a literal argument of defined() is compiled in place whatever its type (zend_compile_func_defined), so even in
// strict mode an int or a float names the constant its string form does
define('1', 'one');
define('1.5', 'one and a half');
var_dump(defined(1), defined(1.5), defined(2), defined(1e30), defined('1'));
