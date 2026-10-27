<?php
// A request that asks for 300,000 time zones that don't exist (names a client could send): what the runtime caches
// about zone names must stay bounded however many distinct names requests bring.
$bad = 0;
for ($i = 0; $i < 300000; $i++) {
    try {
        new DateTimeZone(str_pad("Nowhere/$i", 200, '_'));
    } catch (Exception $e) {
        $bad++;
    }
}
echo $bad, "\n";
