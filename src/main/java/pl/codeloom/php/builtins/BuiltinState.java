package pl.codeloom.php.builtins;

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

    /** Closes what the context left open. */
    public void close() {
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
