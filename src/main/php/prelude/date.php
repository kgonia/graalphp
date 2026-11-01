<?php
// ext/date's classes, written in PHP: Truffle PHP's prelude (see spl.php). A date is an instant in whole seconds
// plus microseconds and a timezone name; the calendar work is java.time's, behind the internal functions in
// namespace Truffle (builtins/DateFunctions.java).

interface DateTimeInterface
{
    const ATOM = "Y-m-d\\TH:i:sP";
    const COOKIE = "l, d-M-Y H:i:s T";
    const ISO8601 = "Y-m-d\\TH:i:sO";
    const ISO8601_EXPANDED = "X-m-d\\TH:i:sP";
    const RFC822 = "D, d M y H:i:s O";
    const RFC850 = "l, d-M-y H:i:s T";
    const RFC1036 = "D, d M y H:i:s O";
    const RFC1123 = "D, d M Y H:i:s O";
    const RFC7231 = "D, d M Y H:i:s \\G\\M\\T";
    const RFC2822 = "D, d M Y H:i:s O";
    const RFC3339 = "Y-m-d\\TH:i:sP";
    const RFC3339_EXTENDED = "Y-m-d\\TH:i:s.vP";
    const RSS = "D, d M Y H:i:s O";
    const W3C = "Y-m-d\\TH:i:sP";

    public function format(string $format): string;

    public function getTimezone(): DateTimeZone|false;

    public function getOffset(): int;

    public function getTimestamp(): int;

    public function diff(DateTimeInterface $targetObject, bool $absolute = false): DateInterval;
}

class DateTimeZone
{
    const AFRICA = 1;
    const AMERICA = 2;
    const ANTARCTICA = 4;
    const ARCTIC = 8;
    const ASIA = 16;
    const ATLANTIC = 32;
    const AUSTRALIA = 64;
    const EUROPE = 128;
    const INDIAN = 256;
    const PACIFIC = 512;
    const UTC = 1024;
    const ALL = 2047;
    const ALL_WITH_BC = 4095;
    const PER_COUNTRY = 4096;

    private $name;

    public function __construct(string $timezone)
    {
        $name = \Truffle\timezone_name($timezone);
        if ($name === false) {
            throw new Exception("DateTimeZone::__construct(): Unknown or bad timezone ($timezone)");
        }
        $this->name = $name;
    }

    public function getName(): string
    {
        return $this->name;
    }

    public function getOffset(DateTimeInterface $datetime): int
    {
        return \Truffle\timezone_offset($this->name, $datetime->getTimestamp());
    }

    public static function listIdentifiers(int $timezoneGroup = DateTimeZone::ALL, ?string $countryCode = null): array
    {
        return timezone_identifiers_list();
    }
}

/** What DateTime and DateTimeImmutable share; with() is where they differ (a copy, or this object changed). */
trait TruffleDateTime
{
    private $timestamp = 0;
    private $microseconds = 0;
    private $zone = 'UTC';

    public function __construct(string $datetime = "now", ?DateTimeZone $timezone = null)
    {
        $zone = $timezone !== null ? $timezone->getName() : date_default_timezone_get();
        $trimmed = trim($datetime);
        if (preg_match('/(?:\d|\s)(Z|[+-]\d\d:?\d\d)$/', $trimmed, $match)) {
            $zone = \Truffle\timezone_name($match[1]);
        } elseif (preg_match('/^@-?\d+$/', $trimmed)) {
            $zone = '+00:00';
        }
        $micro = 0;
        if ($trimmed === '' || strtolower($trimmed) === 'now') {
            $now = microtime(true);
            $timestamp = (int) floor($now);
            $micro = (int) round(($now - $timestamp) * 1000000);
        } else {
            if (preg_match('/\.(\d{1,6})(?=\D*$|Z|[+-]\d)/', $trimmed, $fraction)) {
                $micro = (int) str_pad($fraction[1], 6, '0');
                $trimmed = str_replace($fraction[0], '', $trimmed);
            }
            $timestamp = \Truffle\date_parse($trimmed, time(), $zone);
            if ($timestamp === false) {
                throw new Exception("Failed to parse time string ($datetime) at position 0 (" . substr($datetime, 0, 1)
                    . "): The timezone could not be found in the database");
            }
        }
        $this->timestamp = $timestamp;
        $this->microseconds = $micro;
        $this->zone = $zone;
    }

    public function format(string $format): string
    {
        return \Truffle\date_format($format, $this->timestamp, $this->zone, $this->microseconds);
    }

    public function getTimestamp(): int
    {
        return $this->timestamp;
    }

    public function getMicrosecond(): int
    {
        return $this->microseconds;
    }

    public function getTimezone(): DateTimeZone|false
    {
        return new DateTimeZone($this->zone);
    }

    public function getOffset(): int
    {
        return \Truffle\timezone_offset($this->zone, $this->timestamp);
    }

    public function setTimezone(DateTimeZone $timezone): static
    {
        return $this->with($this->timestamp, $this->microseconds, $timezone->getName());
    }

    public function setTimestamp(int $timestamp): static
    {
        return $this->with($timestamp, 0, $this->zone);
    }

    public function setDate(int $year, int $month, int $day): static
    {
        return $this->with(\Truffle\date_set($this->timestamp, $this->zone, $year, $month, $day), $this->microseconds,
            $this->zone);
    }

    public function setTime(int $hour, int $minute, int $second = 0, int $microsecond = 0): static
    {
        return $this->with(\Truffle\date_set($this->timestamp, $this->zone, null, null, null, $hour, $minute, $second),
            $microsecond, $this->zone);
    }

    public function modify(string $modifier): static|false
    {
        $timestamp = \Truffle\date_parse($modifier, $this->timestamp, $this->zone);
        if ($timestamp === false) {
            return false;
        }
        return $this->with($timestamp, $this->microseconds, $this->zone);
    }

    public function add(DateInterval $interval): static
    {
        return $this->shift($interval, $interval->invert ? -1 : 1);
    }

    public function sub(DateInterval $interval): static
    {
        return $this->shift($interval, $interval->invert ? 1 : -1);
    }

    private function shift(DateInterval $interval, int $sign): static
    {
        return $this->with(\Truffle\date_add($this->timestamp, $this->zone, $interval->y, $interval->m, $interval->d,
            $interval->h, $interval->i, $interval->s, $sign), $this->microseconds, $this->zone);
    }

    public function diff(DateTimeInterface $targetObject, bool $absolute = false): DateInterval
    {
        $parts = \Truffle\date_diff($this->timestamp, $targetObject->getTimestamp(), $this->zone);
        $interval = new DateInterval('PT0S');
        [$interval->y, $interval->m, $interval->d, $interval->h, $interval->i, $interval->s] = $parts;
        $interval->invert = $absolute ? 0 : $parts[6];
        $interval->days = $parts[7];
        return $interval;
    }

    public static function createFromFormat(string $format, string $datetime, ?DateTimeZone $timezone = null): static|false
    {
        $zone = $timezone !== null ? $timezone->getName() : date_default_timezone_get();
        $timestamp = \Truffle\date_from_format($format, $datetime, $zone);
        if ($timestamp === false) {
            return false;
        }
        $date = new static('@' . $timestamp);
        return $date->with($timestamp, 0, str_contains($format, 'U') ? '+00:00' : $zone);
    }

    public static function createFromInterface(DateTimeInterface $object): static
    {
        $date = new static('@' . $object->getTimestamp());
        return $date->with($object->getTimestamp(), 0, $object->getTimezone()->getName());
    }

    public function __serialize(): array
    {
        return ['date' => $this->format('Y-m-d H:i:s.u'), 'timezone_type' => 3, 'timezone' => $this->zone];
    }
}

class DateTimeImmutable implements DateTimeInterface
{
    use TruffleDateTime;

    private function with(int $timestamp, int $microseconds, string $zone): static
    {
        $copy = clone $this;
        $copy->timestamp = $timestamp;
        $copy->microseconds = $microseconds;
        $copy->zone = $zone;
        return $copy;
    }

    public static function createFromMutable(DateTime $object): static
    {
        return static::createFromInterface($object);
    }
}

class DateTime implements DateTimeInterface
{
    use TruffleDateTime;

    private function with(int $timestamp, int $microseconds, string $zone): static
    {
        $this->timestamp = $timestamp;
        $this->microseconds = $microseconds;
        $this->zone = $zone;
        return $this;
    }

    public static function createFromImmutable(DateTimeImmutable $object): static
    {
        return static::createFromInterface($object);
    }
}

class DateInterval
{
    public $y = 0;
    public $m = 0;
    public $d = 0;
    public $h = 0;
    public $i = 0;
    public $s = 0;
    public $f = 0;
    public $invert = 0;
    public $days = false;

    /** An ISO 8601 duration: P1Y2M3D, PT4H5M6S, P2W. */
    public function __construct(string $duration)
    {
        if (!preg_match('/^P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/',
            $duration, $m) || $duration === 'P' || $duration === 'PT') {
            throw new Exception("DateInterval::__construct(): Unknown or bad format ($duration)");
        }
        $this->y = (int) ($m[1] ?? 0);
        $this->m = (int) ($m[2] ?? 0);
        $this->d = (int) ($m[4] ?? 0) + 7 * (int) ($m[3] ?? 0);
        $this->h = (int) ($m[5] ?? 0);
        $this->i = (int) ($m[6] ?? 0);
        $this->s = (int) ($m[7] ?? 0);
    }

    public function format(string $format): string
    {
        $values = [
            'Y' => sprintf('%04d', $this->y), 'y' => $this->y, 'M' => sprintf('%02d', $this->m), 'm' => $this->m,
            'D' => sprintf('%02d', $this->d), 'd' => $this->d, 'H' => sprintf('%02d', $this->h), 'h' => $this->h,
            'I' => sprintf('%02d', $this->i), 'i' => $this->i, 'S' => sprintf('%02d', $this->s), 's' => $this->s,
            'F' => sprintf('%06d', $this->f * 1000000), 'f' => (int) ($this->f * 1000000),
            'a' => $this->days === false ? '(unknown)' : $this->days,
            'R' => $this->invert ? '-' : '+', 'r' => $this->invert ? '-' : '', '%' => '%',
        ];
        return preg_replace_callback('/%(.)/', function ($m) use ($values) {
            return array_key_exists($m[1], $values) ? (string) $values[$m[1]] : $m[0];
        }, $format);
    }

    public static function createFromDateString(string $datetime): DateInterval|false
    {
        $base = 86400 * 365 * 30;
        $timestamp = \Truffle\date_parse($datetime, $base, 'UTC');
        if ($timestamp === false) {
            return false;
        }
        $interval = new DateInterval('PT0S');
        $seconds = abs($timestamp - $base);
        $interval->d = intdiv($seconds, 86400);
        $interval->h = intdiv($seconds % 86400, 3600);
        $interval->i = intdiv($seconds % 3600, 60);
        $interval->s = $seconds % 60;
        $interval->invert = $timestamp < $base ? 1 : 0;
        return $interval;
    }
}

/**
 * The dates from a start, an interval apart, up to an end or for a number of recurrences (ext/date's
 * date_period_it_*): each date is a new DateTime or DateTimeImmutable, as the start is one; the start, end and
 * current properties and getters are of the start's own class.
 */
class DatePeriod implements IteratorAggregate
{
    const EXCLUDE_START_DATE = 1;
    const INCLUDE_END_DATE = 2;

    public $start = null;
    public $current = null;
    public $end = null;
    public $interval = null;
    public $recurrences = 1;
    public $include_start_date = true;
    public $include_end_date = false;

    public function __construct($start, $interval = null, $end = null, $options = 0)
    {
        if (!($start instanceof DateTimeInterface) || !($interval instanceof DateInterval)
            || !($end instanceof DateTimeInterface || is_int($end))) {
            throw new TypeError('DatePeriod::__construct() accepts (DateTimeInterface, DateInterval, int [, int]), '
                . 'or (DateTimeInterface, DateInterval, DateTime [, int]), or (string [, int]) as arguments');
        }
        $recurrences = is_int($end) ? $end : 0;
        if (!is_int($end)) {
            $this->end = self::made(get_class($start), $end);
        } elseif ($recurrences < 1 || $recurrences > 2147483639) {
            throw new DateMalformedPeriodStringException('DatePeriod::__construct(): Recurrence count must be greater '
                . 'or equal to 1 and lower than 2147483640');
        }
        $this->start = self::made(get_class($start), $start);
        $this->interval = clone $interval;
        $this->include_start_date = !($options & self::EXCLUDE_START_DATE);
        $this->include_end_date = (bool) ($options & self::INCLUDE_END_DATE);
        $this->recurrences = $recurrences + (int) $this->include_start_date + (int) $this->include_end_date;
    }

    /** create_date_period_datetime: an object of the class, made without its constructor, at the date's time. */
    private static function made(string $class, DateTimeInterface $date): DateTimeInterface
    {
        $made = \Truffle\reflection_new($class);
        $made = $made->setTimezone($date->getTimezone())->setTimestamp($date->getTimestamp());
        if ($date->getMicrosecond() !== 0) {
            $made = $made->setTime((int) $made->format('G'), (int) $made->format('i'), (int) $made->format('s'),
                $date->getMicrosecond());
        }
        return $made;
    }

    public function getIterator(): Iterator
    {
        $current = DateTimeImmutable::createFromInterface($this->start)->setTime((int) $this->start->format('G'),
            (int) $this->start->format('i'), (int) $this->start->format('s'), $this->start->getMicrosecond());
        if (!$this->include_start_date) {
            $current = $current->add($this->interval);
        }
        for ($index = 0; $this->hasMore($current, $index); $index++) {
            $this->current = self::made(get_class($this->start), $current);
            yield $index => self::made($this->start instanceof DateTimeImmutable ? DateTimeImmutable::class
                : DateTime::class, $current);
            $current = $current->add($this->interval);
        }
    }

    private function hasMore(DateTimeInterface $current, int $index): bool
    {
        if ($this->end === null) {
            return $index < $this->recurrences;
        }
        if ($current->getTimestamp() === $this->end->getTimestamp()) {
            return $this->include_end_date ? $current->getMicrosecond() <= $this->end->getMicrosecond()
                : $current->getMicrosecond() < $this->end->getMicrosecond();
        }
        return $current->getTimestamp() < $this->end->getTimestamp();
    }

    public function getStartDate(): DateTimeInterface
    {
        return self::made(get_class($this->start), $this->start);
    }

    public function getEndDate(): ?DateTimeInterface
    {
        return $this->end === null ? null : self::made(get_class($this->start), $this->end);
    }

    public function getDateInterval(): DateInterval
    {
        return clone $this->interval;
    }

    public function getRecurrences(): ?int
    {
        return $this->end === null ? $this->recurrences - (int) $this->include_start_date
            - (int) $this->include_end_date : null;
    }
}

class DateException extends Exception
{
}

class DateInvalidTimeZoneException extends DateException
{
}

class DateInvalidOperationException extends DateException
{
}

class DateMalformedStringException extends DateException
{
}

class DateMalformedIntervalStringException extends DateException
{
}

class DateMalformedPeriodStringException extends DateException
{
}

class DateError extends Error
{
}

class DateObjectError extends DateError
{
}

class DateRangeError extends DateError
{
}
