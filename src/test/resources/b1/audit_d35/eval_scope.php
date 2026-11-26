<?php
class E { function who() { return eval('return $this;'); } }
$e = new E(); echo $e === $e->who(), "\n";
echo eval("try { throw new Exception(); } catch (Exception \$e) {}") === null, "\n";
eval("echo '\xff';");
try { eval("return\n(;"); } catch (ParseError $e) { echo $e->getMessage(), ' ', $e->getFile(), ':', $e->getLine(); }
