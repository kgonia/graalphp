package pl.codeloom.php.builtins;

/**
 * The built-ins' per-context state: pattern caches and preg_last_error(), json_last_error(), the mbstring
 * substitute character. A context owns one; the built-in functions themselves are language-level.
 */
public final class BuiltinState {
    final PcreFunctions pcre = new PcreFunctions();
    final JsonFunctions.State json = new JsonFunctions.State();
    final MbFunctions.State mb = new MbFunctions.State();
    /** strtok()'s string and position between calls. */
    byte[] strtok;
    int strtokPosition;
    /** mysqli_connect_errno() / mysqli_connect_error(): the last connection attempt's. */
    int mysqliConnectErrno;
    String mysqliConnectError;
    /** Database connections this context opened, closed when it is finalized. */
    final java.util.List<java.sql.Connection> openConnections = new java.util.ArrayList<>();

    /** Closes what the context left open. */
    public void close() {
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
