package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpNull;
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
                case 'e' -> out.append(zone.getId());
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
        String text = input.trim().toLowerCase(Locale.ROOT);
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
        int index = months.indexOf(name.toLowerCase(Locale.ROOT).substring(0, 3));
        return index < 0 || index % 3 != 0 ? 0 : index / 3 + 1;
    }
}
