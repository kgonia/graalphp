<?php
require __DIR__ . "/named_decl.php";
ordered(b: 2, a: 1);
try { ordered(b: 3); } catch (ArgumentCountError $e) { echo $e->getMessage() . "\n"; }
