package pl.codeloom.php.runtime;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.lang.invoke.MethodHandles;
import com.oracle.truffle.api.object.Shape;

public final class OptionReadSetRegression {
    private static final String META = "_elementor_design_system_sync_css_meta";
    private static final String UPSERT = " ON DUPLICATE KEY UPDATE `option_name` = VALUES(`option_name`), "
            + "`option_value` = VALUES(`option_value`), `autoload` = VALUES(`autoload`)";
    private static final String[] NAME_VALUE = {"option_name", "option_value"};

    private static byte[] sql(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String delete(String key) {
        return "DELETE FROM `wp_options` WHERE `option_name` = '" + key + "'";
    }

    private static String insert(String key, String autoload) {
        return "INSERT INTO `wp_options` (`option_name`, `option_value`, `autoload`) VALUES ('" + key
                + "', 'a:2:{s:4:\\\"time\\\";i:1795609428;i:0;b:0;}', '" + autoload + "')";
    }

    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }

    private static void write(boolean invalidates, String query) {
        check(BootstrapEpoch.configWrite(sql(query)) == invalidates, query);
    }

    private static PhpRef cache(String tag) {
        var all = new PhpArray();
        all.put(Str.of(tag + "_all"), false);
        var missing = new PhpArray();
        missing.put(Str.of(tag + "_missing"), true);
        var group = new PhpArray();
        group.put(Str.of("alloptions"), all);
        group.put(Str.of("notoptions"), missing);
        group.put(Str.of(tag + "_direct"), Str.of("option value"));
        var groups = new PhpArray();
        groups.put(Str.of("options"), group);
        var shape = Shape.newBuilder().layout(PhpObject.class, MethodHandles.lookup()).build();
        var type = new PhpClass("WP_Object_Cache", null, false, false, shape, null);
        var object = new PhpObject(shape, type, 1);
        object.writeRaw("cache", groups);
        object.writeRaw("multisite", false);
        return new PhpRef(object);
    }

    private static BootstrapEpoch.Capture pair() {
        var pair = BootstrapEpoch.beginCapture();
        pair.includeCache("frontend", cache("front"));
        pair.includeCache("rest", cache("rest"));
        return pair;
    }

    public static void run() {
        write(true, delete(META)); // No published pair.
        var reads = pair();
        reads.observeQuery(sql("SELECT option_value FROM wp_options WHERE option_name = 'failed_lookup' LIMIT 1"),
                new String[]{"option_value"}, List.of());
        reads.observeQuery(sql("SELECT option_name, option_value FROM wp_options WHERE option_name IN ('a', 'b')"),
                NAME_VALUE, List.of());
        reads.observeQuery(sql("SELECT COUNT(*) FROM wp_options WHERE option_name LIKE 'elementor\\_1\\_batch\\_%'"),
                new String[]{"COUNT(*)"}, List.<byte[][]>of(new byte[][]{sql("0")}));
        reads.observeQuery(sql("SELECT option_name, option_value FROM wp_options WHERE autoload IN ('yes','on','auto-on','auto')"),
                NAME_VALUE, List.<byte[][]>of(new byte[][]{sql("evicted_from_cache"), sql("value")}));
        check(reads.publish(), "publish both templates");
        for (String key : List.of("front_all", "front_missing", "front_direct", "rest_all", "rest_missing", "rest_direct",
                "FAILED_LOOKUP", "a", "b", "evicted_from_cache", "elementor_1_batch_new")) {
            write(true, delete(key));
            write(true, insert(key, "no"));
            write(true, "UPDATE wp_options SET option_value = 'new' WHERE option_name = '" + key + "'");
        }
        write(false, delete(META));
        write(false, delete("elementor_other_batch"));
        write(false, insert(META, "no") + UPSERT);
        write(false, insert(META, "OFF"));
        write(false, "INSERT INTO wp_options (autoload, option_name, option_value) VALUES ('off','different','text, ) ''quoted''')");
        write(false, "UPDATE wp_options SET option_value = 'WHERE option_name = ''a''', autoload='auto-off' WHERE option_name='uncached'");
        write(false, "UPDATE wp_options SET option_value='on' WHERE option_name='uncached'");
        for (String enabled : List.of("yes", "on", "auto", "auto-on", "unknown")) {
            write(true, insert(META, enabled));
            write(true, "UPDATE wp_options SET autoload='" + enabled + "' WHERE option_name='uncached'");
        }
        for (String other : List.of(
                "INSERT INTO wp_options (option_name, option_value) VALUES ('uncached','value')",
                insert(META, "no") + ", ('a','v','no')", insert(META, "no") + " ON DUPLICATE KEY UPDATE autoload='yes'",
                "DELETE FROM wp_options WHERE option_name='uncached' OR option_name='a'",
                "DELETE FROM wp_options WHERE option_name IN ('uncached','a')",
                "UPDATE wp_options SET option_name='a' WHERE option_name='uncached'",
                "UPDATE wp_options SET autoload=DEFAULT WHERE option_name='uncached'",
                "UPDATE wp_options SET option_value=(SELECT option_value FROM wp_options LIMIT 1) WHERE option_name='uncached'",
                delete(META) + "; DELETE FROM wp_options", delete("caf\u00e9"), delete("front_all "),
                insert("a".repeat(192), "no"),
                "UPDATE wp_2_options SET option_value='v' WHERE option_name='uncached'",
                "DELETE FROM wp_sitemeta WHERE meta_key='unseen'", "ALTER TABLE wp_posts ADD a INT",
                "CALL change_options()", "EXECUTE prepared_write", "/*! EXECUTE prepared_write */",
                "SET STATEMENT max_statement_time=5 FOR CALL change_options()")) write(true, other);

        // A write classified before the new capture completes is checked on acknowledgement;
        // a previously ignored uncommitted key is checked again when that transaction commits.
        String pending = BootstrapEpoch.mutationKey(sql(delete(META)));
        long epoch = BootstrapEpoch.current();
        BootstrapEpoch.applied(pending, null, sql(delete(META)));
        check(BootstrapEpoch.current() == epoch, "irrelevant write keeps pair live");
        var newPair = pair();
        write(true, delete(META));
        newPair.observeQuery(sql("SELECT option_value FROM wp_options WHERE option_name='" + META + "'"),
                NAME_VALUE, List.of());
        check(newPair.publish(), "publish changed dependency");
        BootstrapEpoch.applied(null, Set.of(pending), sql("COMMIT"));
        check(BootstrapEpoch.current() == epoch + 1, "commit rechecks newly watched key");
        var interrupted = pair();
        BootstrapEpoch.applied(pending, null, sql(delete(META)));
        check(!interrupted.publish(), "capture crossing a mutation cannot publish");
        var superseded = pair();
        var replacement = pair();
        check(!superseded.publish() && replacement.publish(), "superseded capture cannot replace read-set");
        write(false, delete(META));
        for (String opaque : List.of("SELECT COUNT(*) FROM wp_options", "SELECT option_value FROM wp_options",
                "SELECT option_value FROM wp_options WHERE option_name='a' OR option_value='b'",
                "SELECT COUNT(*) FROM wp_options WHERE option_name LIKE '%batch'",
                "SELECT option_name FROM wp_options WHERE autoload='off'", "CALL plugin_boot()")) {
            var uncertain = pair();
            uncertain.observeQuery(sql(opaque), NAME_VALUE, List.of());
            check(uncertain.publish(), opaque);
            write(true, delete(META));
        }
        var incomplete = BootstrapEpoch.beginCapture();
        incomplete.includeCache("frontend", cache("alone"));
        check(incomplete.publish(), "unknown dependencies still allow a fully invalidated template pair");
        write(true, delete(META));
        var customSchema = pair();
        customSchema.watchAll("unverified wp_options schema");
        check(customSchema.publish(), "custom options schema");
        write(true, insert(META, "no") + UPSERT);
    }
}
