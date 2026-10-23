<?php
// Every date format character (php_date.c's date_format) for zones given by identifier, by abbreviation and by
// offset, in DateTime::format, date() and gmdate(): T, e, I, O, P, p, Z across DST, historic (LMT) and half-hour
// zones, years before 1000 and after 9999, escapes and bytes outside ASCII in the format.
$all = 'd D j l N S w z W F m M n t L o X x Y y a A B g G h H i s u v e I O P p T Z c r U';
$chars = explode(' ', $all);
$zones = ['UTC', 'Europe/Warsaw', 'europe/warsaw', 'America/New_York', 'Asia/Kolkata', 'America/Sao_Paulo',
    'Europe/Dublin', 'Europe/London', 'Australia/Lord_Howe', 'Asia/Kathmandu', 'Pacific/Chatham',
    'America/St_Johns', 'Africa/Casablanca', 'Asia/Dubai', 'Pacific/Apia', 'Antarctica/Troll', 'US/Eastern',
    'CEST', 'cest', 'CET', 'EST', 'EDT', 'PST', 'Z', 'z', 'GMT', 'gmt', 'utc', 'BST', 'IST', 'A', 'MSK',
    '+02:00', '-05:30', '+0000', '-00:00', '+14:00', '-12:00', '+5', '-0930', 'GMT+0300', '+01:30:45'];
$moments = [0, 1719835200, 1704067200, 1711846800, 1711850400, 1729994400, -2208988800, -1000000000, 951782400,
    253402300799, -62135596800];
foreach ($zones as $name) {
    try {
        $zone = new DateTimeZone($name);
    } catch (Exception $e) {
        echo "$name: ", get_class($e), "\n";
        continue;
    }
    echo "== $name -> ", $zone->getName(), "\n";
    foreach ($moments as $ts) {
        if ($name === 'Africa/Casablanca' && $ts > 4e9) {
            // java.time's rules for Casablanca after 2087 (+01) differ from timelib's (+00)
            continue;
        }
        $d = (new DateTimeImmutable('@' . $ts))->setTimezone($zone);
        $line = [];
        foreach ($chars as $c) {
            $line[] = $d->format($c);
        }
        echo implode('|', $line), "\n";
    }
}
foreach (['2024-06-01T12:00:00Z', '2024-06-01T12:00:00+02:00', '2024-06-01 12:00:00.123456', '@1700000000',
             '2024-01-15T08:30:00-05:00'] as $text) {
    $d = new DateTimeImmutable($text);
    echo $text, ' => ', $d->format('Y-m-d H:i:s.u v e T P p O Z I U'), ' ', $d->getTimezone()->getName(), "\n";
}
foreach (['UTC', 'Europe/Warsaw', 'America/New_York', 'Asia/Kathmandu'] as $default) {
    date_default_timezone_set($default);
    foreach ([0, 1719835200, 1704067200, -1000000000] as $ts) {
        echo $default, ' ', date($all, $ts), "\n", 'gm ', gmdate($all, $ts), "\n";
    }
}
date_default_timezone_set('UTC');
echo date('\\Y\\m\\d \\\\ \\'), '|', bin2hex(date('Y\\')), '|', date('\Y-m-d \a\t H:i', 86400), "\n";
echo bin2hex(date("Y\xff\xc3\xa9-m", 0)), ' ', date('', 0), '|', date('[]{}()!@#$%^&*~`;:"<>,.?/', 0), "\n";
foreach (['ATOM', 'COOKIE', 'ISO8601', 'ISO8601_EXPANDED', 'RFC822', 'RFC850', 'RFC1036', 'RFC1123', 'RFC2822',
             'RFC3339', 'RFC3339_EXTENDED', 'RSS', 'W3C'] as $constant) {
    echo date(constant("DateTimeInterface::$constant"), 1000000000), ' | ';
}
echo "\n";
$d = new DateTime('2024-03-31 01:59:59', new DateTimeZone('Europe/Warsaw'));
echo $d->format('c T I U'), ' ';
$d->modify('+1 second');
echo $d->format('c T I U'), "\n";
foreach ([1, 999, 1000, 9999, 10000, 12345] as $year) {
    $y = (new DateTimeImmutable('2000-06-15 10:00:00', new DateTimeZone('UTC')))->setDate($year, 6, 15);
    echo $y->format('Y y x X o c r'), "\n";
}
foreach (timezone_identifiers_list() as $i => $id) {
    if ($i % 40 === 0) {
        echo $id, ' ';
    }
}
echo count(timezone_identifiers_list()), "\n";
