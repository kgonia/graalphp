<?php
// Issue 064: var_dump of the same object from inside its __debugInfo prints *RECURSION*.
class A { function __debugInfo() { var_dump($this); return []; } }
var_dump(new A);
