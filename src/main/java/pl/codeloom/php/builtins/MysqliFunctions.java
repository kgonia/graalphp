package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.ArrayKey;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
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
        r.fn("mysqli_real_connect", (context, a) -> connect(context, link(a, 0), a, 1));
        r.fn("mysqli_connect", (context, a) -> {
            PhpObject object = newLink(context);
            return connect(context, object, a, 0) ? (Object) object : Boolean.FALSE;
        });
        r.fn("mysqli_connect_errno", (context, a) -> (long) context.builtinState().mysqliConnectErrno);
        r.fn("mysqli_connect_error", (context, a) -> context.builtinState().mysqliConnectError == null
                ? PhpNull.NULL : Str.of(context.builtinState().mysqliConnectError));
        r.fn("mysqli_query", (context, a) -> query(context, link(a, 0), Str.bytes(Args.str(a, 1))));
        r.fn("mysqli_real_escape_string", a -> escape(Str.bytes(Args.str(a, 1))));
        r.fn("mysqli_escape_string", a -> escape(Str.bytes(Args.str(a, 1))));
        r.fn("mysqli_set_charset", (context, a) -> setCharset(link(a, 0), Str.java(Args.str(a, 1))));
        r.fn("mysqli_character_set_name", a -> Str.of(state(link(a, 0)).charset));
        r.fn("mysqli_select_db", a -> selectDb(link(a, 0), Str.java(Args.str(a, 1))));
        r.fn("mysqli_error", a -> Str.of(state(link(a, 0)).error));
        r.fn("mysqli_errno", a -> (long) state(link(a, 0)).errno);
        r.fn("mysqli_affected_rows", a -> state(link(a, 0)).affectedRows);
        r.fn("mysqli_insert_id", a -> state(link(a, 0)).insertId);
        r.fn("mysqli_get_server_info", a -> serverInfo(link(a, 0)));
        r.fn("mysqli_get_client_info", a -> Str.of("mysqlnd 8.5.12"));
        r.fn("mysqli_ping", a -> ping(link(a, 0)));
        r.fn("mysqli_close", a -> close(link(a, 0)));
        r.fn("mysqli_report", a -> Boolean.TRUE);
        r.fn("mysqli_more_results", a -> Boolean.FALSE);
        r.fn("mysqli_next_result", a -> Boolean.FALSE);
        r.fn("mysqli_num_fields", a -> (long) result(a, 0).names.length);
        r.fn("mysqli_num_rows", a -> (long) result(a, 0).rows.size());
        r.fn("mysqli_fetch_field", (context, a) -> fetchField(context, result(a, 0)));
        r.fn("mysqli_fetch_object", (context, a) -> fetchObject(context, result(a, 0)));
        r.fn("mysqli_fetch_array", a -> fetchArray(result(a, 0), Args.lng(a, 1, ASSOC | NUM)));
        r.fn("mysqli_fetch_assoc", a -> fetchArray(result(a, 0), ASSOC));
        r.fn("mysqli_fetch_row", a -> fetchArray(result(a, 0), NUM));
        r.fn("mysqli_data_seek", a -> {
            Result result = result(a, 0);
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

    private static PhpObject link(Object[] a, int index) {
        if (Args.get(a, index) instanceof PhpObject object && object.getInternal() instanceof Link) {
            return object;
        }
        throw new PhpError("mysqli: Argument #" + (index + 1) + " ($mysql) must be of type mysqli");
    }

    private static Link state(PhpObject object) {
        return (Link) object.getInternal();
    }

    private static Result result(Object[] a, int index) {
        if (Args.get(a, index) instanceof PhpObject object && object.getInternal() instanceof Result result) {
            return result;
        }
        throw new PhpError("mysqli: Argument #" + (index + 1) + " ($result) must be of type mysqli_result");
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
        String url = "jdbc:mariadb://" + host + ":" + port + "/" + database + "?connectTimeout=10000";
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
            return false;
        }
    }

    @TruffleBoundary
    private static boolean setCharset(PhpObject object, String charset) {
        Link link = state(object);
        if (link.connection == null) {
            return false;
        }
        try (Statement statement = link.connection.createStatement()) {
            statement.execute("SET NAMES " + charset.replaceAll("[^A-Za-z0-9_]", ""));
            link.charset = charset;
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
    }

    // ---- queries ----

    private static final java.util.regex.Pattern SET_NAMES =
            java.util.regex.Pattern.compile("(?i)^\\s*SET\\s+NAMES\\s+'?([A-Za-z0-9_]+)");

    @TruffleBoundary
    private static Object query(PhpContext context, PhpObject object, byte[] sql) {
        Link link = state(object);
        if (link.connection == null) {
            throw new PhpError("mysqli object is already closed");
        }
        link.errno = 0;
        link.error = "";
        String text = sqlText(sql, link.charset);
        try (Statement statement = link.connection.createStatement()) {
            boolean hasRows = statement.execute(text, Statement.RETURN_GENERATED_KEYS);
            java.util.regex.Matcher names = SET_NAMES.matcher(text);
            if (names.find()) {
                link.charset = names.group(1);
            }
            if (hasRows) {
                try (ResultSet rows = statement.getResultSet()) {
                    Result result = read(rows);
                    link.affectedRows = result.rows.size();
                    reflect(object);
                    PhpObject resultObject = context.getClass("mysqli_result").instantiate();
                    resultObject.setInternal(result);
                    resultObject.setProperty("num_rows", (long) result.rows.size());
                    resultObject.setProperty("field_count", (long) result.names.length);
                    return resultObject;
                }
            }
            link.affectedRows = Math.max(0, statement.getUpdateCount());
            link.insertId = 0;
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    link.insertId = keys.getLong(1);
                }
            } catch (SQLException noKeys) {
                // statements without generated keys
            }
            reflect(object);
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
            object.setProperty(result.names[i].intern(), value(row[i]));
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
     * The query as the driver takes it: a Java string, sent UTF-8 encoded. mysqlnd sends the query's bytes as they
     * are, which in a session of another character set (latin1) need not be UTF-8; a string literal holding such
     * bytes is sent as `_charset X'hex'`, which the server reads as the same literal. Bytes that aren't UTF-8
     * outside literals (in an identifier or a comment) can't be sent as they are and are replaced.
     */
    static String sqlText(byte[] sql, String charset) {
        if (isUtf8(sql, 0, sql.length)) {
            return new String(sql, StandardCharsets.UTF_8);
        }
        StringBuilder out = new StringBuilder(sql.length + 32);
        int copied = 0;
        int i = 0;
        while (i < sql.length) {
            byte b = sql[i];
            if (b == '\'' || b == '"') {
                int end = literalEnd(sql, i);
                if (!isUtf8(sql, i, end)) {
                    out.append(new String(sql, copied, i - copied, StandardCharsets.UTF_8));
                    // after an introducer of its own (_binary'...') the hex literal takes that one
                    boolean introduced = out.length() > 0 && isWordChar(out.charAt(out.length() - 1));
                    out.append(introduced ? " X'" : "_" + charset.replaceAll("[^A-Za-z0-9_]", "") + " X'");
                    out.append(java.util.HexFormat.of().formatHex(unescape(sql, i + 1, end - 1)));
                    out.append('\'');
                    copied = end;
                }
                i = end;
            } else if (b == '`') {
                i = indexOf(sql, (byte) '`', i + 1) + 1;
            } else if (b == '#' || b == '-' && i + 2 < sql.length && sql[i + 1] == '-' && isSpace(sql[i + 2])) {
                i = indexOf(sql, (byte) '\n', i);
            } else if (b == '/' && i + 1 < sql.length && sql[i + 1] == '*') {
                int close = i + 2;
                while (close + 1 < sql.length && !(sql[close] == '*' && sql[close + 1] == '/')) {
                    close++;
                }
                i = Math.min(close + 2, sql.length);
            } else {
                i++;
            }
        }
        out.append(new String(sql, copied, sql.length - copied, StandardCharsets.UTF_8));
        return out.toString();
    }

    /** The index after a quoted literal's closing quote (or the end): backslash escapes, a doubled quote. */
    private static int literalEnd(byte[] sql, int open) {
        byte quote = sql[open];
        int i = open + 1;
        while (i < sql.length) {
            if (sql[i] == '\\') {
                i += 2;
            } else if (sql[i] == quote) {
                if (i + 1 < sql.length && sql[i + 1] == quote) {
                    i += 2;
                } else {
                    return i + 1;
                }
            } else {
                i++;
            }
        }
        return sql.length;
    }

    /** A literal's value: MySQL's backslash escapes (\% and \_ keep their backslash, for LIKE) and doubled quotes. */
    private static byte[] unescape(byte[] sql, int from, int to) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(to - from);
        for (int i = from; i < to; i++) {
            byte b = sql[i];
            if (b == '\\' && i + 1 < to) {
                byte next = sql[++i];
                switch (next) {
                    case '0' -> out.write(0);
                    case 'b' -> out.write('\b');
                    case 'n' -> out.write('\n');
                    case 'r' -> out.write('\r');
                    case 't' -> out.write('\t');
                    case 'Z' -> out.write(0x1A);
                    case '%', '_' -> {
                        out.write('\\');
                        out.write(next);
                    }
                    default -> out.write(next);
                }
            } else if ((b == '\'' || b == '"') && i + 1 < to && sql[i + 1] == b) {
                out.write(b);
                i++;
            } else {
                out.write(b);
            }
        }
        return out.toByteArray();
    }

    private static boolean isUtf8(byte[] bytes, int from, int to) {
        try {
            StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes, from, to - from));
            return true;
        } catch (java.nio.charset.CharacterCodingException e) {
            return false;
        }
    }

    private static int indexOf(byte[] bytes, byte b, int from) {
        for (int i = from; i < bytes.length; i++) {
            if (bytes[i] == b) {
                return i;
            }
        }
        return bytes.length;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isSpace(byte b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r';
    }

    /** mysqli_real_escape_string: backslash-escapes NUL, \n, \r, \, ', " and Ctrl-Z. */
    @TruffleBoundary
    private static TruffleString escape(byte[] input) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(input.length + 16);
        for (byte b : input) {
            switch (b) {
                case 0 -> out.writeBytes("\\0".getBytes(StandardCharsets.ISO_8859_1));
                case '\n' -> out.writeBytes("\\n".getBytes(StandardCharsets.ISO_8859_1));
                case '\r' -> out.writeBytes("\\r".getBytes(StandardCharsets.ISO_8859_1));
                case '\\' -> out.writeBytes("\\\\".getBytes(StandardCharsets.ISO_8859_1));
                case '\'' -> out.writeBytes("\\'".getBytes(StandardCharsets.ISO_8859_1));
                case '"' -> out.writeBytes("\\\"".getBytes(StandardCharsets.ISO_8859_1));
                case 0x1A -> out.writeBytes("\\Z".getBytes(StandardCharsets.ISO_8859_1));
                default -> out.write(b);
            }
        }
        return Str.of(out.toByteArray());
    }
}
