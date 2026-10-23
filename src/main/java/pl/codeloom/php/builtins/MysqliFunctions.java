package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.Convert;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpException;
import pl.codeloom.php.runtime.PhpNull;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.Str;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import org.mariadb.jdbc.client.Completion;
import org.mariadb.jdbc.client.Context;
import org.mariadb.jdbc.client.socket.Writer;
import org.mariadb.jdbc.message.ClientMessage;
import org.mariadb.jdbc.message.server.OkPacket;
import org.mariadb.jdbc.util.constants.ServerStatus;
import java.util.ArrayList;
import java.util.List;

/**
 * The procedural mysqli API on JDBC (MariaDB Connector/J), as WordPress's wpdb uses it. Results come back as
 * mysqli returns them without MYSQLI_OPT_INT_AND_FLOAT_NATIVE: every value a string of the column's bytes, or
 * null. A `mysqli` object holds a {@link Link}, a `mysqli_result` a {@link Result}.
 */
final class MysqliFunctions {
    private MysqliFunctions() {
    }

    /** A connection and the state mysqli reports about its last operation. */
    static final class Link {
        Connection connection;
        String charset = "utf8mb4";
        int errno;
        String error = "";
        long affectedRows;
        long insertId;
    }

    /** A query's rows, read eagerly (mysqli_query's default MYSQLI_STORE_RESULT). */
    static final class Result {
        final String[] names;
        final String[] tables;
        final int[] types;
        final List<byte[][]> rows = new ArrayList<>();
        int position;
        int field;

        Result(int columns) {
            names = new String[columns];
            tables = new String[columns];
            types = new int[columns];
        }
    }

    static final long ASSOC = 1;
    static final long NUM = 2;

    static void register(Registry r) {
        r.fn("mysqli_init", (context, a) -> newLink(context));
        r.fn("mysqli_real_connect", (context, a) -> connect(context, link(a, 0, "mysqli_real_connect"), a, 1));
        r.fn("mysqli_connect", (context, a) -> {
            PhpObject object = newLink(context);
            return connect(context, object, a, 0) ? (Object) object : Boolean.FALSE;
        });
        r.fn("mysqli_connect_errno", (context, a) -> (long) context.builtinState().mysqliConnectErrno);
        r.fn("mysqli_connect_error", (context, a) -> context.builtinState().mysqliConnectError == null
                ? PhpNull.NULL : Str.of(context.builtinState().mysqliConnectError));
        r.fn("mysqli_query", (context, a) -> query(context, link(a, 0, "mysqli_query"), Str.bytes(Args.str(a, 1))));
        r.fn("mysqli_real_escape_string",
                a -> escape(link(a, 0, "mysqli_real_escape_string"), Str.bytes(Args.str(a, 1))));
        r.fn("mysqli_escape_string", a -> escape(link(a, 0, "mysqli_escape_string"), Str.bytes(Args.str(a, 1))));
        r.fn("mysqli_set_charset",
                (context, a) -> setCharset(link(a, 0, "mysqli_set_charset"), Str.java(Args.str(a, 1))));
        r.fn("mysqli_character_set_name", a -> Str.of(state(link(a, 0, "mysqli_character_set_name")).charset));
        r.fn("mysqli_select_db", a -> selectDb(link(a, 0, "mysqli_select_db"), Str.java(Args.str(a, 1))));
        r.fn("mysqli_error", a -> Str.of(state(link(a, 0, "mysqli_error")).error));
        r.fn("mysqli_errno", a -> (long) state(link(a, 0, "mysqli_errno")).errno);
        r.fn("mysqli_affected_rows", a -> state(link(a, 0, "mysqli_affected_rows")).affectedRows);
        r.fn("mysqli_insert_id", a -> state(link(a, 0, "mysqli_insert_id")).insertId);
        r.fn("mysqli_get_server_info", a -> serverInfo(link(a, 0, "mysqli_get_server_info")));
        r.fn("mysqli_get_client_info", a -> Str.of("mysqlnd 8.5.12"));
        r.fn("mysqli_ping", a -> ping(link(a, 0, "mysqli_ping")));
        r.fn("mysqli_close", a -> close(link(a, 0, "mysqli_close")));
        r.fn("mysqli_report", (context, a) -> {
            context.builtinState().mysqliReport = Args.lng(a, 0, 0);
            return Boolean.TRUE;
        });
        r.fn("mysqli_more_results", a -> Boolean.FALSE);
        r.fn("mysqli_next_result", a -> Boolean.FALSE);
        r.fn("mysqli_num_fields", a -> (long) result(a, 0, "mysqli_num_fields").names.length);
        r.fn("mysqli_num_rows", a -> (long) result(a, 0, "mysqli_num_rows").rows.size());
        r.fn("mysqli_fetch_field", (context, a) -> fetchField(context, result(a, 0, "mysqli_fetch_field")));
        r.fn("mysqli_fetch_object", (context, a) -> fetchObject(context, result(a, 0, "mysqli_fetch_object")));
        r.fn("mysqli_fetch_array", a -> fetchArray(result(a, 0, "mysqli_fetch_array"), Args.lng(a, 1, ASSOC | NUM)));
        r.fn("mysqli_fetch_assoc", a -> fetchArray(result(a, 0, "mysqli_fetch_assoc"), ASSOC));
        r.fn("mysqli_fetch_row", a -> fetchArray(result(a, 0, "mysqli_fetch_row"), NUM));
        r.fn("mysqli_data_seek", a -> {
            Result result = result(a, 0, "mysqli_data_seek");
            long row = Args.lng(a, 1, 0);
            if (row < 0 || row >= result.rows.size()) {
                return Boolean.FALSE;
            }
            result.position = (int) row;
            return Boolean.TRUE;
        });
        r.fn("mysqli_free_result", a -> PhpNull.NULL);
    }

    // ---- objects ----

    private static PhpObject newLink(PhpContext context) {
        PhpObject object = context.getClass("mysqli").instantiate();
        object.setInternal(new Link());
        object.setProperty("connect_errno", 0L);
        object.setProperty("connect_error", PhpNull.NULL);
        return object;
    }

    private static PhpObject link(Object[] a, int index, String function) {
        if (Args.get(a, index) instanceof PhpObject object && object.getInternal() instanceof Link) {
            return object;
        }
        throw new PhpError("TypeError", function + "(): Argument #" + (index + 1) + " ($mysql) must be of type mysqli, "
                + Convert.debugType(Args.get(a, index, PhpNull.NULL)) + " given");
    }

    private static Link state(PhpObject object) {
        return (Link) object.getInternal();
    }

    private static Result result(Object[] a, int index, String function) {
        if (Args.get(a, index) instanceof PhpObject object && object.getInternal() instanceof Result result) {
            return result;
        }
        throw new PhpError("TypeError", function + "(): Argument #" + (index + 1)
                + " ($result) must be of type mysqli_result, " + Convert.debugType(Args.get(a, index, PhpNull.NULL))
                + " given");
    }

    /** mysqli's properties mirror the last operation (wpdb reads $dbh->connect_errno). */
    private static void reflect(PhpObject object) {
        Link link = state(object);
        object.setProperty("errno", (long) link.errno);
        object.setProperty("error", Str.of(link.error));
        object.setProperty("affected_rows", link.affectedRows);
        object.setProperty("insert_id", link.insertId);
    }

    // ---- connection ----

    /** (host, user, password, database, port, socket[, flags]) from index on. */
    @TruffleBoundary
    private static boolean connect(PhpContext context, PhpObject object, Object[] a, int index) {
        Link link = state(object);
        String host = Args.has(a, index) && Args.get(a, index) != PhpNull.NULL ? Str.java(Args.str(a, index))
                : "localhost";
        if (host.startsWith("p:")) {
            host = host.substring(2);
        }
        String user = Args.has(a, index + 1) ? Str.java(Args.str(a, index + 1)) : "";
        String password = Args.has(a, index + 2) ? Str.java(Args.str(a, index + 2)) : "";
        String database = Args.has(a, index + 3) && Args.get(a, index + 3) != PhpNull.NULL
                ? Str.java(Args.str(a, index + 3)) : "";
        long port = Args.has(a, index + 4) && Args.get(a, index + 4) != PhpNull.NULL ? Args.lng(a, index + 4, 3306)
                : 3306;
        // LOAD DATA LOCAL INFILE stays off, as mysqli.allow_local_infile's default
        String url = "jdbc:mariadb://" + host + ":" + port + "/" + database
                + "?connectTimeout=10000&allowLocalInfile=false";
        BuiltinState state = context.builtinState();
        try {
            link.connection = DriverManager.getConnection(url, user, password);
            state.mysqliConnectErrno = 0;
            state.mysqliConnectError = null;
            state.openConnections.add(link.connection);
            object.setProperty("connect_errno", 0L);
            object.setProperty("connect_error", PhpNull.NULL);
            setCharset(object, link.charset);
            return true;
        } catch (SQLException e) {
            state.mysqliConnectErrno = e.getErrorCode() != 0 ? e.getErrorCode() : 2002;
            state.mysqliConnectError = e.getMessage();
            object.setProperty("connect_errno", (long) state.mysqliConnectErrno);
            object.setProperty("connect_error", Str.of(e.getMessage()));
            // a failed connection throws under MYSQLI_REPORT_STRICT alone (php_mysqli_throw_sql_exception), with
            // mysqlnd's sqlstate for a handshake error
            if ((state.mysqliReport & REPORT_STRICT) != 0) {
                throw sqlException(context, "HY000", state.mysqliConnectErrno, e.getMessage());
            }
            return false;
        }
    }

    /**
     * mysqli_set_charset: SET NAMES, and the charset mysqli_real_escape_string escapes for; a name mysqlnd
     * doesn't know fails with CR_CANT_FIND_CHARSET, before anything is sent.
     */
    @TruffleBoundary
    private static boolean setCharset(PhpObject object, String charset) {
        Link link = state(object);
        if (link.connection == null) {
            return false;
        }
        if (MysqlCharsets.find(charset) == null) {
            link.errno = 2019;
            link.error = "Invalid character set was provided";
            reflect(object);
            reportError("HY000", link.errno, link.error);
            return false;
        }
        try (Statement statement = link.connection.createStatement()) {
            statement.execute("SET NAMES " + charset);
            link.charset = Str.lower(charset);
            return true;
        } catch (SQLException e) {
            failed(object, e);
            return false;
        }
    }

    @TruffleBoundary
    private static boolean selectDb(PhpObject object, String database) {
        try {
            state(object).connection.setCatalog(database);
            return true;
        } catch (SQLException e) {
            failed(object, e);
            return false;
        }
    }

    @TruffleBoundary
    private static Object serverInfo(PhpObject object) {
        try {
            return Str.of(state(object).connection.getMetaData().getDatabaseProductVersion());
        } catch (SQLException e) {
            return Boolean.FALSE;
        }
    }

    @TruffleBoundary
    private static boolean ping(PhpObject object) {
        try {
            return state(object).connection != null && state(object).connection.isValid(5);
        } catch (SQLException e) {
            return false;
        }
    }

    @TruffleBoundary
    private static boolean close(PhpObject object) {
        Link link = state(object);
        try {
            if (link.connection != null) {
                link.connection.close();
            }
        } catch (SQLException e) {
            return false;
        }
        link.connection = null;
        return true;
    }

    private static void failed(PhpObject object, SQLException e) {
        Link link = state(object);
        link.errno = e.getErrorCode();
        link.error = e.getMessage();
        reflect(object);
        reportError(sqlState(e), link.errno, link.error);
    }

    // mysqli_report() flags
    private static final long REPORT_ERROR = 1;
    private static final long REPORT_STRICT = 2;
    private static final long REPORT_INDEX = 4;
    // SERVER_QUERY_NO_GOOD_INDEX_USED, SERVER_QUERY_NO_INDEX_USED
    private static final int NO_GOOD_INDEX_USED = 16;
    private static final int NO_INDEX_USED = 32;

    private static String sqlState(SQLException e) {
        return e.getSQLState() != null ? e.getSQLState() : "HY000";
    }

    /**
     * MYSQLI_REPORT_MYSQL_ERROR: under MYSQLI_REPORT_ERROR an error is reported, as a mysqli_sql_exception with
     * MYSQLI_REPORT_STRICT, else as a warning (which this runtime doesn't print).
     */
    private static void reportError(String sqlState, long code, String message) {
        PhpContext context = PhpContext.get(null);
        long mode = context.builtinState().mysqliReport;
        if ((mode & REPORT_ERROR) != 0 && (mode & REPORT_STRICT) != 0) {
            throw sqlException(context, sqlState, code, message);
        }
    }

    /** php_mysqli_report_index: under MYSQLI_REPORT_INDEX, a query the server ran without a (good) index. */
    private static void reportIndex(PhpContext context, Link link, byte[] sql) {
        long mode = context.builtinState().mysqliReport;
        if ((mode & REPORT_INDEX) == 0) {
            return;
        }
        int status = driver(link).getContext().getServerStatus();
        String index = (status & NO_GOOD_INDEX_USED) != 0 ? "Bad index"
                : (status & NO_INDEX_USED) != 0 ? "No index" : null;
        if (index != null && (mode & REPORT_STRICT) != 0) {
            throw sqlException(context, "00000", 0, index + " used in query/prepared statement "
                    + new String(sql, StandardCharsets.UTF_8));
        }
    }

    /** php_mysqli_throw_sql_exception: the object made without its constructor, message, sqlstate and code set. */
    private static PhpException sqlException(PhpContext context, String sqlState, long code, String message) {
        PhpObject exception = context.findClassAutoloading("mysqli_sql_exception").instantiate();
        exception.setProperty("message", Str.of(message));
        exception.setProperty("sqlstate", Str.of(sqlState));
        exception.setProperty("code", code);
        return new PhpException(exception);
    }

    // ---- queries ----

    @TruffleBoundary
    private static Object query(PhpContext context, PhpObject object, byte[] sql) {
        Link link = state(object);
        if (link.connection == null) {
            throw new PhpError("mysqli object is already closed");
        }
        link.errno = 0;
        link.error = "";
        org.mariadb.jdbc.Connection driver = driver(link);
        try (Statement statement = driver.createStatement()) {
            // without the driver's statement lock: a connection belongs to one context, run by one thread at a time
            List<Completion> completions = driver.getClient().execute(new RawQuery(sql),
                    (org.mariadb.jdbc.Statement) statement, 0, 0L, ResultSet.CONCUR_READ_ONLY,
                    ResultSet.TYPE_FORWARD_ONLY, false, false);
            Completion first = completions.get(0);
            if (first instanceof ResultSet resultSet) {
                try (ResultSet rows = resultSet) {
                    Result result = read(rows);
                    link.affectedRows = result.rows.size();
                    reflect(object);
                    PhpObject resultObject = context.getClass("mysqli_result").instantiate();
                    resultObject.setInternal(result);
                    resultObject.setProperty("num_rows", (long) result.rows.size());
                    resultObject.setProperty("field_count", (long) result.names.length);
                    reportIndex(context, link, sql);
                    return resultObject;
                }
            }
            OkPacket ok = (OkPacket) first;
            link.affectedRows = ok.getAffectedRows();
            link.insertId = ok.getLastInsertId();
            reflect(object);
            reportIndex(context, link, sql);
            return Boolean.TRUE;
        } catch (SQLException e) {
            failed(object, e);
            return Boolean.FALSE;
        }
    }

    private static Result read(ResultSet rows) throws SQLException {
        ResultSetMetaData meta = rows.getMetaData();
        int columns = meta.getColumnCount();
        Result result = new Result(columns);
        for (int i = 0; i < columns; i++) {
            result.names[i] = meta.getColumnLabel(i + 1);
            result.tables[i] = meta.getTableName(i + 1);
            result.types[i] = mysqliType(meta.getColumnType(i + 1));
        }
        boolean[] bytes = new boolean[columns];
        for (int i = 0; i < columns; i++) {
            int type = meta.getColumnType(i + 1);
            bytes[i] = switch (type) {
                case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                     Types.CLOB, Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> true;
                default -> false;
            };
        }
        while (rows.next()) {
            byte[][] row = new byte[columns][];
            for (int i = 0; i < columns; i++) {
                // the text protocol's value as mysqlnd returns it: text as the bytes the server sent, in the
                // session's character set (not necessarily UTF-8); a number or a date as its (ASCII) text
                if (bytes[i]) {
                    row[i] = rows.getBytes(i + 1);
                } else {
                    String text = rows.getString(i + 1);
                    row[i] = text == null ? null : text.getBytes(StandardCharsets.UTF_8);
                }
            }
            result.rows.add(row);
        }
        return result;
    }

    /** JDBC column types as mysqli's MYSQLI_TYPE_* codes (fetch_field()->type). */
    private static int mysqliType(int jdbcType) {
        return switch (jdbcType) {
            case Types.TINYINT, Types.BOOLEAN, Types.BIT -> 1;
            case Types.SMALLINT -> 2;
            case Types.INTEGER -> 3;
            case Types.FLOAT, Types.REAL -> 4;
            case Types.DOUBLE -> 5;
            case Types.TIMESTAMP -> 7;
            case Types.BIGINT -> 8;
            case Types.DATE -> 10;
            case Types.TIME -> 11;
            case Types.DECIMAL, Types.NUMERIC -> 246;
            case Types.BLOB, Types.LONGVARBINARY, Types.LONGVARCHAR, Types.CLOB -> 252;
            case Types.CHAR -> 254;
            default -> 253;
        };
    }

    @TruffleBoundary
    private static Object fetchField(PhpContext context, Result result) {
        if (result.field >= result.names.length) {
            return Boolean.FALSE;
        }
        int i = result.field++;
        PhpObject field = context.getClass("stdClass").instantiate();
        field.setProperty("name", Str.of(result.names[i]));
        field.setProperty("orgname", Str.of(result.names[i]));
        field.setProperty("table", Str.of(result.tables[i] == null ? "" : result.tables[i]));
        field.setProperty("orgtable", Str.of(result.tables[i] == null ? "" : result.tables[i]));
        field.setProperty("def", Str.EMPTY);
        field.setProperty("db", Str.EMPTY);
        field.setProperty("catalog", Str.of("def"));
        long maxLength = 0;
        for (byte[][] row : result.rows) {
            if (row[i] != null) {
                maxLength = Math.max(maxLength, row[i].length);
            }
        }
        field.setProperty("max_length", maxLength);
        field.setProperty("length", 0L);
        field.setProperty("charsetnr", 255L);
        field.setProperty("flags", 0L);
        field.setProperty("type", (long) result.types[i]);
        field.setProperty("decimals", 0L);
        return field;
    }

    @TruffleBoundary
    private static Object fetchObject(PhpContext context, Result result) {
        if (result.position >= result.rows.size()) {
            return PhpNull.NULL;
        }
        byte[][] row = result.rows.get(result.position++);
        PhpObject object = context.getClass("stdClass").instantiate();
        for (int i = 0; i < row.length; i++) {
            object.setProperty(Str.sourceKey(result.names[i]), value(row[i]));
        }
        return object;
    }

    @TruffleBoundary
    private static Object fetchArray(Result result, long mode) {
        if (result.position >= result.rows.size()) {
            return PhpNull.NULL;
        }
        byte[][] row = result.rows.get(result.position++);
        PhpArray array = new PhpArray();
        for (int i = 0; i < row.length; i++) {
            Object value = value(row[i]);
            if ((mode & NUM) != 0) {
                array.put((long) i, value);
            }
            if ((mode & ASSOC) != 0) {
                array.put(ArrayKey.of(Str.of(result.names[i])), value);
            }
        }
        return array;
    }

    private static Object value(byte[] bytes) {
        return bytes == null ? PhpNull.NULL : Str.of(bytes);
    }

    /**
     * COM_QUERY with the query's own bytes, as mysqlnd sends them: in a session of another character set (latin1)
     * they need not be UTF-8, which the driver's QueryPacket (a Java string, UTF-8 encoded) can't carry. Encoded
     * as QueryPacket.encode does it.
     */
    private record RawQuery(byte[] sql) implements ClientMessage {
        @Override
        public int encode(Writer writer, Context context) throws java.io.IOException {
            writer.initPacket();
            writer.writeByte(0x03);
            writer.writeBytes(sql);
            writer.flush();
            return 1;
        }

        @Override
        public String description() {
            return new String(sql, StandardCharsets.UTF_8);
        }
    }

    private static org.mariadb.jdbc.Connection driver(Link link) {
        try {
            return link.connection.unwrap(org.mariadb.jdbc.Connection.class);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * mysqli_real_escape_string as mysqlnd does it: for the connection's charset (the last mysqli_set_charset), and,
     * when the server reports NO_BACKSLASH_ESCAPES in its sql_mode, by doubling quotes instead of backslashes.
     */
    @TruffleBoundary
    private static TruffleString escape(PhpObject object, byte[] input) {
        Link link = state(object);
        MysqlCharsets.Encoding charset = MysqlCharsets.find(link.charset);
        boolean noBackslashEscapes = link.connection != null
                && (driver(link).getContext().getServerStatus() & ServerStatus.NO_BACKSLASH_ESCAPES) != 0;
        return Str.of(noBackslashEscapes ? MysqlCharsets.escapeQuotes(charset, input)
                : MysqlCharsets.escapeSlashes(charset, input));
    }
}
