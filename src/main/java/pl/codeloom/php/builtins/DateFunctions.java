package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpThrowable;
import pl.codeloom.php.runtime.Str;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dates and times: date() / gmdate() with PHP's format characters, mktime(), strtotime() for the formats
 * WordPress produces and parses, and the default timezone (UTC unless set, as php.ini's date.timezone default).
 */
final class DateFunctions {
    private DateFunctions() {
    }

    static void register(Registry r) {
        r.fn("date", (context, a) -> format(Args.str(a, 0), timestamp(a, 1), 0, contextZone(context), true));
        r.fn("gmdate", (context, a) -> format(Args.str(a, 0), timestamp(a, 1), 0, contextZone(context), false));
        r.fn("mktime", (context, a) -> mktime(a, zone(context)));
        r.fn("gmmktime", a -> mktime(a, ZoneOffset.UTC));
        r.fn("checkdate", a -> checkdate(Args.lng(a, 0, 0), Args.lng(a, 1, 0), Args.lng(a, 2, 0)));
        r.fn("strtotime", (context, a) -> {
            Long base = Args.has(a, 1) && Args.get(a, 1) != PhpNull.NULL ? Args.lng(a, 1, 0) : null;
            Long result = strtotime(Str.java(Args.str(a, 0)), base == null ? now() : base, zone(context));
            return result == null ? (Object) Boolean.FALSE : result;
        });
        r.fn("date_default_timezone_set", (context, a) -> {
            try {
                context.setTimezone(ZoneId.of(Str.java(Args.str(a, 0))));
                return Boolean.TRUE;
            } catch (DateTimeException e) {
                return Boolean.FALSE;
            }
        });
        r.fn("date_default_timezone_get", (context, a) -> Str.of(zone(context).getId()));
        r.fn("timezone_identifiers_list", a -> identifiers());

        // ---- the prelude's date classes (src/main/php/prelude/date.php): instants in seconds, zones by name ----
        // ---- the procedural API over the date classes ----
        r.fn("date_create", (context, a) -> construct(context, "DateTime", a));
        r.fn("date_create_immutable", (context, a) -> construct(context, "DateTimeImmutable", a));
        r.fn("date_create_from_format", (context, a) -> staticCall(context, "DateTime", "createfromformat", a));
        r.fn("date_create_immutable_from_format", (context, a) -> staticCall(context, "DateTimeImmutable",
                "createfromformat", a));
        r.fn("timezone_open", (context, a) -> construct(context, "DateTimeZone", a));
        r.fn("date_format", a -> method(a, "format"));
        r.fn("date_timestamp_get", a -> method(a, "gettimestamp"));
        r.fn("date_timezone_get", a -> method(a, "gettimezone"));
        r.fn("date_offset_get", a -> method(a, "getoffset"));
        r.fn("date_timezone_set", a -> method(a, "settimezone"));
        r.fn("date_modify", (context, a) -> {
            try {
                return method(a, "modify");
            } catch (PhpThrowable e) {
                // PHP 8.3: the method throws, the function warns with the same reason and returns false
                PhpObject thrown = e.toThrowable(context);
                if (!thrown.getPhpClass().getLowerName().equals("datemalformedstringexception")) {
                    throw e;
                }
                String message = Str.java((TruffleString) thrown.getProperty("message"));
                ErrorFunctions.raise(context, ErrorFunctions.E_WARNING,
                        Str.of("date_modify(): " + message.substring(message.indexOf("::modify(): ") + 12)),
                        pl.codeloom.php.runtime.SourceLocation.current());
                return Boolean.FALSE;
            }
        });
        r.fn("date_add", a -> method(a, "add"));
        r.fn("date_sub", a -> method(a, "sub"));
        r.fn("date_diff", a -> method(a, "diff"));
        r.fn("timezone_name_get", a -> method(a, "getname"));
        r.fn("timezone_offset_get", a -> method(a, "getoffset"));

        r.fn("truffle\\timezone_name", a -> {
            PhpZone zone = parseZone(Str.java(Args.str(a, 0)));
            return zone == null ? (Object) Boolean.FALSE : Str.of(zone.name());
        });
        r.fn("truffle\\timezone_offset", a -> (long) zoneOf(a, 0).getRules()
                .getOffset(Instant.ofEpochSecond(Args.lng(a, 1, 0))).getTotalSeconds());
        // date_format(format, timestamp, zone, microseconds)
        r.fn("truffle\\date_format", a -> {
            PhpZone zone = parseZone(Str.java(Args.str(a, 2)));
            return format(Args.str(a, 0), Args.lng(a, 1, 0), (int) Args.lng(a, 3, 0),
                    zone == null ? parseZone("UTC") : zone, true);
        });
        // date_modify(modifier, timestamp, zone): [timestamp, whether the time of day was set], or false
        r.fn("truffle\\date_modify", a -> {
            Parsed parsed = parseForModify(Str.java(Args.str(a, 0)), Args.lng(a, 1, 0), zoneOf(a, 2));
            return parsed == null ? (Object) Boolean.FALSE
                    : PhpArray.list(new Object[] {parsed.epoch(), parsed.timeSet()});
        });
        r.fn("truffle\\date_parse", a -> {
            Long result = strtotime(Str.java(Args.str(a, 0)), Args.lng(a, 1, 0), zoneOf(a, 2));
            return result == null ? (Object) Boolean.FALSE : result;
        });
        r.fn("truffle\\date_from_format", a -> fromFormat(Str.java(Args.str(a, 0)), Str.java(Args.str(a, 1)),
                zoneOf(a, 2)));
        r.fn("truffle\\date_set", a -> set(Args.lng(a, 0, 0), zoneOf(a, 1), a));
        r.fn("truffle\\date_add", a -> add(Args.lng(a, 0, 0), zoneOf(a, 1), a));
        r.fn("truffle\\date_diff", a -> diff(Args.lng(a, 0, 0), Args.lng(a, 1, 0), zoneOf(a, 2)));
    }

    /** new Class(...args), or false if the constructor throws (date_create() and timezone_open() don't throw). */
    @TruffleBoundary
    private static Object construct(PhpContext context, String className, Object[] a) {
        PhpObject object = context.getClass(className).instantiate();
        // held while the constructor runs, as `new`'s temporary holds it
        object.addRef();
        try {
            pl.codeloom.php.runtime.Refcount.done(object.callMethod("__construct", arguments(a, 0)));
        } catch (PhpThrowable e) {
            object.release();
            return Boolean.FALSE;
        }
        object.disown();
        return object;
    }

    @TruffleBoundary
    private static Object staticCall(PhpContext context, String className, String method, Object[] a) {
        PhpClass phpClass = context.getClass(className);
        return phpClass.findMethod(method).invoke(null, phpClass, arguments(a, 0));
    }

    /** $object->method(...rest) for the procedural forms: date_format($date, $format) is $date->format($format). */
    @TruffleBoundary
    private static Object method(Object[] a, String method) {
        if (!(Args.get(a, 0) instanceof PhpObject object)) {
            throw new PhpError("TypeError", "Argument #1 must be a date object");
        }
        return object.callMethod(method, arguments(a, 1));
    }

    private static Object[] arguments(Object[] a, int from) {
        Object[] values = new Object[Math.max(0, Args.count(a) - from)];
        for (int i = 0; i < values.length; i++) {
            values[i] = Args.get(a, from + i);
        }
        return values;
    }

    // ---- timezones ----

    /** timelib's zone types. */
    static final int ZONETYPE_OFFSET = 1;
    static final int ZONETYPE_ABBR = 2;
    static final int ZONETYPE_ID = 3;

    /**
     * A PHP timezone: an identifier (rules from java.time), a UTC offset, or an abbreviation (its standard offset
     * plus an hour when dst). name is DateTimeZone::getName(): the identifier, "+02:00", or the abbreviation in
     * upper case.
     */
    record PhpZone(int type, String name, ZoneId rules, int offset, boolean dst) {
        /** The java.time zone the date arithmetic runs in. */
        ZoneId zoneId() {
            return rules;
        }
    }

    /**
     * What tz-tables.txt (tools/tz-tables.php, from PHP's timelib data) holds: the abbreviations PHP accepts as
     * zones, the identifiers it lists, and per identifier the eras that give a UTC offset its abbreviation and
     * DST flag.
     */
    private static final class TzTables {
        /** Lower-case abbreviation to {offset, dst}: timelib_timezone_lookup's first entry for the name. */
        static final Map<String, int[]> ABBREVIATIONS = new HashMap<>();
        static final List<String> IDENTIFIERS = new ArrayList<>();
        /** Lower-case identifier to its eras. */
        static final Map<String, Era[]> ZONES = new HashMap<>();
        /** Lower-case identifier to its spelling. */
        static final Map<String, String> CANONICAL = new HashMap<>();

        static {
            String text = new String(StringFunctions.resource("tz-tables.txt"), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                String[] fields = line.split(" ");
                switch (fields[0]) {
                    case "abbr" -> ABBREVIATIONS.put(fields[1],
                            new int[]{Integer.parseInt(fields[2]), Integer.parseInt(fields[3])});
                    case "ids" -> IDENTIFIERS.addAll(Arrays.asList(fields).subList(1, fields.length));
                    case "zone" -> {
                        Era[] eras = new Era[fields.length - 2];
                        for (int i = 2; i < fields.length; i++) {
                            int eq = fields[i].indexOf('=');
                            String[] entries = fields[i].substring(eq + 1).split(",");
                            int[] offsets = new int[entries.length];
                            boolean[] dsts = new boolean[entries.length];
                            String[] abbreviations = new String[entries.length];
                            for (int j = 0; j < entries.length; j++) {
                                String[] parts = entries[j].split(":", 3);
                                offsets[j] = Integer.parseInt(parts[0]);
                                dsts[j] = parts[1].equals("1");
                                abbreviations[j] = parts[2];
                            }
                            eras[i - 2] = new Era(Long.parseLong(fields[i].substring(0, eq)), offsets, dsts,
                                    abbreviations);
                        }
                        ZONES.put(Str.lower(fields[1]), eras);
                        CANONICAL.put(Str.lower(fields[1]), fields[1]);
                    }
                    default -> {
                    }
                }
            }
        }
    }

    /** From start until the next era: a moment at offsets[i] has the DST flag dsts[i] and abbreviations[i]. */
    private record Era(long start, int[] offsets, boolean[] dsts, String[] abbreviations) {
    }

    /**
     * A parsed zone per zone name: the prelude passes names, and formats a date with the same zone again. Shared by
     * every context, and the names are any strings scripts pass, so it stops growing at ZONE_CACHE_LIMIT names (the
     * identifiers, abbreviations and offsets in use fit many times over); later names are parsed on each use.
     */
    private static final Map<String, PhpZone> ZONE_CACHE = new ConcurrentHashMap<>();
    private static final int ZONE_CACHE_LIMIT = 4096;
    private static final PhpZone NO_ZONE = new PhpZone(0, "", ZoneOffset.UTC, 0, false);

    private static final Pattern OFFSET = Pattern.compile("([+-])(\\d{1,2})(?::?(\\d{2}))?(?::?(\\d{2}))?");

    /**
     * A timezone as timelib_parse_zone reads one: "GMT" before an offset is skipped, an offset (+2, +02:00,
     * +0200), "UTC" (an identifier), an abbreviation (under six letters, case-insensitive; "GMT" too), or an
     * identifier (case-insensitive). Null if it is none of these.
     */
    @TruffleBoundary
    static PhpZone parseZone(String given) {
        PhpZone zone = ZONE_CACHE.get(given);
        if (zone == null) {
            zone = parseZoneUncached(given);
            if (ZONE_CACHE.size() < ZONE_CACHE_LIMIT) {
                ZONE_CACHE.putIfAbsent(given, zone);
            }
        }
        return zone == NO_ZONE ? null : zone;
    }

    private static PhpZone parseZoneUncached(String given) {
        String name = given.trim();
        if (name.length() > 3 && name.regionMatches(true, 0, "GMT", 0, 3)
                && (name.charAt(3) == '+' || name.charAt(3) == '-')) {
            name = name.substring(3);
        }
        Matcher offset = OFFSET.matcher(name);
        if (offset.matches()) {
            int hours = Integer.parseInt(offset.group(2));
            int minutes = offset.group(3) == null ? 0 : Integer.parseInt(offset.group(3));
            int seconds = offset.group(4) == null ? 0 : Integer.parseInt(offset.group(4));
            int total = (offset.group(1).equals("-") ? -1 : 1) * (hours * 3600 + minutes * 60 + seconds);
            if (Math.abs(total) > 18 * 3600) {
                return NO_ZONE;
            }
            return new PhpZone(ZONETYPE_OFFSET, offsetName(total), ZoneOffset.ofTotalSeconds(total), total, false);
        }
        String lower = Str.lower(name);
        if (lower.equals("utc")) {
            return new PhpZone(ZONETYPE_ID, "UTC", ZoneId.of("UTC"), 0, false);
        }
        int[] abbreviation = name.length() < 6 ? TzTables.ABBREVIATIONS.get(lower) : null;
        if (lower.equals("gmt")) {
            abbreviation = new int[]{0, 0};
        }
        if (abbreviation != null) {
            return new PhpZone(ZONETYPE_ABBR, Str.upper(name),
                    ZoneOffset.ofTotalSeconds(abbreviation[0]), abbreviation[0] - abbreviation[1] * 3600,
                    abbreviation[1] != 0);
        }
        String id = TzTables.CANONICAL.get(lower);
        if (id == null) {
            for (String available : ZoneId.getAvailableZoneIds()) {
                if (available.equalsIgnoreCase(name)) {
                    id = available;
                    break;
                }
            }
        }
        if (id == null) {
            return NO_ZONE;
        }
        try {
            // the name stays as given ("europe/warsaw"), as php-src keeps it
            return new PhpZone(ZONETYPE_ID, name, ZoneId.of(id), 0, false);
        } catch (DateTimeException e) {
            return NO_ZONE;
        }
    }

    /** php_format_date(format, timestamp, localtime = 1): the moment in the context's default timezone. */
    static TruffleString formatLocal(PhpContext context, String format, long timestamp) {
        return format(Str.of(format), timestamp, 0, contextZone(context), true);
    }

    /** The context's default timezone (date(), mktime()), an identifier. */
    private static PhpZone contextZone(PhpContext context) {
        ZoneId zone = context.timezone();
        PhpZone parsed = parseZone(zone.getId());
        return parsed != null ? parsed : new PhpZone(ZONETYPE_ID, zone.getId(), zone, 0, false);
    }

    /** DateTimeZone::getName() of an offset zone: "+02:00", or "+02:00:30" with seconds. */
    private static String offsetName(int total) {
        int abs = Math.abs(total);
        String name = (total < 0 ? "-" : "+") + two(abs / 3600) + ":" + two(abs / 60 % 60);
        return abs % 60 == 0 ? name : name + ":" + two(abs % 60);
    }

    private static ZoneId zoneOf(Object[] a, int index) {
        PhpZone zone = parseZone(Str.java(Args.str(a, index)));
        return zone == null ? ZoneOffset.UTC : zone.zoneId();
    }

    /** The zone's UTC offset at a moment, its DST flag and abbreviation, as timelib_time_offset. */
    private record ZoneState(int offset, boolean dst, String abbreviation) {
    }

    private static ZoneState zoneState(PhpZone zone, long timestamp) {
        switch (zone.type()) {
            case ZONETYPE_ABBR:
                return new ZoneState(zone.offset() + (zone.dst() ? 3600 : 0), zone.dst(), zone.name());
            case ZONETYPE_OFFSET: {
                int total = zone.offset();
                return new ZoneState(total, false, "GMT" + (total < 0 ? '-' : '+') + two(Math.abs(total / 3600))
                        + two(Math.abs(total % 3600 / 60)));
            }
            default: {
                int total = zone.rules().getRules().getOffset(Instant.ofEpochSecond(timestamp)).getTotalSeconds();
                Era[] eras = TzTables.ZONES.get(Str.lower(zone.name()));
                if (eras != null) {
                    int at = 0;
                    while (at + 1 < eras.length && eras[at + 1].start() <= timestamp) {
                        at++;
                    }
                    for (int e = at; e >= 0; e--) {
                        Era era = eras[e];
                        for (int i = 0; i < era.offsets().length; i++) {
                            if (era.offsets()[i] == total) {
                                return new ZoneState(total, era.dsts()[i], era.abbreviations()[i]);
                            }
                        }
                    }
                }
                boolean dst = zone.rules().getRules().isDaylightSavings(Instant.ofEpochSecond(timestamp));
                int abs = Math.abs(total);
                String abbreviation = (total < 0 ? "-" : "+") + two(abs / 3600)
                        + (abs % 3600 == 0 ? "" : two(abs / 60 % 60));
                return new ZoneState(total, dst, abbreviation);
            }
        }
    }

    /** date_set(ts, zone, year, month, day, hour, minute, second): null parts stay; out-of-range parts roll over. */
    @TruffleBoundary
    private static long set(long timestamp, ZoneId zone, Object[] a) {
        ZonedDateTime t = Instant.ofEpochSecond(timestamp).atZone(zone);
        long year = part(a, 2, t.getYear());
        long month = part(a, 3, t.getMonthValue());
        long day = part(a, 4, t.getDayOfMonth());
        long hour = part(a, 5, t.getHour());
        long minute = part(a, 6, t.getMinute());
        long second = part(a, 7, t.getSecond());
        return LocalDate.of((int) year, 1, 1).atStartOfDay(zone).plusMonths(month - 1).plusDays(day - 1)
                .plusHours(hour).plusMinutes(minute).plusSeconds(second).toEpochSecond();
    }

    private static long part(Object[] a, int index, long current) {
        return Args.has(a, index) && Args.get(a, index) != PhpNull.NULL ? Args.lng(a, index, 0) : current;
    }

    /** date_add(ts, zone, y, m, d, h, i, s, sign): a DateInterval added (sign 1) or subtracted (-1). */
    @TruffleBoundary
    private static long add(long timestamp, ZoneId zone, Object[] a) {
        long sign = Args.lng(a, 8, 1);
        ZonedDateTime t = Instant.ofEpochSecond(timestamp).atZone(zone);
        return t.plusYears(sign * Args.lng(a, 2, 0)).plusMonths(sign * Args.lng(a, 3, 0))
                .plusDays(sign * Args.lng(a, 4, 0)).plusHours(sign * Args.lng(a, 5, 0))
                .plusMinutes(sign * Args.lng(a, 6, 0)).plusSeconds(sign * Args.lng(a, 7, 0)).toEpochSecond();
    }

    /** date_diff(from, to, zone): [y, m, d, h, i, s, invert, days], as DateTime::diff computes the parts. */
    @TruffleBoundary
    private static PhpArray diff(long from, long to, ZoneId zone) {
        boolean invert = to < from;
        ZonedDateTime start = Instant.ofEpochSecond(invert ? to : from).atZone(zone);
        ZonedDateTime end = Instant.ofEpochSecond(invert ? from : to).atZone(zone);
        java.time.Period period = java.time.Period.between(start.toLocalDate(), end.toLocalDate());
        long seconds = java.time.Duration.between(start.toLocalTime(), end.toLocalTime()).getSeconds();
        if (seconds < 0) {
            period = period.minusDays(1);
            seconds += 86400;
            if (period.getDays() < 0) {
                period = java.time.Period.between(start.toLocalDate(), end.toLocalDate().minusDays(1));
            }
        }
        long days = ChronoUnit.DAYS.between(start.toLocalDateTime(), end.toLocalDateTime());
        return PhpArray.list((long) period.getYears(), (long) period.getMonths(), (long) period.getDays(),
                seconds / 3600, seconds / 60 % 60, seconds % 60, invert ? 1L : 0L, days);
    }

    /**
     * DateTime::createFromFormat for the common characters: d j m n Y y H G h g i s A a U, month and day names,
     * zones (e T P O), `!` and `|` (reset unparsed parts to the epoch's), backslash escapes. Unparsed parts are now's.
     */
    @TruffleBoundary
    private static Object fromFormat(String format, String value, ZoneId zone) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        Integer year = null;
        Integer month = null;
        Integer day = null;
        Integer hour = null;
        Integer minute = null;
        Integer second = null;
        boolean pm = false;
        boolean meridian = false;
        boolean reset = false;
        Long epoch = null;
        int at = 0;
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c == '\\' && i + 1 < format.length()) {
                i++;
                if (at >= value.length() || value.charAt(at) != format.charAt(i)) {
                    return Boolean.FALSE;
                }
                at++;
                continue;
            }
            if (c == '!' || c == '|') {
                reset = true;
                continue;
            }
            int width = switch (c) {
                case 'd', 'm', 'H', 'h', 'i', 's', 'y' -> 2;
                case 'j', 'n', 'G', 'g' -> -2;
                case 'Y' -> 4;
                case 'U' -> -12;
                default -> 0;
            };
            if (width != 0) {
                int max = Math.abs(width);
                int start = at;
                if (c == 'U' && at < value.length() && value.charAt(at) == '-') {
                    at++;
                }
                while (at < value.length() && at - start < max && Character.isDigit(value.charAt(at))) {
                    at++;
                }
                if (at == start || width > 0 && c != 'Y' && at - start != max) {
                    return Boolean.FALSE;
                }
                long number = Long.parseLong(value.substring(start, at));
                switch (c) {
                    case 'd', 'j' -> day = (int) number;
                    case 'm', 'n' -> month = (int) number;
                    case 'Y' -> year = (int) number;
                    case 'y' -> year = (int) (number < 70 ? 2000 + number : 1900 + number);
                    case 'H', 'G', 'h', 'g' -> hour = (int) number;
                    case 'i' -> minute = (int) number;
                    case 's' -> second = (int) number;
                    case 'U' -> epoch = number;
                    default -> {
                    }
                }
                continue;
            }
            int wordStart = at;
            switch (c) {
                case 'A', 'a' -> {
                    if (at + 2 > value.length()) {
                        return Boolean.FALSE;
                    }
                    pm = value.substring(at, at + 2).equalsIgnoreCase("pm");
                    meridian = true;
                    at += 2;
                }
                case 'M', 'D' -> at = Math.min(value.length(), at + 3);
                case 'F', 'l' -> {
                    while (at < value.length() && Character.isLetter(value.charAt(at))) {
                        at++;
                    }
                }
                case 'e', 'T', 'P', 'O' -> {
                    int start = at;
                    while (at < value.length() && " ".indexOf(value.charAt(at)) < 0) {
                        at++;
                    }
                    PhpZone parsed = parseZone(value.substring(start, at));
                    if (parsed == null) {
                        return Boolean.FALSE;
                    }
                    zone = parsed.zoneId();
                }
                default -> {
                    if (at >= value.length() || value.charAt(at) != c) {
                        return Boolean.FALSE;
                    }
                    at++;
                }
            }
            if ((c == 'M' || c == 'F') && at - wordStart >= 3) {
                month = monthNumber(value.substring(wordStart, at));
            }
        }
        if (at != value.length()) {
            return Boolean.FALSE;
        }
        if (epoch != null) {
            return epoch;
        }
        if (meridian && hour != null) {
            hour = hour % 12 + (pm ? 12 : 0);
        }
        boolean anyTime = hour != null || minute != null || second != null;
        int y = year != null ? year : reset ? 1970 : now.getYear();
        int mo = month != null ? month : reset ? 1 : now.getMonthValue();
        int d = day != null ? day : reset ? 1 : now.getDayOfMonth();
        int h = hour != null ? hour : reset || anyTime ? 0 : now.getHour();
        int mi = minute != null ? minute : reset || anyTime ? 0 : now.getMinute();
        int se = second != null ? second : reset || anyTime ? 0 : now.getSecond();
        return LocalDate.of(y, 1, 1).atStartOfDay(zone).plusMonths(mo - 1).plusDays(d - 1).plusHours(h)
                .plusMinutes(mi).plusSeconds(se).toEpochSecond();
    }

    private static ZoneId zone(PhpContext context) {
        return context.timezone();
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static long timestamp(Object[] a, int index) {
        return Args.has(a, index) && Args.get(a, index) != PhpNull.NULL ? Args.lng(a, index, 0) : now();
    }

    /** timezone_identifiers_list(): PHP's list (from its timezone database). */
    @TruffleBoundary
    private static PhpArray identifiers() {
        PhpArray list = new PhpArray();
        for (String id : TzTables.IDENTIFIERS) {
            list.append(Str.of(id));
        }
        return list;
    }

    @TruffleBoundary
    private static Object checkdate(long month, long day, long year) {
        if (month < 1 || month > 12 || day < 1 || year < 1 || year > 32767) {
            return Boolean.FALSE;
        }
        return day <= LocalDate.of((int) year, (int) month, 1).lengthOfMonth();
    }

    /** mktime(hour, minute, second, month, day, year): missing arguments are the current date's. */
    @TruffleBoundary
    private static Object mktime(Object[] a, ZoneId zone) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        long hour = Args.lng(a, 0, now.getHour());
        long minute = Args.lng(a, 1, now.getMinute());
        long second = Args.lng(a, 2, now.getSecond());
        long month = Args.lng(a, 3, now.getMonthValue());
        long day = Args.lng(a, 4, now.getDayOfMonth());
        long year = Args.lng(a, 5, now.getYear());
        if (Args.has(a, 5) && year >= 0 && year < 70) {
            year += 2000;
        } else if (Args.has(a, 5) && year >= 70 && year <= 100) {
            year += 1900;
        }
        // out-of-range parts roll over, as in PHP: start from January 1st and add
        ZonedDateTime date = LocalDate.of((int) year, 1, 1).atStartOfDay(zone)
                .plusMonths(month - 1).plusDays(day - 1).plusHours(hour).plusMinutes(minute).plusSeconds(second);
        return date.toEpochSecond();
    }

    private static final String[] MONTH_FULL = {"January", "February", "March", "April", "May", "June", "July",
            "August", "September", "October", "November", "December"};
    private static final String[] MONTH_SHORT = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct",
            "Nov", "Dec"};
    private static final String[] DAY_FULL = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday",
            "Saturday"};
    private static final String[] DAY_SHORT = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};

    /**
     * php_date.c's date_format: the format's characters for a moment (timestamp and microseconds) in a zone;
     * localtime false is gmdate's UTC without a zone. A backslash escapes the next byte (a trailing one prints
     * the string's terminating NUL, as php-src does).
     */
    @TruffleBoundary
    static TruffleString format(TruffleString formatString, long timestamp, int micro, PhpZone zone,
                                boolean localtime) {
        byte[] format = Str.bytes(formatString);
        if (format.length == 0) {
            return Str.EMPTY;
        }
        ZoneState state = localtime ? zoneState(zone, timestamp) : new ZoneState(0, false, "GMT");
        int offset = state.offset();
        LocalDateTime t = LocalDateTime.ofEpochSecond(timestamp, 0, ZoneOffset.ofTotalSeconds(offset));
        long y = t.getYear();
        int m = t.getMonthValue();
        int d = t.getDayOfMonth();
        int h = t.getHour();
        int dayOfWeek = t.getDayOfWeek().getValue() % 7;
        StringBuilder out = new StringBuilder();
        ByteBuilder bytes = new ByteBuilder(format.length + 16);
        for (int i = 0; i < format.length; i++) {
            out.setLength(0);
            switch (format[i]) {
                case 'd' -> out.append(two(d));
                case 'D' -> out.append(DAY_SHORT[dayOfWeek]);
                case 'j' -> out.append(d);
                case 'l' -> out.append(DAY_FULL[dayOfWeek]);
                case 'S' -> out.append(englishSuffix(d));
                case 'w' -> out.append(dayOfWeek);
                case 'N' -> out.append(dayOfWeek == 0 ? 7 : dayOfWeek);
                case 'z' -> out.append(t.getDayOfYear() - 1);
                case 'W' -> out.append(two(t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)));
                case 'o' -> out.append(t.get(IsoFields.WEEK_BASED_YEAR));
                case 'F' -> out.append(MONTH_FULL[m - 1]);
                case 'm' -> out.append(two(m));
                case 'M' -> out.append(MONTH_SHORT[m - 1]);
                case 'n' -> out.append(m);
                case 't' -> out.append(t.toLocalDate().lengthOfMonth());
                case 'L' -> out.append(t.toLocalDate().isLeapYear() ? 1 : 0);
                case 'y' -> out.append(String.format("%02d", y % 100));
                case 'Y' -> out.append(y < 0 ? "-" : "").append(String.format("%04d", Math.abs(y)));
                case 'x' -> out.append(y < 0 ? "-" : y >= 10000 ? "+" : "").append(String.format("%04d", Math.abs(y)));
                case 'X' -> out.append(y < 0 ? "-" : "+").append(String.format("%04d", Math.abs(y)));
                case 'a' -> out.append(h >= 12 ? "pm" : "am");
                case 'A' -> out.append(h >= 12 ? "PM" : "AM");
                case 'B' -> {
                    long beat = (timestamp % 86400 + 3600) * 10;
                    if (beat < 0) {
                        beat += 864000;
                    }
                    out.append(String.format("%03d", beat / 864 % 1000));
                }
                case 'g' -> out.append(h % 12 != 0 ? h % 12 : 12);
                case 'G' -> out.append(h);
                case 'h' -> out.append(two(h % 12 != 0 ? h % 12 : 12));
                case 'H' -> out.append(two(h));
                case 'i' -> out.append(two(t.getMinute()));
                case 's' -> out.append(two(t.getSecond()));
                case 'u' -> out.append(String.format("%06d", micro));
                case 'v' -> out.append(String.format("%03d", micro / 1000));
                case 'I' -> out.append(localtime && state.dst() ? 1 : 0);
                case 'p' -> {
                    String abbreviation = state.abbreviation();
                    if (!localtime || abbreviation.equals("UTC") || abbreviation.equals("Z")
                            || abbreviation.equals("GMT+0000")) {
                        out.append('Z');
                    } else {
                        out.append(offset(offset, true));
                    }
                }
                case 'P' -> out.append(offset(offset, true));
                case 'O' -> out.append(offset(offset, false));
                case 'T' -> out.append(state.abbreviation());
                case 'e' -> {
                    if (!localtime) {
                        out.append("UTC");
                    } else if (zone.type() == ZONETYPE_OFFSET) {
                        out.append(offsetName(offset));
                    } else {
                        out.append(zone.type() == ZONETYPE_ID ? zone.name() : state.abbreviation());
                    }
                }
                case 'Z' -> out.append(offset);
                case 'c' -> out.append(String.format("%04d", y)).append('-').append(two(m)).append('-').append(two(d))
                        .append('T').append(two(h)).append(':').append(two(t.getMinute())).append(':')
                        .append(two(t.getSecond())).append(offset(offset, true));
                case 'r' -> out.append(DAY_SHORT[dayOfWeek]).append(", ").append(two(d)).append(' ')
                        .append(MONTH_SHORT[m - 1]).append(' ').append(String.format("%04d", y)).append(' ')
                        .append(two(h)).append(':').append(two(t.getMinute())).append(':')
                        .append(two(t.getSecond())).append(' ').append(offset(offset, false));
                case 'U' -> out.append(timestamp);
                case '\\' -> {
                    i++;
                    bytes.write(i < format.length ? format[i] : 0);
                    continue;
                }
                default -> {
                    bytes.write(format[i]);
                    continue;
                }
            }
            bytes.write(out.toString().getBytes(StandardCharsets.ISO_8859_1));
        }
        return bytes.toStr();
    }

    private static String two(long value) {
        return value >= 0 && value < 10 ? "0" + value : Long.toString(value);
    }

    private static String englishSuffix(int number) {
        if (number >= 10 && number <= 19) {
            return "th";
        }
        return switch (number % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    /** "+0200" or "+02:00": the offset's hours and minutes (seconds dropped), as the O and P formats. */
    private static String offset(int seconds, boolean colon) {
        return (seconds < 0 ? "-" : "+") + two(Math.abs(seconds / 3600)) + (colon ? ":" : "")
                + two(Math.abs(seconds % 3600 / 60));
    }

    // ---- strtotime ----

    private static final Pattern ISO = Pattern.compile(
            "(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[T ](\\d{1,2}):(\\d{2})(?::(\\d{2})(?:\\.\\d+)?)?)?\\s*"
                    + "(Z|[+-]\\d{2}:?\\d{2}|UTC|GMT)?");
    private static final Pattern RFC2822 = Pattern.compile(
            "(?:[A-Za-z]{3},\\s*)?(\\d{1,2})\\s+([A-Za-z]{3})[a-z]*\\s+(\\d{4})\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*"
                    + "([+-]\\d{4}|GMT|UTC|Z)?");

    /** ISO dates and times, RFC 2822, @timestamp, and timelib's relative formats ({@link Relative}). */
    @TruffleBoundary
    static Long strtotime(String input, long base, ZoneId zone) {
        String text = Str.lower(input.trim());
        if (text.isEmpty()) {
            return null;
        }
        if (text.startsWith("@")) {
            try {
                return Long.parseLong(text.substring(1).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        Matcher iso = ISO.matcher(input.trim());
        if (iso.matches()) {
            return dateTime(iso, zone, 7);
        }
        Matcher rfc = RFC2822.matcher(input.trim());
        if (rfc.matches()) {
            int month = monthNumber(rfc.group(2));
            if (month == 0) {
                return null;
            }
            LocalDateTime local = LocalDateTime.of(Integer.parseInt(rfc.group(3)), month,
                    Integer.parseInt(rfc.group(1)), Integer.parseInt(rfc.group(4)), Integer.parseInt(rfc.group(5)),
                    rfc.group(6) == null ? 0 : Integer.parseInt(rfc.group(6)));
            return local.atZone(offsetOr(rfc.group(7), zone)).toEpochSecond();
        }
        Parsed parsed = Relative.parse(text, base, zone, false);
        return parsed == null ? null : parsed.epoch();
    }

    /** strtotime's result, and whether the string set the time of day (timelib's time, microseconds included). */
    record Parsed(long epoch, boolean timeSet) {
    }

    /** strtotime for DateTime::modify: whether the time was set decides if the microseconds stay. */
    @TruffleBoundary
    static Parsed parseForModify(String input, long base, ZoneId zone) {
        String text = Str.lower(input.trim());
        Long whole = text.startsWith("@") || ISO.matcher(input.trim()).matches()
                || RFC2822.matcher(input.trim()).matches() ? strtotime(input, base, zone) : null;
        if (whole != null) {
            return new Parsed(whole, true);
        }
        // the object's zone and DST flag take part (tz->have_zone, tz->dst)
        return text.isEmpty() ? null : Relative.parse(text, base, zone, true);
    }

    /**
     * timelib's relative formats (parse_date.re), token by token: the keywords (now, today, midnight, noon,
     * tomorrow, yesterday), a time of day (12-hour with a meridian, 24-hour), relative numbers with units, relative
     * text (next, last, previous, this, first ... twelfth) with a unit or a weekday, "next week" and its kin,
     * weekdays, "first/last day of" and "ago". Each token changes the state as timelib's rule does; then the state is
     * applied as timelib_update_ts applies it: the weekday, the relative fields on the wall clock, first/last day of,
     * normalised as timelib_do_normalize, the zone's offset resolved last (an overlap takes the earlier offset, a gap
     * moves forward). A token timelib doesn't know, or a second time of day, is an error: null.
     */
    private static final class Relative {
        private static final String DAYS = "sunday|monday|tuesday|wednesday|thursday|friday|saturday"
                + "|sun|mon|tue|wed|thu|fri|sat";
        /** timelib's reltextunit (without the sub-second units and weekday/weekdays), and 'week' or 'weeks'. */
        private static final String UNIT = "(?:(?:second|sec|minute|min|hour|day|fortnight|forthnight|month|year)s?"
                + "|weeks?|" + DAYS + ")(?![a-z])";
        private static final Pattern FIRST_LAST_DAY_OF = Pattern.compile("(first|last) day of(?![a-z])");
        private static final Pattern KEYWORD = Pattern.compile(
                "(yesterday|today|tomorrow|midnight|noon|now)(?![a-z])");
        private static final Pattern AGO = Pattern.compile("ago(?![a-z])");
        private static final Pattern TIME12 = Pattern.compile(
                "(0?[1-9]|1[0-2])(?:[:.]([0-5]?[0-9])(?:[:.]([0-5]?[0-9]|60))?)?[ \t]*([ap])\\.?m\\.?(?=$|[ \t])");
        private static final Pattern TIME24 = Pattern.compile(
                "t?([01]?[0-9]|2[0-4])[:.]([0-5]?[0-9])(?:[:.]([0-5]?[0-9]|60))?(?![0-9])");
        private static final Pattern NUMBER = Pattern.compile("([+-]*)[ \t]*([0-9]{1,13})[ \t]*(" + UNIT + ")");
        private static final Pattern TEXT_WEEK = Pattern.compile("(next|last|previous|this)[ \t]+week(?![a-z])");
        private static final Pattern TEXT = Pattern.compile("(next|last|previous|this|first|second|third|fourth"
                + "|fifth|sixth|seventh|eighth|ninth|tenth|eleventh|twelfth)[ \t]+(" + UNIT + ")");
        private static final Pattern WEEKDAY = Pattern.compile("(" + DAYS + ")(?![a-z])");
        private static final List<String> ORDINALS = List.of("first", "second", "third", "fourth", "fifth", "sixth",
                "seventh", "eighth", "ninth", "tenth", "eleventh", "twelfth");

        // the time of day: set by a keyword or a time, else the base's
        private boolean timeSet;
        private boolean haveTime;
        private long hour;
        private long minute;
        private long second;
        // timelib's relative fields
        private long relYears;
        private long relMonths;
        private long relDays;
        private long relHours;
        private long relMinutes;
        private long relSeconds;
        private boolean haveWeekday;
        private int weekday;
        private int weekdayBehavior;
        /** 0, or TIMELIB_SPECIAL_FIRST_DAY_OF_MONTH (1) or _LAST_ (2). */
        private int firstLastDayOf;

        /** haveZone: the base is a DateTime's, whose zone and DST flag resolve an overlap (DateTime::modify). */
        static Parsed parse(String text, long base, ZoneId zone, boolean haveZone) {
            Relative state = new Relative();
            int position = 0;
            while (true) {
                // timelib skips spaces, tabs, commas and dots between tokens
                while (position < text.length() && " \t,.".indexOf(text.charAt(position)) >= 0) {
                    position++;
                }
                if (position >= text.length()) {
                    break;
                }
                int next = state.token(text, position);
                if (next < 0) {
                    return null;
                }
                position = next;
            }
            return new Parsed(state.apply(base, zone, haveZone), state.timeSet);
        }

        /** The token at position, applied; the position after it, or -1 for an error. */
        private int token(String text, int position) {
            Matcher m;
            if ((m = at(FIRST_LAST_DAY_OF, text, position)) != null) {
                firstLastDayOf = m.group(1).equals("first") ? 1 : 2;
                return m.end();
            }
            if ((m = at(KEYWORD, text, position)) != null) {
                switch (m.group(1)) {
                    case "yesterday" -> {
                        unhaveTime();
                        relDays = -1;
                    }
                    case "tomorrow" -> {
                        unhaveTime();
                        relDays = 1;
                    }
                    case "midnight", "today" -> unhaveTime();
                    case "noon" -> {
                        unhaveTime();
                        haveTime = true;
                        hour = 12;
                    }
                    default -> {
                        // now: nothing
                    }
                }
                return m.end();
            }
            if ((m = at(AGO, text, position)) != null) {
                relYears = -relYears;
                relMonths = -relMonths;
                relDays = -relDays;
                relHours = -relHours;
                relMinutes = -relMinutes;
                relSeconds = -relSeconds;
                weekday = weekday == 0 ? -7 : -weekday;
                return m.end();
            }
            if ((m = at(TIME12, text, position)) != null) {
                if (!haveTime()) {
                    return -1;
                }
                long h = Long.parseLong(m.group(1));
                // timelib_meridian: 12am is 0, 12pm stays 12
                hour = m.group(4).equals("a") ? h % 12 : h % 12 + 12;
                minute = m.group(2) == null ? 0 : Long.parseLong(m.group(2));
                second = m.group(3) == null ? 0 : Long.parseLong(m.group(3));
                return m.end();
            }
            if ((m = at(TIME24, text, position)) != null) {
                if (!haveTime()) {
                    return -1;
                }
                hour = Long.parseLong(m.group(1));
                minute = Long.parseLong(m.group(2));
                second = m.group(3) == null ? 0 : Long.parseLong(m.group(3));
                return m.end();
            }
            if ((m = at(NUMBER, text, position)) != null) {
                // relnumber: each minus sign flips the sign
                long amount = Long.parseLong(m.group(2));
                for (int i = 0; i < m.group(1).length(); i++) {
                    if (m.group(1).charAt(i) == '-') {
                        amount = -amount;
                    }
                }
                setRelative(amount, 0, m.group(3), true);
                return m.end();
            }
            if ((m = at(TEXT_WEEK, text, position)) != null) {
                setRelative(textAmount(m.group(1)), textBehavior(m.group(1)), "week", false);
                weekdayBehavior = 2;
                if (!haveWeekday) {
                    haveWeekday = true;
                    weekday = 1;
                }
                return m.end();
            }
            if ((m = at(TEXT, text, position)) != null) {
                setRelative(textAmount(m.group(1)), textBehavior(m.group(1)), m.group(2), false);
                return m.end();
            }
            if ((m = at(WEEKDAY, text, position)) != null) {
                haveWeekday = true;
                unhaveTime();
                weekday = dayIndex(m.group(1));
                if (weekdayBehavior != 2) {
                    weekdayBehavior = 1;
                }
                return m.end();
            }
            return -1;
        }

        private static Matcher at(Pattern pattern, String text, int position) {
            Matcher m = pattern.matcher(text);
            m.region(position, text.length());
            return m.lookingAt() ? m : null;
        }

        /** TIMELIB_UNHAVE_TIME: the time of day is midnight, and may be set again. */
        private void unhaveTime() {
            timeSet = true;
            haveTime = false;
            hour = 0;
            minute = 0;
            second = 0;
        }

        /** TIMELIB_HAVE_TIME: false for a second time of day ("Double time specification"). */
        private boolean haveTime() {
            if (haveTime) {
                return false;
            }
            unhaveTime();
            haveTime = true;
            return true;
        }

        /** timelib_lookup_relative_text: last and previous -1, this 0, next 1, first ... twelfth 1 ... 12. */
        private static long textAmount(String word) {
            return switch (word) {
                case "last", "previous" -> -1;
                case "this" -> 0;
                case "next" -> 1;
                default -> ORDINALS.indexOf(word) + 1;
            };
        }

        private static int textBehavior(String word) {
            return word.equals("this") ? 1 : 0;
        }

        /** timelib_set_relative: a unit's amount, or for a weekday, the weekday and the weeks before it. */
        private void setRelative(long amount, int behavior, String unit, boolean keepTime) {
            String u = unit.endsWith("s") && !unit.equals("days") && !isDay(unit) ? unit.substring(0, unit.length() - 1)
                    : unit;
            if (isDay(unit)) {
                haveWeekday = true;
                if (!keepTime) {
                    unhaveTime();
                }
                relDays += (amount > 0 ? amount - 1 : amount) * 7;
                weekday = dayIndex(unit);
                weekdayBehavior = behavior;
                return;
            }
            switch (u) {
                case "sec", "second" -> relSeconds += amount;
                case "min", "minute" -> relMinutes += amount;
                case "hour" -> relHours += amount;
                case "day", "days" -> relDays += amount;
                case "week" -> relDays += 7 * amount;
                case "fortnight", "forthnight" -> relDays += 14 * amount;
                case "month" -> relMonths += amount;
                default -> relYears += amount;
            }
        }

        private static boolean isDay(String unit) {
            return WEEKDAY.matcher(unit).matches();
        }

        /** 0 for Sunday ... 6 for Saturday, by the name's first three letters. */
        private static int dayIndex(String name) {
            return List.of("sun", "mon", "tue", "wed", "thu", "fri", "sat").indexOf(name.substring(0, 3));
        }

        /** timelib_update_ts: the base's wall clock in the zone, the state applied, the offset resolved. */
        private long apply(long base, ZoneId zone, boolean haveZone) {
            LocalDateTime local = Instant.ofEpochSecond(base).atZone(zone).toLocalDateTime();
            long y = local.getYear();
            long m = local.getMonthValue();
            long d = local.getDayOfMonth();
            long h = timeSet ? hour : local.getHour();
            long i = timeSet ? minute : local.getMinute();
            long s = timeSet ? second : local.getSecond();
            if (haveWeekday) {
                d += weekdayShift(local.toLocalDate().getDayOfWeek().getValue() % 7);
            }
            y += relYears;
            m += relMonths;
            d += relDays;
            h += relHours;
            i += relMinutes;
            s += relSeconds;
            if (firstLastDayOf == 1) {
                d = 1;
            } else if (firstLastDayOf == 2) {
                d = 0;
                m++;
            }
            // timelib_do_normalize: months into years, then days across months, the time of day carried in
            LocalDateTime wall = LocalDate.of((int) (y + Math.floorDiv(m - 1, 12)), (int) Math.floorMod(m - 1, 12) + 1,
                    1).plusDays(d - 1).atStartOfDay().plusSeconds(h * 3600 + i * 60 + s);
            return localToEpoch(wall, zone, haveZone,
                    haveZone && zone.getRules().isDaylightSavings(Instant.ofEpochSecond(base)));
        }

        /** do_adjust_for_weekday: the days to the weekday from a date on currentDow (0 Sunday). */
        private long weekdayShift(int currentDow) {
            int target = weekday;
            if (weekdayBehavior == 2) {
                // "this week" from a Sunday; "sunday this week" from another day
                if (currentDow == 0 && target != 0) {
                    target -= 7;
                }
                if (target == 0 && currentDow != 0) {
                    target = 7;
                }
                return target - currentDow;
            }
            int difference = target - currentDow;
            if (relDays < 0 && difference < 0 || relDays >= 0 && difference <= -weekdayBehavior) {
                difference += 7;
            }
            return target >= 0 ? difference : -(7 - (Math.abs(target) - currentDow));
        }
    }

    private static Long dateTime(Matcher m, ZoneId zone, int offsetGroup) {
        try {
            LocalDate date = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)));
            LocalTime time = m.group(4) == null ? LocalTime.MIDNIGHT : LocalTime.of(Integer.parseInt(m.group(4)),
                    Integer.parseInt(m.group(5)), m.group(6) == null ? 0 : Integer.parseInt(m.group(6)));
            return localToEpoch(date.atTime(time), offsetOr(m.group(offsetGroup), zone), false, false);
        } catch (DateTimeException e) {
            return null;
        }
    }

    /**
     * timelib's do_adjust_timezone for a zone with rules: a wall-clock time to an instant. The wall time read as
     * UTC gives the current offset, and that offset back gives the one after; with haveZone (a DateTime's own
     * zone) and dst (its DST flag), a look two hours behind or ahead can pick the DST side of an overlap. A gap moves
     * forward; an overlap without that look takes the later (standard) offset.
     */
    static long localToEpoch(LocalDateTime wall, ZoneId zone, boolean haveZone, boolean dst) {
        java.time.zone.ZoneRules rules = zone.getRules();
        if (rules.isFixedOffset()) {
            return wall.toEpochSecond(rules.getOffset(Instant.EPOCH));
        }
        long sse = wall.toEpochSecond(ZoneOffset.UTC);
        Instant currentAt = Instant.ofEpochSecond(sse);
        long current = rules.getOffset(currentAt).getTotalSeconds();
        boolean currentIsDst = rules.isDaylightSavings(currentAt);
        long after = offsetAt(rules, sse - current);
        long afterTransition = transitionAt(rules, sse - current);
        long actual = after;
        long actualTransition = afterTransition;
        if (current == after && haveZone) {
            if (current >= 0 && dst && !currentIsDst) {
                // east of UTC: the wall time read as UTC has left DST before the actual time
                long earlier = offsetAt(rules, sse - current - 7200);
                if (earlier != after && sse - earlier < afterTransition) {
                    actual = earlier;
                    actualTransition = transitionAt(rules, sse - current - 7200);
                }
            } else if (current <= 0 && currentIsDst && !dst) {
                // west of UTC: it leaves DST after the actual time
                long later = offsetAt(rules, sse - current + 7200);
                long laterTransition = transitionAt(rules, sse - current + 7200);
                if (later != after && sse - later >= laterTransition) {
                    actual = later;
                    actualTransition = laterTransition;
                }
            }
        }
        boolean inTransition = actualTransition != Long.MIN_VALUE
                && sse - actual >= actualTransition + (current - actual) && sse - actual < actualTransition;
        return sse - (current != actual && !inTransition ? actual : current);
    }

    private static long offsetAt(java.time.zone.ZoneRules rules, long epoch) {
        return rules.getOffset(Instant.ofEpochSecond(epoch)).getTotalSeconds();
    }

    /** The start of the zone period holding epoch (timelib's transition_time), or Long.MIN_VALUE before the first. */
    private static long transitionAt(java.time.zone.ZoneRules rules, long epoch) {
        java.time.zone.ZoneOffsetTransition previous = rules.previousTransition(Instant.ofEpochSecond(epoch, 1));
        return previous == null ? Long.MIN_VALUE : previous.getInstant().getEpochSecond();
    }

    private static ZoneId offsetOr(String offset, ZoneId zone) {
        if (offset == null) {
            return zone;
        }
        if (offset.equalsIgnoreCase("Z") || offset.equalsIgnoreCase("UTC") || offset.equalsIgnoreCase("GMT")) {
            return ZoneOffset.UTC;
        }
        String digits = offset.replace(":", "");
        return ZoneOffset.ofHoursMinutes(Integer.parseInt(digits.substring(0, 3)),
                (digits.charAt(0) == '-' ? -1 : 1) * Integer.parseInt(digits.substring(3)));
    }

    private static int monthNumber(String name) {
        String months = "janfebmaraprmayjunjulaugsepoctnovdec";
        int index = months.indexOf(Str.lower(name).substring(0, 3));
        return index < 0 || index % 3 != 0 ? 0 : index / 3 + 1;
    }
}
