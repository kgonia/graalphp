package pl.codeloom.php.builtins;

import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpThrowable;
import pl.codeloom.php.runtime.SourceLocation;
import pl.codeloom.php.runtime.Str;

/**
 * The built-ins' per-context state: the ini settings, error handling's, pattern caches and preg_last_error(),
 * json_last_error(), the mbstring substitute character. A context owns one; the built-in functions themselves are language-level.
 */
public final class BuiltinState {
    final Ini ini;
    final ErrorFunctions.State errors = new ErrorFunctions.State();
    final PcreFunctions pcre = new PcreFunctions();
    final JsonFunctions.State json = new JsonFunctions.State();
    final MbFunctions.State mb = new MbFunctions.State();
    /** ext/curl's handles and the exception a callback left pending. */
    final CurlFunctions.State curl = new CurlFunctions.State();
    final WeakReferences.State weakReferences = new WeakReferences.State();
    /** serialize() and unserialize() calls in progress, which nested ones may share. */
    final Serialization.State serialization = new Serialization.State();
    /** PG(have_called_openlog): php_syslog opens the log once a request. */
    boolean calledOpenlog;
    /**
     * setlocale()'s locale per glibc category (LC_CTYPE 0 to LC_IDENTIFICATION 12; LC_ALL 6 unused): LC_CTYPE is
     * C.UTF-8 from startup (zend_reset_lc_ctype_locale), the others C.
     */
    final String[] locales = {"C.UTF-8", "C", "C", "C", "C", "C", "", "C", "C", "C", "C", "C", "C"};
    /** The environment once putenv() changed it (null before: the process's). */
    java.util.Map<String, String> environment;
    /** strtok()'s string and position between calls. */
    byte[] strtok;
    int strtokPosition;
    /** mysqli_report()'s mode: MYSQLI_REPORT_ERROR | MYSQLI_REPORT_STRICT unless changed, as PHP 8.1+. */
    long mysqliReport = 3;
    /** mysqli_connect_errno() / mysqli_connect_error(): the last connection attempt's. */
    int mysqliConnectErrno;
    String mysqliConnectError;
    /** Database connections this context opened, closed when it is finalized. */
    final java.util.List<java.sql.Connection> openConnections = new java.util.ArrayList<>();
    /**
     * Streams this context opened and hasn't closed, closed (releasing their flock()s) when it is finalized, as php
     * closes a request's resources at its end. Held weakly: php frees a stream nothing refers to, and an
     * unreachable stream's channel is closed by the JDK's cleaner.
     */
    final java.util.Set<FileFunctions.Stream> openStreams =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    /** FG(default_context): the request's default stream context, made on first use. */
    StreamContext defaultStreamContext;
    /** The request's input as ext/filter keeps it (filter_input()). */
    final FilterFunctions.Input filterInput = new FilterFunctions.Input();
    /** The default filter that failed while the SAPI registered the request's variables, else 0: no script runs. */
    long startupFailure;

    /** cgi: the SAPI, whose defaults php.ini starts from; ini: the php.Ini option's settings. */
    public BuiltinState(boolean cgi, java.util.Map<String, String> ini) {
        this.ini = new Ini(cgi, ini);
    }

    public Ini ini() {
        return ini;
    }

    public ErrorFunctions.State errors() {
        return errors;
    }

    /**
     * $_SERVER and $_ENV as the SAPI made them, kept for filter_input() (cgi: the CGI SAPI's); the $_SERVER to install,
     * its input variables through filter.default.
     */
    public PhpArray requestGlobals(PhpContext context, PhpArray server, PhpArray env, boolean cgi) {
        filterInput.globals(server, env, cgi);
        return FilterFunctions.registeredArray(context, server, filterInput::serverInput);
    }

    /**
     * The errors of the module startup php-cli would display before the script: the deprecation of filter.default
     * (UpdateDefaultFilter's, for a filter other than unsafe_raw).
     */
    public void startupErrors(PhpContext context, boolean cgi) {
        if (!cgi && ini.defaultFilter() != FilterFunctions.DEFAULT) {
            ErrorFunctions.raise(context, ErrorFunctions.E_DEPRECATED,
                    Str.of("The filter.default ini setting is deprecated"), SourceLocation.UNKNOWN);
        }
    }

    /** The exception that ends the request before the script runs (startupError), once. */
    public PhpThrowable takeStartupError() {
        long filter = startupFailure;
        startupFailure = 0;
        return filter == 0 ? null : FilterFunctions.startupError(filter);
    }

    /** Closes what the context left open. */
    public void close() {
        curl.close();
        for (FileFunctions.Stream stream : new java.util.ArrayList<>(openStreams)) {
            FileFunctions.closeStream(stream);
        }
        openStreams.clear();
        pcre.close();
        for (java.sql.Connection connection : openConnections) {
            try {
                connection.close();
            } catch (java.sql.SQLException ignored) {
                // closing anyway
            }
        }
        openConnections.clear();
    }
}
