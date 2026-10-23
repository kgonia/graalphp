<?php
// Writes the parts of PHP's timezone data (timelib's) that java.time lacks, for Truffle PHP's
// builtins/DateFunctions.java: the abbreviations DateTimeZone accepts and what the date formats print as a zone's
// abbreviation and DST flag. Run in the reference container:
//   php tools/tz-tables.php src/main/resources/pl/codeloom/php/builtins/tz-tables.txt
// Output lines:
//   abbr <lower-case abbreviation> <offset seconds> <dst 0|1>     timelib_timezone_lookup's first entry per name
//   ids <identifier>...                                            DateTimeZone::listIdentifiers()
//   zone <identifier> <era>...                                     one per DateTimeZone::listIdentifiers(ALL_WITH_BC)
// An era is <start timestamp>=<offset>:<dst>:<abbr>[,<offset>:<dst>:<abbr>...]: from its start (until the next
// era's), a moment at that UTC offset has that DST flag and abbreviation. A new era starts at a transition whose
// offset the current era maps to a different flag or abbreviation. Transitions are taken up to 2200.
$out = [];
foreach (DateTimeZone::listAbbreviations() as $abbr => $entries) {
    $out[] = sprintf('abbr %s %d %d', $abbr, $entries[0]['offset'], $entries[0]['dst'] ? 1 : 0);
}
$out[] = 'ids ' . implode(' ', DateTimeZone::listIdentifiers());
$end = (new DateTimeImmutable('2200-01-01 00:00:00 UTC'))->getTimestamp();
foreach (DateTimeZone::listIdentifiers(DateTimeZone::ALL_WITH_BC) as $id) {
    $transitions = (new DateTimeZone($id))->getTransitions(PHP_INT_MIN, $end);
    if ($transitions === false) {
        // a name that is also an abbreviation (CET, EST, ...) makes an abbreviation zone, not an identifier one
        continue;
    }
    $eras = [];
    $current = null;
    foreach ($transitions as $i => $t) {
        $value = $t['offset'] . ':' . ($t['isdst'] ? 1 : 0) . ':' . $t['abbr'];
        if ($current === null || (isset($current[$t['offset']]) && $current[$t['offset']] !== $value)) {
            if ($current !== null) {
                $eras[] = $start . '=' . implode(',', $current);
            }
            $current = [];
            $start = $i === 0 ? PHP_INT_MIN : $t['ts'];
        }
        $current[$t['offset']] = $value;
    }
    $eras[] = $start . '=' . implode(',', $current);
    $out[] = "zone $id " . implode(' ', $eras);
}
file_put_contents($argv[1], implode("\n", $out) . "\n");
echo count($out), " lines, ", strlen(implode("\n", $out)), " bytes\n";
