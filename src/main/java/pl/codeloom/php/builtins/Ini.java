package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.runtime.Str;

import java.util.HashMap;
import java.util.Map;

/**
 * The php.ini directives the runtime implements, per context: their startup values (php-src's defaults for the
 * SAPI, then the php.Ini option, as php.ini and -d give them) and the values ini_set() changed. Other directives
 * are unknown: ini_get() and ini_set() give false for them.
 */
public final class Ini {
    /** A directive's default (null: no value, which ini_get() shows as ""), and which values it accepts. */
    private record Directive(String defaultValue, java.util.function.Predicate<String> accepts) {
    }

    private static final java.util.function.Predicate<String> ANY = value -> true;

    private static final Map<String, Directive> DIRECTIVES = Map.ofEntries(
            Map.entry("display_errors", new Directive("1", ANY)),
            Map.entry("error_reporting", new Directive(null, ANY)),
            Map.entry("log_errors", new Directive("0", ANY)),
            Map.entry("error_log", new Directive(null, ANY)),
            Map.entry("html_errors", new Directive("1", ANY)),
            Map.entry("error_prepend_string", new Directive(null, ANY)),
            Map.entry("error_append_string", new Directive(null, ANY)),
            Map.entry("ignore_repeated_errors", new Directive("0", ANY)),
            Map.entry("ignore_repeated_source", new Directive("0", ANY)),
            Map.entry("fatal_error_backtraces", new Directive("1", ANY)),
            Map.entry("zend.exception_ignore_args", new Directive("0", ANY)),
            Map.entry("zend.exception_string_param_max_len", new Directive("15", value -> {
                long length = atol(value);
                return length >= 0 && length <= 1_000_000;
            })));

    private final Map<String, String> startup = new HashMap<>();
    private final Map<String, String> values = new HashMap<>();

    /**
     * The startup values: php-src's defaults, the CLI's own (html_errors=0, from php_cli.c's HARDCODED_INI), then
     * the settings, "name=value" lines.
     */
    Ini(boolean cgi, String settings) {
        for (Map.Entry<String, Directive> directive : DIRECTIVES.entrySet()) {
            startup.put(directive.getKey(), directive.getValue().defaultValue());
        }
        if (!cgi) {
            startup.put("html_errors", "0");
        }
        for (String line : settings.split("\n")) {
            int equals = line.indexOf('=');
            if (equals > 0 && DIRECTIVES.containsKey(line.substring(0, equals).trim())) {
                startup.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
            }
        }
        values.putAll(startup);
    }

    public boolean isKnown(String name) {
        return DIRECTIVES.containsKey(name);
    }

    /** The value; null if it has none. */
    public String get(String name) {
        return values.get(name);
    }

    /** Sets a known directive to a value it accepts; false otherwise, as ini_set() fails. */
    @TruffleBoundary
    public boolean set(String name, String value) {
        Directive directive = DIRECTIVES.get(name);
        if (directive == null || !directive.accepts().test(value)) {
            return false;
        }
        values.put(name, value);
        return true;
    }

    /** ini_restore(): back to the startup value. */
    public void restore(String name) {
        if (isKnown(name)) {
            values.put(name, startup.get(name));
        }
    }

    /** A boolean directive as zend_ini_parse_bool reads it: on, yes, true, or a non-zero number. */
    public boolean bool(String name) {
        String value = values.get(name);
        if (value == null) {
            return false;
        }
        String lower = Str.lower(value);
        return lower.equals("true") || lower.equals("yes") || lower.equals("on") || atol(value) != 0;
    }

    public long lng(String name) {
        String value = values.get(name);
        return value == null ? 0 : atol(value);
    }

    /** C's atol: the leading decimal number, after whitespace and a sign; 0 if there is none. */
    static long atol(String text) {
        int i = 0;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        boolean negative = false;
        if (i < text.length() && (text.charAt(i) == '-' || text.charAt(i) == '+')) {
            negative = text.charAt(i) == '-';
            i++;
        }
        long value = 0;
        while (i < text.length() && text.charAt(i) >= '0' && text.charAt(i) <= '9') {
            value = value * 10 + (text.charAt(i++) - '0');
        }
        return negative ? -value : value;
    }
}
