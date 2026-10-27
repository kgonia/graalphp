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
 * are unknown: ini_get() and ini_set() give false for them, as for an extension that isn't loaded.
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
     * that declares it (as the module registry names it).
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
        if (!userCanChange(name)) {
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
     * current values and its access (or only the current value); with an extension, only its directives. The
     * module registry's keys are lowercase ("core"), and the name isn't lowered.
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
            if (module != null && !module.equals(directive.getValue().extension())) {
                continue;
            }
            String name = directive.getKey();
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
