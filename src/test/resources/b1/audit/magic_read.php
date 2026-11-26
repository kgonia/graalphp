<?php
class Getter { function __get($name) { return "read:" . $name; } }
$obj = new Getter();
echo $obj->missing . "\n";
