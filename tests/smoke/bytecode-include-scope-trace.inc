<?php
// Included by bytecode-include-scope.php into traced(): a trace from inside an included file
foreach (debug_backtrace() as $frame) {
    echo base($frame["file"] ?? "-"), ":", $frame["line"] ?? "-", " ", $frame["function"], "\n";
}
return $local;
