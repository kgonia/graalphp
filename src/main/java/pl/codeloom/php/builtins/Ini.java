package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The php.ini directives the runtime implements, per context: their startup values (php-src's defaults for the
 * SAPI, then the php.Ini option, as php.ini and -d give them) and the values ini_set() changed. As in php-src, a
 * directive's OnUpdate handler parses a value into the setting the runtime reads, or rejects it. Other directives
 * are unknown: ini_get() and ini_set() give false for them, as for an extension that isn't loaded. The directives
 * are Core's (main.c) and the few of ext/standard's the runtime implements.
 */
public final class Ini {
    /** php_get_display_errors_mode. */
    public enum DisplayMode { OFF, STDOUT, STDERR }

    /** PHP_INI_USER, PHP_INI_PERDIR, PHP_INI_SYSTEM: where a directive may be changed (ini_set() needs USER). */
    private static final long USER = 1;
    private static final long PERDIR = 2;
    private static final long SYSTEM = 4;
    private static final long ALL = USER | PERDIR | SYSTEM;

    /**
     * A directive's default (null: no value, which ini_get() shows as ""), where it may be changed, and the extension
     * that declares it, as ini_get_all() selects them ("core" for main.c's).
     */
    private record Directive(String value, long access, String extension) {
        Directive(String value, long access) {
            this(value, access, "core");
        }
    }

    /** The directives, by name: Core's as main.c declares them, then the extensions'. */
    private static final Map<String, Directive> DIRECTIVES = directives();

    private static Map<String, Directive> directives() {
        Map<String, Directive> directives = new TreeMap<>();
        directives.put("allow_url_fopen", new Directive("1", SYSTEM));
        directives.put("arg_separator.output", new Directive("&", ALL));
        directives.put("default_mimetype", new Directive("text/html", ALL));
        directives.put("default_socket_timeout", new Directive("60", ALL, "standard"));
        directives.put("disable_functions", new Directive("", SYSTEM));
        directives.put("display_errors", new Directive("1", ALL));
        directives.put("error_append_string", new Directive(null, ALL));
        directives.put("error_log", new Directive(null, ALL));
        directives.put("error_prepend_string", new Directive(null, ALL));
        directives.put("error_reporting", new Directive(null, ALL));
        directives.put("fatal_error_backtraces", new Directive("1", ALL));
        directives.put("file_uploads", new Directive("1", SYSTEM));
        directives.put("html_errors", new Directive("1", ALL));
        directives.put("ignore_repeated_errors", new Directive("0", ALL));
        directives.put("ignore_repeated_source", new Directive("0", ALL));
        directives.put("log_errors", new Directive("0", ALL));
        directives.put("mail.add_x_header", new Directive("0", SYSTEM | PERDIR));
        directives.put("max_execution_time", new Directive("30", ALL));
        directives.put("max_file_uploads", new Directive("20", SYSTEM | PERDIR));
        directives.put("max_input_time", new Directive("-1", SYSTEM | PERDIR));
        directives.put("max_input_vars", new Directive("1000", SYSTEM | PERDIR));
        directives.put("memory_limit", new Directive("128M", ALL));
        directives.put("open_basedir", new Directive(null, ALL));
        directives.put("post_max_size", new Directive("8M", SYSTEM | PERDIR));
        directives.put("sendmail_from", new Directive(null, ALL));
        directives.put("sendmail_path", new Directive("/usr/sbin/sendmail -t -i", SYSTEM));
        directives.put("upload_max_filesize", new Directive("2M", SYSTEM | PERDIR));
        directives.put("upload_tmp_dir", new Directive(null, SYSTEM));
        directives.put("zend.exception_ignore_args", new Directive("0", ALL));
        directives.put("zend.exception_string_param_max_len", new Directive("15", ALL));
        // ext/curl's PHP_INI_BEGIN
        directives.put("curl.cainfo", new Directive("", SYSTEM, "curl"));
        return Collections.unmodifiableMap(directives);
    }

    private final Map<String, String> startup = new HashMap<>();
    private final Map<String, String> values = new HashMap<>();

    private DisplayMode displayErrors;
    /** EG(error_reporting): set by the directive, narrowed by `@`. */
    private int errorReporting;
    private boolean logErrors;
    private String errorLog;
    private boolean htmlErrors;
    private String errorPrependString;
    private String errorAppendString;
    private boolean ignoreRepeatedErrors;
    private boolean ignoreRepeatedSource;
    private boolean fatalErrorBacktraces;
    private boolean exceptionIgnoreArgs;
    private int exceptionStringParamMaxLen;
    private String argSeparatorOutput;
    /** FG(default_socket_timeout), in seconds. */
    private long defaultSocketTimeout;
    /** The warning the last OnUpdate handler raised (zend_ini_parse_quantity_warn's), for ini_set() to report. */
    private String updateWarning;

    /**
     * The startup values: php-src's defaults, the CLI's own (from php_cli.c's HARDCODED_INI), then
     * the settings; a setting its directive rejects leaves the default, as at php-src's startup.
     */
    Ini(boolean cgi, Map<String, String> settings) {
        for (Map.Entry<String, Directive> directive : DIRECTIVES.entrySet()) {
            boolean accepted = set(directive.getKey(), directive.getValue().value());
            assert accepted : directive;
        }
        if (!cgi) {
            set("html_errors", "0");
            set("max_execution_time", "0");
        }
        for (Map.Entry<String, String> setting : settings.entrySet()) {
            set(setting.getKey(), setting.getValue());
        }
        startup.putAll(values);
    }

    public boolean isKnown(String name) {
        return DIRECTIVES.containsKey(name);
    }

    /** Whether a script may change the directive (PHP_INI_USER): ini_set() and ini_restore() need it. */
    private static boolean userCanChange(String name) {
        Directive directive = DIRECTIVES.get(name);
        return directive != null && (directive.access() & USER) != 0;
    }

    /** The value; null if it has none. */
    public String get(String name) {
        return values.get(name);
    }

    /** Sets a known directive to a value it accepts; false otherwise, as ini_set() fails. */
    @TruffleBoundary
    public boolean set(String name, String value) {
        if (!isKnown(name) || !update(name, value)) {
            return false;
        }
        values.put(name, value);
        return true;
    }

    /** ini_restore(): back to the startup value. */
    public void restore(String name) {
        if (userCanChange(name)) {
            set(name, startup.get(name));
        }
    }

    /** The directive's OnUpdate handler: the value parsed into its setting; false if the directive rejects it. */
    private boolean update(String name, String value) {
        switch (name) {
            case "arg_separator.output" -> {
                if (value == null || value.isEmpty()) {
                    return false; // OnUpdateStrNotEmpty
                }
                argSeparatorOutput = value;
            }
            case "display_errors" -> displayErrors = displayMode(value);
            case "error_reporting" -> errorReporting = value == null ? ErrorFunctions.E_ALL : (int) atol(value);
            case "log_errors" -> logErrors = bool(value);
            case "error_log" -> errorLog = value;
            case "html_errors" -> htmlErrors = bool(value);
            case "error_prepend_string" -> errorPrependString = value;
            case "error_append_string" -> errorAppendString = value;
            case "ignore_repeated_errors" -> ignoreRepeatedErrors = bool(value);
            case "ignore_repeated_source" -> ignoreRepeatedSource = bool(value);
            case "fatal_error_backtraces" -> fatalErrorBacktraces = bool(value);
            case "zend.exception_ignore_args" -> exceptionIgnoreArgs = bool(value);
            case "default_socket_timeout" -> defaultSocketTimeout = quantity(name, value); // OnUpdateLong
            case "upload_tmp_dir" -> {
                if (value != null && value.isEmpty()) {
                    return false; // OnUpdateStringUnempty
                }
            }
            case "max_input_vars" -> {
                if (value != null && atol(value) < 0) {
                    return false; // OnUpdateLongGEZero
                }
            }
            case "zend.exception_string_param_max_len" -> {
                long length = value == null ? 0 : atol(value);
                if (length < 0 || length > 1_000_000) {
                    return false;
                }
                exceptionStringParamMaxLen = (int) length;
            }
            default -> {
                // stored only: nothing in the runtime reads it (memory_limit, max_execution_time and the upload
                // and mail directives aren't enforced), but scripts read and set it
            }
        }
        return true;
    }

    public DisplayMode displayErrors() {
        return displayErrors;
    }

    public int errorReporting() {
        return errorReporting;
    }

    /** Changes EG(error_reporting) only, as `@` does: the directive's value stays. */
    public void setErrorReporting(int level) {
        errorReporting = level;
    }

    public boolean logErrors() {
        return logErrors;
    }

    public String errorLog() {
        return errorLog;
    }

    public boolean htmlErrors() {
        return htmlErrors;
    }

    public String errorPrependString() {
        return errorPrependString;
    }

    public String errorAppendString() {
        return errorAppendString;
    }

    public boolean ignoreRepeatedErrors() {
        return ignoreRepeatedErrors;
    }

    public boolean ignoreRepeatedSource() {
        return ignoreRepeatedSource;
    }

    public boolean fatalErrorBacktraces() {
        return fatalErrorBacktraces;
    }

    public boolean exceptionIgnoreArgs() {
        return exceptionIgnoreArgs;
    }

    public int exceptionStringParamMaxLen() {
        return exceptionStringParamMaxLen;
    }

    public String argSeparatorOutput() {
        return argSeparatorOutput;
    }

    public long defaultSocketTimeout() {
        return defaultSocketTimeout;
    }

    /**
     * The warning the last change raised, cleared. A startup value's (the php.Ini option's) is dropped: php-src
     * prints it while starting up, before any script runs.
     */
    String takeWarning() {
        String warning = updateWarning;
        updateWarning = null;
        return warning;
    }

    /** zend_ini_parse_quantity_warn: the value, keeping the warning for an invalid quantity. */
    private long quantity(String name, String value) {
        long[] result = new long[1];
        String error = parseQuantity(value == null ? "" : value, result);
        if (error != null) {
            updateWarning = "Invalid \"" + name + "\" setting. " + error;
        }
        return result[0];
    }

    /**
     * zend_ini_parse_quantity (signed): a decimal, octal (leading 0, or 0o), hexadecimal (0x) or binary (0b)
     * integer, with an optional K, M or G multiplier. result[0] receives the value; the return is the error text
     * when the value isn't a clean quantity (the value is then the one php-src falls back to), or null.
     */
    static String parseQuantity(String text, long[] result) {
        byte[] str = Str.bytes(Str.of(text));
        result[0] = 0;
        int digits = 0;
        int end = str.length;
        while (digits < end && isWhitespace(at(str, digits))) {
            digits++;
        }
        while (digits < end && isWhitespace(at(str, end - 1))) {
            end--;
        }
        if (digits == end) {
            return null;
        }
        boolean negative = false;
        if (at(str, digits) == '+') {
            digits++;
        } else if (at(str, digits) == '-') {
            negative = true;
            digits++;
        }
        if (!isDigit(at(str, digits))) {
            return "Invalid quantity \"" + escaped(str, 0, str.length) + "\": no valid leading digits, interpreting as "
                    + "\"0\" for backwards compatibility";
        }
        int base = 0;
        if (at(str, digits) == '0' && !isDigit(at(str, digits + 1))) {
            if (digits + 1 == end) {
                return null;
            }
            switch (at(str, digits + 1)) {
                case 'g', 'G', 'm', 'M', 'k', 'K' -> base = -1;
                case 'x', 'X' -> base = 16;
                case 'o', 'O' -> base = 8;
                case 'b', 'B' -> base = 2;
                default -> {
                    return "Invalid prefix \"0" + (char) at(str, digits + 1) + "\", interpreting as \"0\" for backwards "
                            + "compatibility";
                }
            }
            if (base > 0) {
                digits += 2;
                if (digits == end || digits != consumeQuantityPrefix(str, digits, end, base)) {
                    return "Invalid quantity \"" + escaped(str, 0, str.length) + "\": no digits after base prefix, "
                            + "interpreting as \"0\" for backwards compatibility";
                }
            } else {
                base = 0;
            }
        }
        long[] parsed = strtoul(str, digits, base);
        long value = parsed[0];
        int digitsEnd = (int) parsed[1];
        boolean overflow = false;
        if (parsed[2] != 0) {
            overflow = true;
        } else if (negative && value == Long.MIN_VALUE) {
            value = Long.MIN_VALUE; // PHP_INT_MIN
        } else if (value < 0) {
            overflow = true;
        } else if (negative) {
            value = -value;
        }
        if (digitsEnd == digits) {
            return "Invalid quantity \"" + escaped(str, 0, str.length) + "\": no valid leading digits, interpreting as "
                    + "\"0\" for backwards compatibility";
        }
        while (digitsEnd < end && isWhitespace(at(str, digitsEnd))) {
            digitsEnd++;
        }
        if (digitsEnd != end) {
            long factor;
            switch (at(str, end - 1)) {
                case 'g', 'G' -> factor = 1L << 30;
                case 'm', 'M' -> factor = 1L << 20;
                case 'k', 'K' -> factor = 1L << 10;
                default -> {
                    result[0] = value;
                    return "Invalid quantity \"" + escaped(str, 0, str.length) + "\": unknown multiplier \""
                            + escaped(str, end - 1, end) + "\", interpreting as \"" + escaped(str, 0, digitsEnd)
                            + "\" for backwards compatibility";
                }
            }
            if (!overflow) {
                overflow = value > 0 ? value > Long.MAX_VALUE / factor : value < Long.MIN_VALUE / factor;
            }
            value *= factor;
            if (digitsEnd != end - 1) {
                result[0] = value;
                return "Invalid quantity \"" + escaped(str, 0, str.length) + "\", interpreting as \""
                        + escaped(str, 0, digitsEnd) + escaped(str, end - 1, end) + "\" for backwards compatibility";
            }
        }
        result[0] = value;
        if (overflow) {
            return "Invalid quantity \"" + escaped(str, 0, str.length) + "\": value is out of range, using overflow "
                    + "result for backwards compatibility";
        }
        return null;
    }

    /** zend_ini_consume_quantity_prefix: past whitespace, a sign and a base prefix (0b only outside base 16). */
    private static int consumeQuantityPrefix(byte[] str, int digits, int end, int base) {
        int consumed = digits;
        while (consumed < end && isWhitespace(at(str, consumed))) {
            consumed++;
        }
        if (at(str, consumed) == '+' || at(str, consumed) == '-') {
            consumed++;
        }
        if (at(str, consumed) == '0' && !isDigit(at(str, consumed + 1))) {
            if (consumed + 1 == end) {
                return consumed;
            }
            switch (at(str, consumed + 1)) {
                case 'x', 'X', 'o', 'O' -> consumed += 2;
                case 'b', 'B' -> {
                    if (base != 16) {
                        consumed += 2;
                    }
                }
                default -> {
                }
            }
        }
        return consumed;
    }

    /**
     * C's strtoul from index i: {value, the index after the digits (i when there are none), 1 on overflow (ERANGE,
     * the value then ULONG_MAX)}. Base 0 reads a 0x prefix as hexadecimal and a leading 0 as octal.
     */
    private static long[] strtoul(byte[] str, int i, int base) {
        int p = i;
        while (isWhitespace(at(str, p))) {
            p++;
        }
        boolean negative = false;
        if (at(str, p) == '+' || at(str, p) == '-') {
            negative = at(str, p) == '-';
            p++;
        }
        if ((base == 0 || base == 16) && at(str, p) == '0' && (at(str, p + 1) | 0x20) == 'x'
                && Character.digit(at(str, p + 2), 16) >= 0) {
            p += 2;
            base = 16;
        } else if (base == 0) {
            base = at(str, p) == '0' ? 8 : 10;
        }
        int start = p;
        long value = 0;
        boolean overflow = false;
        for (int d; (d = Character.digit(at(str, p), base)) >= 0 && at(str, p) < 0x80; p++) {
            long high = Math.unsignedMultiplyHigh(value, base);
            long product = value * base;
            long sum = product + d;
            if (high != 0 || Long.compareUnsigned(sum, product) < 0) {
                overflow = true;
            }
            value = sum;
        }
        if (p == start) {
            return new long[] {0, i, 0};
        }
        if (overflow) {
            return new long[] {-1, p, 1};
        }
        return new long[] {negative ? -value : value, p, 0};
    }

    /** The byte at i, or 0 past the end (C's terminating NUL). */
    private static int at(byte[] str, int i) {
        return i < str.length ? str[i] & 0xFF : 0;
    }

    private static boolean isDigit(int c) {
        return c >= '0' && c <= '9';
    }

    /** zend_is_whitespace. */
    private static boolean isWhitespace(int c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x0B || c == '\f';
    }

    /** smart_str_append_escaped of bytes [from, to). */
    private static String escaped(byte[] str, int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int i = from; i < to; i++) {
            int c = str[i] & 0xFF;
            if (c >= 32 && c != '\\' && c <= 126) {
                out.append((char) c);
                continue;
            }
            out.append('\\');
            switch (c) {
                case '\n' -> out.append('n');
                case '\r' -> out.append('r');
                case '\t' -> out.append('t');
                case '\f' -> out.append('f');
                case 0x0B -> out.append('v');
                case '\\' -> out.append('\\');
                case 0x1B -> out.append('e');
                default -> out.append('x').append(String.format("%02X", c));
            }
        }
        return out.toString();
    }

    /** A boolean directive as zend_ini_parse_bool reads it: on, yes, true, or a non-zero number. */
    private static boolean bool(String value) {
        if (value == null) {
            return false;
        }
        String lower = Str.lower(value);
        return lower.equals("true") || lower.equals("yes") || lower.equals("on") || atol(value) != 0;
    }

    /** php_get_display_errors_mode: on, yes, true and stdout show errors; stderr; else the number's mode. */
    private static DisplayMode displayMode(String value) {
        if (value == null) {
            return DisplayMode.STDOUT;
        }
        String lower = Str.lower(value);
        if (lower.equals("on") || lower.equals("yes") || lower.equals("true") || lower.equals("stdout")) {
            return DisplayMode.STDOUT;
        }
        if (lower.equals("stderr")) {
            return DisplayMode.STDERR;
        }
        return switch ((int) (atol(value) & 0xFF)) {
            case 0 -> DisplayMode.OFF;
            case 2 -> DisplayMode.STDERR;
            default -> DisplayMode.STDOUT;
        };
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

    // ---- the functions ----

    static void register(Registry r) {
        r.fn("ini_get", (context, a) -> {
            Ini ini = context.ini();
            String name = Str.java(Args.str(a, 0));
            if (!ini.isKnown(name)) {
                return Boolean.FALSE;
            }
            String value = ini.get(name);
            return value == null ? Str.EMPTY : Str.of(value);
        });
        r.fn("ini_set", (context, a) -> iniSet(context, Str.java(Args.str(a, 0)), Args.get(a, 1)));
        r.fn("ini_alter", (context, a) -> iniSet(context, Str.java(Args.str(a, 0)), Args.get(a, 1)));
        r.fn("ini_restore", (context, a) -> {
            context.ini().restore(Str.java(Args.str(a, 0)));
            warn(context);
            return PhpNull.NULL;
        });
        r.fn("ini_get_all", (context, a) -> iniGetAll(context, a));
    }

    /** ini_set(): the old value, or false for an unknown directive or a value it doesn't take. */
    @TruffleBoundary
    private static Object iniSet(PhpContext context, String name, Object newValue) {
        if (!userCanChange(name)) {
            return Boolean.FALSE;
        }
        Ini ini = context.ini();
        String old = ini.get(name);
        boolean accepted = ini.set(name, Str.java(Convert.toStr(PhpRef.deref(newValue))));
        warn(context);
        if (!accepted) {
            return Boolean.FALSE;
        }
        return old == null ? Str.EMPTY : Str.of(old);
    }

    /** Raises the warning the directive's OnUpdate handler gave (zend_error, without a function's name). */
    private static void warn(PhpContext context) {
        String warning = context.ini().takeWarning();
        if (warning != null) {
            ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of(warning), SourceLocation.current());
        }
    }

    /**
     * ini_get_all(?string $extension = null, bool $details = true): every directive by name, with its startup and
     * current values and its access (or only the current value); with an extension, only its directives, except
     * that Core's module number is 0, which php_ini_get_option takes as no filter. The module registry's keys are
     * lowercase ("core"), and the name isn't lowered.
     */
    @TruffleBoundary
    private static Object iniGetAll(PhpContext context, Object[] a) {
        Object extension = Args.get(a, 0, PhpNull.NULL);
        String module = extension == PhpNull.NULL ? null : Str.java(Convert.toStr(extension));
        if (module != null && !RuntimeFunctions.EXTENSIONS.contains(module)) {
            ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of("ini_get_all(): Extension \""
                    + Str.java(Convert.toStr(extension)) + "\" cannot be found"), SourceLocation.current());
            return Boolean.FALSE;
        }
        boolean details = Args.bool(a, 1, true);
        Ini ini = context.ini();
        PhpArray all = new PhpArray();
        for (Map.Entry<String, Directive> directive : DIRECTIVES.entrySet()) {
            String name = directive.getKey();
            if (module != null && !module.equals("core") && !module.equals(directive.getValue().extension())) {
                continue;
            }
            Object current = valueOf(ini.get(name));
            if (details) {
                PhpArray entry = new PhpArray();
                entry.put(Str.of("global_value"), valueOf(ini.startup.get(name)));
                entry.put(Str.of("local_value"), current);
                entry.put(Str.of("access"), directive.getValue().access());
                all.put(Str.of(name), entry);
            } else {
                all.put(Str.of(name), current);
            }
        }
        return all;
    }

    private static Object valueOf(String value) {
        return value == null ? PhpNull.NULL : Str.of(value);
    }
}
