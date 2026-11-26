<?php
try { new PrintableLater(); } catch (Error $e) { echo $e->getMessage() . "\n"; }
class PrintableLater { function __toString() { return "declared"; } }
$obj = new PrintableLater();
echo $obj . "\n";
echo "before return\n";
return;
class stdClass {}
