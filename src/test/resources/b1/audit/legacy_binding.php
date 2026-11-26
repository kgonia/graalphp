<?php
function forceFallback() { return []; }
class Old { function __destruct() { echo "old\n"; throw new Exception("replacement"); } }
class Payload { function __destruct() { echo "payload\n"; } }
function fail($payload) { throw new Exception("caught"); }
function run() {
    $e = new Old();
    try {
        try { fail(new Payload()); } catch (Exception $e) { echo "unreachable\n"; }
    } catch (Exception $new) { echo $new->getMessage() . "/" . $e->getMessage() . "\n"; }
    $e = 0;
    echo "released\n";
}
run();
