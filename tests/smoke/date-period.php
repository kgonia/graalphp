<?php
// DatePeriod (WooCommerce's orders list builds its month filter with one)
date_default_timezone_set('UTC');
class MyDate extends DateTime { public $tag = 'default'; }
$start = new MyDate('2026-01-15 10:30:00');
$start->tag = 'changed';
$end = new MyDate('2026-05-01 00:00:00');
foreach (new DatePeriod($start, new DateInterval('P1M'), $end) as $i => $date) {
    echo $i, ' ', get_class($date), ' ', $date->format('Y-m-d H:i'), "\n";
}
$immutable = new DateTimeImmutable('2026-03-01');
foreach (new DatePeriod($immutable, new DateInterval('P1D'), 3, DatePeriod::EXCLUDE_START_DATE) as $date) {
    echo get_class($date), ' ', $date->format('D Y-m-d'), "\n";
}
$period = new DatePeriod($immutable, new DateInterval('PT12H'), new DateTimeImmutable('2026-03-02'),
    DatePeriod::INCLUDE_END_DATE);
foreach ($period as $date) {
    echo $date->format('Y-m-d H:i'), "\n";
}
$mine = new DatePeriod($start, new DateInterval('P1D'), 1);
var_dump(get_class($mine->start), $mine->start->tag, get_class($mine->getStartDate()));
var_dump($period->getRecurrences(), $period->getStartDate()->format('c'), $period->getEndDate()->format('c'),
    $period->getDateInterval()->h, $period->include_start_date, $period->include_end_date);
$counted = new DatePeriod($immutable, new DateInterval('P1W'), 2);
var_dump($counted->getRecurrences(), $counted->getEndDate(), $counted->recurrences);
try {
    new DatePeriod($immutable, new DateInterval('P1D'), 0);
} catch (Exception $e) {
    echo get_class($e), ': ', $e->getMessage(), "\n";
}
var_dump(new DateMalformedStringException('x') instanceof DateException, new DateRangeError() instanceof Error);
