<?php
// The date classes (prelude/date.php) on fixed instants: zones, formats, arithmetic, parsing.
date_default_timezone_set('UTC');
$utc = new DateTimeZone('UTC');
$warsaw = new DateTimeZone('Europe/Warsaw');
$d = new DateTimeImmutable('2024-03-30 22:30:00', $utc);
echo $d->format('Y-m-d H:i:s T P U'), "\n";
$w = $d->setTimezone($warsaw);
echo $w->format('Y-m-d H:i:s e O'), ' ', $w->getOffset(), ' ', $warsaw->getName(), ' ', $warsaw->getOffset($d), "\n";
echo $w->modify('+1 day')->format(DATE_ATOM), ' ', $w->format(DateTimeInterface::RFC2822), "\n";
echo $d->add(new DateInterval('P1M2DT3H'))->format('c'), ' ', $d->sub(new DateInterval('PT90M'))->format('c'), "\n";
$m = new DateTime('2024-01-31 10:00:00', $utc);
$m->setDate(2024, 2, 30)->setTime(5, 6, 7);
echo $m->format('Y-m-d H:i:s'), "\n";
$a = new DateTime('2020-02-29 12:00:00', $utc);
$b = new DateTime('2024-03-01 08:30:15', $utc);
$diff = $a->diff($b);
echo $diff->format('%y %m %d %h %i %s %a %R'), ' ', $b->diff($a)->format('%R%a'), "\n";
$f = DateTimeImmutable::createFromFormat('Y-m-d H:i:s', '2023-12-25 18:45:00', $warsaw);
echo $f->format('c U'), ' ', var_export(DateTimeImmutable::createFromFormat('Y-m-d', 'nope'), true), "\n";
$u = date_create_immutable_from_format('U', '1700000000');
echo $u->format('Y-m-d H:i:s e'), ' ', date_create('@86400')->format('Y-m-d e'), "\n";
echo (new DateTimeImmutable('2024-06-01T12:00:00+02:00'))->format('c e'), "\n";
echo (new DateTimeImmutable('2024-06-01 12:00:00.250000', $utc))->format('Y-m-d H:i:s.u v'), "\n";
var_dump(@timezone_open('Nowhere/City'), $d instanceof DateTimeInterface);
echo (new DateInterval('P2W'))->format('%d days'), ' ', get_class(new DateTime('@0')), "\n";
