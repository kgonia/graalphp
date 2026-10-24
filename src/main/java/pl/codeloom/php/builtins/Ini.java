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
 * are unknown: ini_get() and ini_set() give false for them.
 */
public final class Ini {
    /** php_get_display_errors_mode. */
    public enum DisplayMode { OFF, STDOUT, STDERR }

    /** PHP_INI_ALL: every directive here can be changed anywhere. */
    private static final long ACCESS_ALL = 7;

    /** Each directive's default, by name (null: no value, which ini_get() shows as ""). */
    private static final Map<String, String> DEFAULTS = defaults();

    private static Map<String, String> defaults() {
        Map<String, String> defaults = new TreeMap<>();
        defaults.put("display_errors", "1");
        defaults.put("error_append_string", null);
        defaults.put("error_log", null);
        defaults.put("error_prepend_string", null);
        defaults.put("error_reporting", null);
        defaults.put("fatal_error_backtraces", "1");
        defaults.put("html_errors", "1");
        defaults.put("ignore_repeated_errors", "0");
        defaults.put("ignore_repeated_source", "0");
        defaults.put("log_errors", "0");
        defaults.put("zend.exception_ignore_args", "0");
        defaults.put("zend.exception_string_param_max_len", "15");
        return Collections.unmodifiableMap(defaults);
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

    /**
     * The startup values: php-src's defaults, the CLI's own (html_errors=0, from php_cli.c's HARDCODED_INI), then
     * the settings; a setting its directive rejects leaves the default, as at php-src's startup.
     */
    Ini(boolean cgi, Map<String, String> settings) {
        for (Map.Entry<String, String> directive : DEFAULTS.entrySet()) {
            boolean accepted = set(directive.getKey(), directive.getValue());
            assert accepted : directive;
        }
        if (!cgi) {
            set("html_errors", "0");
        }
        for (Map.Entry<String, String> setting : settings.entrySet()) {
            set(setting.getKey(), setting.getValue());
        }
        startup.putAll(values);
    }

    public boolean isKnown(String name) {
        return DEFAULTS.containsKey(name);
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
        if (isKnown(name)) {
            set(name, startup.get(name));
        }
    }

    /** The directive's OnUpdate handler: the value parsed into its setting; false if the directive rejects it. */
    private boolean update(String name, String value) {
        switch (name) {
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
            case "zend.exception_string_param_max_len" -> {
                long length = value == null ? 0 : atol(value);
                if (length < 0 || length > 1_000_000) {
                    return false;
                }
                exceptionStringParamMaxLen = (int) length;
            }
            default -> {
                return false;
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
        r.fn("ini_set", (context, a) -> iniSet(context.ini(), Str.java(Args.str(a, 0)), Args.get(a, 1)));
        r.fn("ini_alter", (context, a) -> iniSet(context.ini(), Str.java(Args.str(a, 0)), Args.get(a, 1)));
        r.fn("ini_restore", (context, a) -> {
            context.ini().restore(Str.java(Args.str(a, 0)));
            return PhpNull.NULL;
        });
        r.fn("ini_get_all", (context, a) -> iniGetAll(context, a));
    }

    /** ini_set(): the old value, or false for an unknown directive or a value it doesn't take. */
    @TruffleBoundary
    private static Object iniSet(Ini ini, String name, Object newValue) {
        if (!ini.isKnown(name)) {
            return Boolean.FALSE;
        }
        String old = ini.get(name);
        if (!ini.set(name, Str.java(Convert.toStr(PhpRef.deref(newValue))))) {
            return Boolean.FALSE;
        }
        return old == null ? Str.EMPTY : Str.of(old);
    }

    /**
     * ini_get_all(?string $extension = null, bool $details = true): every directive by name, with its startup and
     * current values and its access (or only the current value). The directives here are all Core's, which the
     * module registry knows as "core" (its keys are lowercase; the name isn't lowered).
     */
    @TruffleBoundary
    private static Object iniGetAll(PhpContext context, Object[] a) {
        Object extension = Args.get(a, 0, PhpNull.NULL);
        if (extension != PhpNull.NULL && !Str.java(Convert.toStr(extension)).equals("core")) {
            ErrorFunctions.raise(context, ErrorFunctions.E_WARNING, Str.of("ini_get_all(): Extension \""
                    + Str.java(Convert.toStr(extension)) + "\" cannot be found"), SourceLocation.current());
            return Boolean.FALSE;
        }
        boolean details = Args.bool(a, 1, true);
        Ini ini = context.ini();
        PhpArray all = new PhpArray();
        for (String name : DEFAULTS.keySet()) {
            Object current = valueOf(ini.get(name));
            if (details) {
                PhpArray entry = new PhpArray();
                entry.put(Str.of("global_value"), valueOf(ini.startup.get(name)));
                entry.put(Str.of("local_value"), current);
                entry.put(Str.of("access"), ACCESS_ALL);
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
