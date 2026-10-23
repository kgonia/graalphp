package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpException;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.Str;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.Locale;
import java.util.TreeSet;
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
        r.fn("date", (context, a) -> Str.of(format(Str.java(Args.str(a, 0)), timestamp(a, 1), zone(context))));
        r.fn("gmdate", a -> Str.of(format(Str.java(Args.str(a, 0)), timestamp(a, 1), ZoneOffset.UTC)));
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
        r.fn("date_modify", a -> method(a, "modify"));
        r.fn("date_add", a -> method(a, "add"));
        r.fn("date_sub", a -> method(a, "sub"));
        r.fn("date_diff", a -> method(a, "diff"));
        r.fn("timezone_name_get", a -> method(a, "getname"));
        r.fn("timezone_offset_get", a -> method(a, "getoffset"));

        r.fn("truffle\\timezone_name", a -> {
            ZoneId zone = parseZone(Str.java(Args.str(a, 0)));
            return zone == null ? (Object) Boolean.FALSE : Str.of(zoneName(zone, Str.java(Args.str(a, 0))));
        });
        r.fn("truffle\\timezone_offset", a -> (long) zoneOf(a, 0).getRules()
                .getOffset(Instant.ofEpochSecond(Args.lng(a, 1, 0))).getTotalSeconds());
        r.fn("truffle\\date_format", a -> Str.of(format(Str.java(Args.str(a, 0)), Args.lng(a, 1, 0),
                zoneOf(a, 2))));
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
        try {
            object.callMethod("__construct", arguments(a, 0));
        } catch (PhpException | PhpError e) {
            return Boolean.FALSE;
        }
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

    private static final Pattern OFFSET = Pattern.compile("([+-])(\\d{1,2}):?(\\d{2})?");

    /** A timezone as PHP names them: an identifier, UTC, an offset (+02:00, +0200) or an abbreviation; or null. */
    @TruffleBoundary
    static ZoneId parseZone(String name) {
        String trimmed = name.trim();
        if (trimmed.equalsIgnoreCase("UTC") || trimmed.equalsIgnoreCase("GMT")) {
            return ZoneId.of("UTC");
        }
        if (trimmed.equalsIgnoreCase("Z")) {
            return ZoneOffset.UTC;
        }
        Matcher offset = OFFSET.matcher(trimmed);
        if (offset.matches()) {
            int hours = Integer.parseInt(offset.group(2));
            int minutes = offset.group(3) == null ? 0 : Integer.parseInt(offset.group(3));
            int sign = offset.group(1).equals("-") ? -1 : 1;
            return ZoneOffset.ofTotalSeconds(sign * (hours * 3600 + minutes * 60));
        }
        try {
            return ZoneId.of(trimmed, ZoneId.SHORT_IDS);
        } catch (DateTimeException e) {
            for (String id : ZoneId.getAvailableZoneIds()) {
                if (id.equalsIgnoreCase(trimmed)) {
                    return ZoneId.of(id);
                }
            }
            return null;
        }
    }

    /** DateTimeZone::getName(): the identifier as given for regions, "+02:00" for offsets, UTC for UTC. */
    private static String zoneName(ZoneId zone, String given) {
        if (zone instanceof ZoneOffset offset) {
            return given.trim().equalsIgnoreCase("Z") ? "Z" : offsetName(offset);
        }
        return zone.getId();
    }

    /** An offset zone's name: +02:00, and +00:00 rather than java.time's "Z". */
    private static String offsetName(ZoneOffset offset) {
        return offset.getTotalSeconds() == 0 ? "+00:00" : offset.getId();
    }

    private static ZoneId zoneOf(Object[] a, int index) {
        ZoneId zone = parseZone(Str.java(Args.str(a, index)));
        return zone == null ? ZoneOffset.UTC : zone;
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
                    ZoneId parsed = parseZone(value.substring(start, at));
                    if (parsed == null) {
                        return Boolean.FALSE;
                    }
                    zone = parsed;
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

    @TruffleBoundary
    private static PhpArray identifiers() {
        PhpArray list = new PhpArray();
        for (String id : new TreeSet<>(ZoneId.getAvailableZoneIds())) {
            if (id.contains("/") && !id.startsWith("Etc/") && !id.startsWith("SystemV/")) {
                list.append(Str.of(id));
            }
        }
        list.append(Str.of("UTC"));
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

    /** date() with PHP's format characters; a backslash escapes the next one. */
    @TruffleBoundary
    static String format(String format, long timestamp, ZoneId zone) {
        ZonedDateTime t = Instant.ofEpochSecond(timestamp).atZone(zone);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            switch (c) {
                case 'd' -> out.append(two(t.getDayOfMonth()));
                case 'D' -> out.append(t.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
                case 'j' -> out.append(t.getDayOfMonth());
                case 'l' -> out.append(t.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
                case 'N' -> out.append(t.getDayOfWeek().getValue());
                case 'S' -> out.append(suffix(t.getDayOfMonth()));
                case 'w' -> out.append(t.getDayOfWeek().getValue() % 7);
                case 'z' -> out.append(t.getDayOfYear() - 1);
                case 'W' -> out.append(two(t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)));
                case 'F' -> out.append(t.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH));
                case 'm' -> out.append(two(t.getMonthValue()));
                case 'M' -> out.append(t.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH));
                case 'n' -> out.append(t.getMonthValue());
                case 't' -> out.append(t.toLocalDate().lengthOfMonth());
                case 'L' -> out.append(t.toLocalDate().isLeapYear() ? 1 : 0);
                case 'o' -> out.append(t.get(IsoFields.WEEK_BASED_YEAR));
                case 'Y' -> out.append(t.getYear());
                case 'y' -> out.append(two(t.getYear() % 100));
                case 'a' -> out.append(t.getHour() < 12 ? "am" : "pm");
                case 'A' -> out.append(t.getHour() < 12 ? "AM" : "PM");
                case 'g' -> out.append(t.getHour() % 12 == 0 ? 12 : t.getHour() % 12);
                case 'G' -> out.append(t.getHour());
                case 'h' -> out.append(two(t.getHour() % 12 == 0 ? 12 : t.getHour() % 12));
                case 'H' -> out.append(two(t.getHour()));
                case 'i' -> out.append(two(t.getMinute()));
                case 's' -> out.append(two(t.getSecond()));
                case 'u' -> out.append("000000");
                case 'v' -> out.append("000");
                case 'e' -> out.append(zone instanceof ZoneOffset offset ? offsetName(offset) : zone.getId());
                case 'I' -> out.append(zone.getRules().isDaylightSavings(t.toInstant()) ? 1 : 0);
                case 'O' -> out.append(offset(t, false));
                case 'P' -> out.append(offset(t, true));
                case 'p' -> out.append(t.getOffset().getTotalSeconds() == 0 ? "Z" : offset(t, true));
                case 'T' -> out.append(abbreviation(t, zone));
                case 'Z' -> out.append(t.getOffset().getTotalSeconds());
                case 'c' -> out.append(format("Y-m-d\\TH:i:sP", timestamp, zone));
                case 'r' -> out.append(format("D, d M Y H:i:s O", timestamp, zone));
                case 'U' -> out.append(timestamp);
                case '\\' -> {
                    if (i + 1 < format.length()) {
                        out.append(format.charAt(++i));
                    }
                }
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String two(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }

    private static String suffix(int day) {
        if (day >= 11 && day <= 13) {
            return "th";
        }
        return switch (day % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    private static String offset(ZonedDateTime t, boolean colon) {
        int seconds = t.getOffset().getTotalSeconds();
        int minutes = Math.abs(seconds) / 60;
        return (seconds < 0 ? "-" : "+") + two(minutes / 60) + (colon ? ":" : "") + two(minutes % 60);
    }

    private static String abbreviation(ZonedDateTime t, ZoneId zone) {
        if (zone.equals(ZoneOffset.UTC) || zone.getId().equals("UTC")) {
            return "UTC";
        }
        String name = zone.getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        return name.startsWith("GMT") || name.contains("/") ? offset(t, true) : name;
    }

    // ---- strtotime ----

    private static final Pattern ISO = Pattern.compile(
            "(\\d{4})-(\\d{1,2})-(\\d{1,2})(?:[T ](\\d{1,2}):(\\d{2})(?::(\\d{2})(?:\\.\\d+)?)?)?\\s*"
                    + "(Z|[+-]\\d{2}:?\\d{2}|UTC|GMT)?");
    private static final Pattern RFC2822 = Pattern.compile(
            "(?:[A-Za-z]{3},\\s*)?(\\d{1,2})\\s+([A-Za-z]{3})[a-z]*\\s+(\\d{4})\\s+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*"
                    + "([+-]\\d{4}|GMT|UTC|Z)?");
    private static final Pattern RELATIVE = Pattern.compile(
            "([+-]?\\s*\\d+)\\s*(sec|second|min|minute|hour|day|week|fortnight|month|year)s?(\\s+ago)?");

    /** The formats WordPress uses: ISO dates and times, RFC 2822, @timestamp, now/today/…, relative offsets. */
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
        ZonedDateTime t = Instant.ofEpochSecond(base).atZone(zone);
        String rest = text;
        if (rest.startsWith("now")) {
            rest = rest.substring(3).trim();
        } else if (rest.startsWith("today") || rest.startsWith("midnight")) {
            t = t.truncatedTo(ChronoUnit.DAYS);
            rest = rest.substring(rest.startsWith("today") ? 5 : 8).trim();
        } else if (rest.startsWith("tomorrow")) {
            t = t.truncatedTo(ChronoUnit.DAYS).plusDays(1);
            rest = rest.substring(8).trim();
        } else if (rest.startsWith("yesterday")) {
            t = t.truncatedTo(ChronoUnit.DAYS).minusDays(1);
            rest = rest.substring(9).trim();
        }
        Matcher relative = RELATIVE.matcher(rest);
        int position = 0;
        while (position < rest.length()) {
            if (!relative.find(position) || relative.start() != position) {
                return null;
            }
            long amount = Long.parseLong(relative.group(1).replace(" ", "").replace("+", ""));
            if (relative.group(3) != null) {
                amount = -amount;
            }
            t = switch (relative.group(2)) {
                case "sec", "second" -> t.plusSeconds(amount);
                case "min", "minute" -> t.plusMinutes(amount);
                case "hour" -> t.plusHours(amount);
                case "day" -> t.plusDays(amount);
                case "week" -> t.plusWeeks(amount);
                case "fortnight" -> t.plusWeeks(2 * amount);
                case "month" -> t.plusMonths(amount);
                default -> t.plusYears(amount);
            };
            position = relative.end();
            while (position < rest.length() && rest.charAt(position) == ' ') {
                position++;
            }
        }
        return t.toEpochSecond();
    }

    private static Long dateTime(Matcher m, ZoneId zone, int offsetGroup) {
        try {
            LocalDate date = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)));
            LocalTime time = m.group(4) == null ? LocalTime.MIDNIGHT : LocalTime.of(Integer.parseInt(m.group(4)),
                    Integer.parseInt(m.group(5)), m.group(6) == null ? 0 : Integer.parseInt(m.group(6)));
            return date.atTime(time).atZone(offsetOr(m.group(offsetGroup), zone)).toEpochSecond();
        } catch (DateTimeException e) {
            return null;
        }
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
