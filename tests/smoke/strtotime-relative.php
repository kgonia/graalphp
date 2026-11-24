<?php
// Relative date formats as timelib parses them (strtotime, DateTime::modify): the keywords, a time of day after or
// before them, relative units (with "ago"), next/last/previous/this with a unit or a weekday, weekdays,
// "first/last day of", "last week monday". The formats WordPress, WooCommerce (Action Scheduler: "tomorrow 3am"),
// Yoast SEO and Elementor pass, and their neighbours. A string timelib can't parse is false.

$formats = [
    'now', 'today', 'midnight', 'noon', 'tomorrow', 'yesterday', 'tomorrow midnight', 'midnight tonight',
    'tomorrow 3am', 'tomorrow 3 am', 'tomorrow 06:00 am', 'tomorrow 03:00 am ', '00:00 tomorrow', 'noon tomorrow',
    '3am tomorrow', 'today 3pm', 'yesterday 11:30pm', 'tomorrow noon', '3am', '3:15pm', '12am', '12pm', '12:30 a.m.',
    '15:04', '15:04:05', 'today 23:59:59', 'tomorrow 24:00', '25:00', '13pm', 'noon 3pm',
    '+1 day', '-1 day', '-2weeks', '+1 month', '-1 year', '-1 hours', '-90 days', '+1 week 2 days', '2 days ago',
    '+1 day 2 hours ago', '1 fortnight', '-10 minutes', '+30 sec', '+1 day 3am', 'midnight -3 days', '+1 days midnight',
    'next month', 'previous month', 'next day', 'previous day', 'last year', 'this month', 'next week',
    'next monday', 'last monday', 'this monday', 'this sunday', 'monday', 'tuesday', 'sunday', 'next monday 9am',
    'last week monday', 'monday next week', 'sunday this week', 'first monday',
    'first day of next month', 'last day of this month', 'last day of previous month', 'first day of previous month',
    'first day of', 'last day of next month midnight',
    'tonight', 'later', '+', 'tomorrow tomorrow', '3am 4am', 'next', '',
];
$bases = ['2026-11-24 15:04:05', '2026-01-31 08:00:00', '2026-11-29 10:00:00'];
foreach (['UTC', 'Europe/Warsaw'] as $zone) {
    date_default_timezone_set($zone);
    foreach ($bases as $base) {
        $at = strtotime($base);
        echo "== $zone $base\n";
        foreach ($formats as $f) {
            $t = strtotime($f, $at);
            try {
                $m = (new DateTimeImmutable($base))->modify($f)->format('Y-m-d H:i:s');
            } catch (Exception $e) {
                // the message names timelib's error; the class is what callers catch
                $m = get_class($e);
            }
            echo str_pad(var_export($f, true), 34), ' ', $t === false ? 'false' : date('Y-m-d H:i:s', $t), ' | ', $m,
                "\n";
        }
    }
}

// a time of day set by the modifier drops the microseconds; date_modify() warns and returns false
date_default_timezone_set('UTC');
$d = new DateTime('2026-11-24 15:04:05.123456');
var_dump(@date_modify($d, 'nonsense'));
echo error_get_last()['message'], "\n";
echo date_modify($d, '+1 day')->format('Y-m-d H:i:s.u'), "\n";
echo $d->modify('tomorrow 3am')->format('Y-m-d H:i:s.u'), "\n";
$i = new DateTimeImmutable('2026-11-24 15:04:05.5');
foreach (['+1 hour', 'noon', 'next monday', 'first day of next month', '+90 min'] as $f) {
    echo $f, ': ', $i->modify($f)->format('Y-m-d H:i:s.u'), "\n";
}
try {
    $i->modify('nonsense')->format('U');
} catch (DateMalformedStringException $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}

// wall-clock arithmetic, the offset resolved last: an overlap takes the earlier offset, a gap moves forward
date_default_timezone_set('Europe/Warsaw');
foreach (['2026-10-25 01:30:00', '2026-03-29 01:30:00', '2026-03-28 02:30:00'] as $b) {
    $base = new DateTimeImmutable($b);
    foreach (['+1 hour', '+30 minutes', '+90 min', '-1 hour', '+1 day', 'tomorrow 2:30am', '02:30'] as $f) {
        echo "$b $f: ", $base->modify($f)->format('Y-m-d H:i:s T'), ' ',
            date('Y-m-d H:i:s T', strtotime($f, $base->getTimestamp())), "\n";
    }
}
// an ambiguous wall time given whole takes the later (standard) offset, as strtotime does
foreach (['2026-10-25 02:30:00', '2026-10-25 02:59:59', '2026-03-29 02:30:00'] as $b) {
    echo $b, ': ', (new DateTimeImmutable($b))->format('Y-m-d H:i:s T'), ' ', date('Y-m-d H:i:s T', strtotime($b)), "\n";
}
