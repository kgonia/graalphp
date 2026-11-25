package pl.codeloom.php.launcher;

import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.*;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class FreezeRegression {
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static PhpClosure closure(int id) throws ReflectiveOperationException {
        var constructor = PhpClosure.class.getDeclaredConstructor(PhpFunction.class, PhpObject.class,
                PhpClass.class, PhpClass.class, Object[].class, TruffleString.class, int.class, Map.class,
                boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(null, null, null, null, new Object[0], null, id, new HashMap<>(), false);
    }

    private static void ownership() throws Exception {
        var items = new PhpArray();
        var slot = new PhpRef(items);
        int holders = items.holderCount();
        slot.set(items);
        check(holders == 1 && items.holderCount() == holders, "same array");
        var a = closure(1);
        var b = closure(2);
        slot.set(a);
        check(a.refcount() == 1 && items.holderCount() == 0, "array to closure");
        slot.set(a);
        check(a.refcount() == 1, "same closure");
        slot.set(b);
        check(a.refcount() == 0 && b.refcount() == 1, "closure to closure");
        slot.set(null);
        check(b.refcount() == 0, "release closure");
        var saved = new PhpArray();
        a.createStatic("saved", saved);
        b.copyStatics(a);
        check(saved.holderCount() == 2 && a.findStatic("saved") != b.findStatic("saved"), "static copy");
    }

    private static void deepArrays() {
        PhpArray[] chain = new PhpArray[40_000];
        for (int i = chain.length - 1; i >= 0; i--) {
            chain[i] = new PhpArray();
            if (i + 1 < chain.length) chain[i].append(chain[i + 1]);
        }
        check(new BootstrapSnapshot.Builder().value(chain[0]) == chain[0], "pure root identity");
        for (PhpArray node : chain) check(node.isFrozen(), "frozen descendant");
    }

    private static void eligibility() {
        var env = new HashMap<>(Map.of("REQUEST_METHOD", "GET", "HTTP_HOST", "localhost", "HTTP_X_BOOTSHARE_DB", "plugins"));
        for (String method : List.of("GET", "HEAD")) {
            env.put("REQUEST_METHOD", method);
            for (String query : List.of("", "p=1", "p=00023", "rest_route=%2fwp%2Fv2/posts")) {
                env.put("QUERY_STRING", query);
                String variant = query.startsWith("rest_route") ? "rest" : "frontend";
                check(variant.equals(HttpMain.eligibleVariant("/", env, 0)), method + " " + query);
                check(HttpMain.eligibleVariant("/", env, 1) == null, "request body");
            }
        }
        for (String query : List.of("REST_ROUTE=/wp/v2/posts", "p=", "p=1&x=2", "rest_route=/WP/v2/posts")) {
            env.put("QUERY_STRING", query);
            check(HttpMain.eligibleVariant("/", env, 0) == null, query);
        }
        env.put("QUERY_STRING", "");
        for (String header : List.of("HTTP_COOKIE", "HTTP_USER_AGENT", "HTTP_AUTHORIZATION", "HTTP_X_WP_NONCE")) {
            env.put(header, "x");
            check(HttpMain.eligibleVariant("/", env, 0) == null, header);
            env.remove(header);
        }
        check(HttpMain.eligibleVariant("/robots.txt", env, 0) == null, "path");
        env.put("REQUEST_METHOD", "POST");
        check(HttpMain.eligibleVariant("/", env, 0) == null, "method");
    }

    private static void invalidation(Path temp) throws Exception {
        for (String sql : List.of("UPDATE site.wp_options SET option_value='x'", "DELETE FROM wp_2_options",
                "INSERT INTO wp_options VALUES (1)", "REPLACE INTO wp_sitemeta VALUES (1)",
                "ALTER TABLE wp_site ADD a int", "DROP TABLE IF EXISTS wp_options",
                "CREATE TABLE wp_blogs (id int)", "TRUNCATE wp_blogmeta", "CALL change_options()",
                "/* SELECT wp_options */ -- trace\n# second comment\r\n UPDATE wp_options SET option_value=5",
                "/*M!100000 UPDATE wp_options SET option_value=4 */", "/*!40101 DELETE FROM wp_options */",
                "WITH v AS (SELECT 1) UPDATE wp_options SET option_value=3")) {
            check(BootstrapEpoch.configWrite(sql.getBytes(StandardCharsets.UTF_8)), sql);
        }
        for (String sql : List.of("SELECT * FROM wp_options", "UPDATE wp_posts SET id=1")) {
            check(!BootstrapEpoch.configWrite(sql.getBytes(StandardCharsets.UTF_8)), sql);
        }
        String comments = " \t/* UPDATE wp_options */\n-- DROP DATABASE plugins\r\n # DELETE FROM wp_options\r";
        for (String read : List.of("SeLeCt 'update', 'CREATE DATABASE' FROM wp_options FOR UPDATE",
                "SHOW CREATE TABLE wp_options", "SET @hint='DROP TABLE wp_options'",
                "DESCRIBE wp_options /* update */", "DESC wp_options /* delete */",
                "EXPLAIN UPDATE wp_options SET option_value=1", "USE wp_options /* truncate */",
                "BEGIN /* ALTER TABLE wp_options */", "COMMIT /* REPLACE INTO wp_options */",
                "ROLLBACK /* DROP TABLE wp_options */")) {
            check(!BootstrapEpoch.configWrite((comments + read).getBytes(StandardCharsets.UTF_8)), read);
        }
        check(BootstrapEpoch.transactionBoundary((comments + "COMMIT AND CHAIN").getBytes(StandardCharsets.UTF_8)),
                "dirty transaction commits while IN_TRANSACTION remains set");
        check(!BootstrapEpoch.transactionBoundary("SELECT 'commit'".getBytes(StandardCharsets.UTF_8)),
                "read in dirty transaction");
        byte[] write = "UPDATE wp_options\nSET option_value=1".getBytes(StandardCharsets.UTF_8);
        long generation = BootstrapEpoch.current();
        var messages = new ByteArrayOutputStream();
        var stderr = System.err;
        try (var capture = new PrintStream(messages, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            String sql = comments + "UPDATE wp_options\nSET option_value='" + "x".repeat(250) + "trailing-value'";
            BootstrapEpoch.invalidate(sql.getBytes(StandardCharsets.UTF_8));
        } finally {
            System.setErr(stderr);
        }
        var log = messages.toString(StandardCharsets.UTF_8).lines().toList();
        check(BootstrapEpoch.current() == generation + 1 && log.size() == 1, "one epoch and one line per invalidation");
        check(log.getFirst().startsWith("freeze invalidate epoch=" + (generation + 1) + " sql=UPDATE wp_options SET")
                && log.getFirst().endsWith("...") && !log.getFirst().contains("trailing-value"), "bounded SQL preview");
        Path root = Files.createDirectory(temp.resolve("freeze-check-" + ProcessHandle.current().pid() + "-" + System.nanoTime()));
        Path maintenance = root.resolve(".maintenance");
        var builds = new AtomicInteger();
        try (var cache = new SnapshotCache<>(root, reads -> {
            int build = builds.incrementAndGet();
            if (build == 1) BootstrapEpoch.invalidate(write);
            return build;
        }); var pool = Executors.newFixedThreadPool(8)) {
            int old = cache.get();
            check(old == 2, "discard capture straddling a config write");
            BootstrapEpoch.invalidate(write);
            List<Future<Integer>> readers = new ArrayList<>();
            for (int i = 0; i < 8; i++) readers.add(pool.submit(cache::get));
            for (var reader : readers) check(reader.get() == 3, "shared cache generation");
            check(old == 2 && builds.get() == 3, "old reader survives");
            Files.createFile(maintenance);
            check(cache.get() == null, "maintenance blocks restore");
            Files.writeString(maintenance, "upgrade");
            check(cache.get() == null, "maintenance blocks rebuild");
            Files.delete(maintenance);
            check(cache.get() > old && builds.get() >= 4, "rebuild after maintenance");
        } finally {
            Files.deleteIfExists(maintenance);
            Files.delete(root);
        }
    }

    private static void scripts() throws Exception {
        var refresh = PhpContext.class.getDeclaredMethod("refreshScript", String.class, TruffleString.class,
                String.class, String.class, String.class);
        refresh.setAccessible(true);
        for (String[] sample : List.of(
                new String[]{"utils", "var userSettings = {\"time\":\"111\"};", "var userSettings = {\"time\":\"222\"};"},
                new String[]{"wp-api-request", "var wpApiSettings = {\"root\":\"http:\\/\\/$abc\",\"nonce\":\"old\"};",
                        "var wpApiSettings = {\"root\":\"http:\\/\\/$abc\",\"nonce\":\"web\"};"},
                new String[]{"wc-blocks-middleware", "storeApiNonce: 'old',\n wcStoreApiNonceTimestamp: '111'",
                        "storeApiNonce: 'shop',\n wcStoreApiNonceTimestamp: '222'"},
                new String[]{"wc-settings", "{\"storeApiNonce\":\"old\",\"wcStoreApiNonceTimestamp\":111}",
                        "{\"storeApiNonce\":\"shop\",\"wcStoreApiNonceTimestamp\":222}"})) {
            var changed = (TruffleString) refresh.invoke(null, sample[0], Str.of(sample[1]), "222", "web", "shop");
            check(sample[2].equals(Str.java(changed)), sample[0]);
        }
    }

    public static void main(String[] args) throws Exception {
        ownership();
        deepArrays();
        eligibility();
        invalidation(Path.of(args[0]));
        OptionReadSetRegression.run();
        scripts();
        pl.codeloom.php.builtins.SnapshotDbRegression.run();
        var parsed = pl.codeloom.php.parser.zend.ZendParser.parse(Files.readAllBytes(Path.of(args[1])));
        check(parsed.ast() != null && parsed.diagnostics().isEmpty(), "resume.php syntax " + parsed.diagnostics());
        System.out.println("Passed ownership, 40,000-level freeze, eligibility, option read-set invalidation, localized scripts, JDBC cleanup and resume.php syntax checks.");
    }
}
