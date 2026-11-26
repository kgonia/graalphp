<?php
class Payload { function __destruct() { echo "release payload\n"; } }
class Last { function __destruct() { echo "shutdown\n"; } }
function fail($payload) { throw new Exception("top"); }
function handle($e) { echo $e->getMessage() . "\n"; }
set_exception_handler("handle");
$last = new Last();
fail(new Payload());
echo "unreachable\n";
