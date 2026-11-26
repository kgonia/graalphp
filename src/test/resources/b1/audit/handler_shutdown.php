<?php
class Payload { function __destruct() { echo "release payload\n"; } }
class Last { function __destruct() { echo "last\n"; } }
function fail($payload) { throw new Exception("shutdown"); }
class Throws { function __destruct() { fail(new Payload()); } }
function handle($e) { echo $e->getMessage() . "\n"; }
set_exception_handler("handle");
$last = new Last();
$first = new Throws();
