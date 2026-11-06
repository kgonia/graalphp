<?php
// Narrow and Wide are the including file's: the compile can't check Taken::make against Base::make
echo "included runs\n";
var_dump(class_exists('Base', false));
class Base { function make(): Wide { return new Wide(); } }
class Taken extends Base { function make(): Narrow { return new Narrow(); } }
echo "after\n";
