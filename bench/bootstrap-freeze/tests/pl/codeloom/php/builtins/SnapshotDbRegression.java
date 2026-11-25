package pl.codeloom.php.builtins;

import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exercise the real native-handle capture/restore without opening a network connection. */
public final class SnapshotDbRegression {
    @FunctionalInterface
    private interface Call {
        Object apply(String name, Object[] args) throws SQLException;
    }

    private static <T> T standIn(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> call.apply(method.getName(), args)));
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void run() throws SQLException {
        var timeouts = new ArrayList<Integer>();
        ResultSet settings = standIn(ResultSet.class, (method, args) -> switch (method) {
            case "next" -> true;
            case "getString" -> List.of("fixture", "STRICT_ALL_TABLES", "utf8mb4", "utf8mb4_unicode_ci")
                    .get((int) args[0] - 1);
            case "close" -> null;
            default -> throw new AssertionError(method);
        });
        Statement capture = standIn(Statement.class, (method, args) -> switch (method) {
            case "setQueryTimeout" -> { timeouts.add((int) args[0]); yield null; }
            case "executeQuery" -> settings;
            case "close" -> null;
            default -> throw new AssertionError(method);
        });
        var link = new MysqliFunctions.Link();
        link.url = "jdbc:freeze-regression:fixture";
        link.connection = standIn(Connection.class, (method, args) -> {
            if (method.equals("createStatement")) return capture;
            throw new AssertionError(method);
        });
        var plan = MysqliFunctions.snapshotHandle(link);
        var closed = new AtomicBoolean();
        var setup = standIn(PreparedStatement.class, (method, args) -> switch (method) {
            case "setQueryTimeout" -> { timeouts.add((int) args[0]); yield null; }
            case "execute" -> {
                if (args == null) throw new SQLException("simulated sql_mode failure", "08006");
                yield true;
            }
            case "close", "setString" -> null;
            default -> throw new AssertionError(method);
        });
        Connection connection = standIn(Connection.class, (method, args) -> switch (method) {
            case "createStatement", "prepareStatement" -> setup;
            case "setCatalog" -> null;
            case "close" -> { closed.set(true); yield null; }
            default -> throw new AssertionError(method);
        });
        Driver driver = standIn(Driver.class, (method, args) -> {
            if (method.equals("connect")) return link.url.equals(args[0]) ? connection : null;
            if (method.equals("toString")) return "freeze regression JDBC driver";
            throw new AssertionError(method);
        });
        DriverManager.registerDriver(driver);
        try {
            plan.open(null);
            throw new AssertionError("Expected database setup failure");
        } catch (MysqliFunctions.SnapshotDbException expected) {
            check("08006".equals(((SQLException) expected.getCause()).getSQLState()), "preserve DB failure");
            check(closed.get(), "failed connection closed before returning the error");
            check(timeouts.equals(List.of(10, 10, 10)), "capture and both restore setup timeouts");
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }
}
