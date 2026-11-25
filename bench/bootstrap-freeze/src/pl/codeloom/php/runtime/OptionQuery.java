package pl.codeloom.php.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only complete, single-table SQL shapes used by WordPress can narrow a dependency. */
final class OptionQuery {
    // Tokenize before inspecting the grammar: commas, comments and SQL keywords inside a value
    // stay in that value. Ambiguous backslash-quote escapes and unsupported syntax fail closed.
    private static final Pattern TOKEN = Pattern.compile(
            "\\s++|[A-Za-z0-9_]++|`([A-Za-z0-9_]++)`|'((?:[^'\\\\]++|''|\\\\[^'])*+)'|[(),*=;]");
    private static final String FIELD = "(?:option_name|option_value|autoload)";
    private static final String ASSIGN = "(?:option_value|autoload) = \\?";
    private static final Pattern UPDATE = Pattern.compile("update wp_options set (" + ASSIGN + "(?: , " + ASSIGN
            + ")?) where option_name = \\?(?: limit 1)?");
    private static final Pattern INSERT = Pattern.compile("insert into wp_options \\( (" + FIELD + "(?: , " + FIELD
            + "){2}) \\) values \\( \\? , \\? , \\? \\)(?: on duplicate key update (.+))?");
    private static final Pattern COLUMN_COPY = Pattern.compile("(" + FIELD + ") = values \\( \\1 \\)");
    private static final String COLUMN = "(?:option_name|option_value|option_id|autoload)";
    private static final Pattern SELECT = Pattern.compile("select (?:\\*|count \\( \\* \\)|" + COLUMN
            + "(?: , " + COLUMN + ")*) from wp_options where (option_name|autoload) "
            + "(= \\?|like \\?|in \\( \\?(?: , \\?)*+ \\))(?: limit 1)?");
    private static final Pattern SIMPLE_NAME = Pattern.compile("[a-zA-Z0-9_.:/-]++");

    private final String shape;
    private final List<String> values;

    private OptionQuery(String shape, List<String> values) {
        this.shape = shape;
        this.values = values;
    }

    static OptionQuery parse(String sql) {
        Matcher token = TOKEN.matcher(sql);
        List<String> literals = new ArrayList<>();
        StringBuilder shape = new StringBuilder();
        int pos = 0;
        while (pos < sql.length()) {
            if (!token.region(pos, sql.length()).lookingAt()) return null;
            pos = token.end();
            if (Character.isWhitespace(sql.charAt(token.start()))) continue;
            if (sql.charAt(token.start()) == ';') {
                if (!sql.substring(pos).isBlank()) return null;
                break;
            }
            if (!shape.isEmpty()) shape.append(' ');
            if (token.group(2) != null) {
                literals.add(token.group(2));
                shape.append('?');
            } else {
                String word = token.group(1) != null ? token.group(1) : token.group();
                shape.append(word.toLowerCase(Locale.ROOT));
            }
        }
        return new OptionQuery(shape.toString(), literals);
    }

    // Only ASCII keys can prove a disjoint match. Odd names/escapes and collation-sensitive
    // Unicode dependencies use full invalidation. WordPress's option_name index ignores case.
    static String name(String raw) {
        // Longer literals may be truncated to the 191-character index column on INSERT.
        return raw != null && raw.length() <= 191 && SIMPLE_NAME.matcher(raw).matches() ? raw.toLowerCase(Locale.ROOT) : null;
    }

    private static boolean off(String value) {
        return value.equalsIgnoreCase("no") || value.equalsIgnoreCase("off") || value.equalsIgnoreCase("auto-off");
    }

    /** Returns null unless a successful write can affect only this key and cannot enable autoload. */
    String singleWrite() {
        if (shape.equals("delete from wp_options where option_name = ?")
                || shape.equals("delete from wp_options where option_name = ? limit 1")) return name(values.getFirst());
        Matcher update = UPDATE.matcher(shape);
        if (update.matches()) {
            String[] fields = update.group(1).split(" , ");
            if (fields.length == 2 && fields[0].equals(fields[1])) return null;
            for (int i = 0; i < fields.length; i++) {
                if (fields[i].equals("autoload = ?") && !off(values.get(i))) return null;
            }
            return name(values.getLast());
        }
        Matcher insert = INSERT.matcher(shape);
        if (!insert.matches()) return null;
        List<String> fields = List.of(insert.group(1).split(" , "));
        if (!fields.containsAll(List.of("option_name", "option_value", "autoload"))
                || !off(values.get(fields.indexOf("autoload")))) return null;
        // Core add_option() copies the incoming columns on a duplicate option_name. The
        // auto-increment option_id is never supplied. No other expressions or assignments qualify.
        if (insert.group(2) != null) {
            Set<String> copied = new HashSet<>();
            for (String assignment : insert.group(2).split(" , ")) {
                Matcher copy = COLUMN_COPY.matcher(assignment);
                if (!copy.matches() || !copied.add(copy.group(1))) return null;
            }
        }
        return name(values.get(fields.indexOf("option_name")));
    }

    void collect(BootstrapEpoch.Capture reads, String[] columns, List<byte[][]> rows) {
        Matcher select = SELECT.matcher(shape);
        if (!select.matches()) {
            reads.watchAll("unbounded options query");
        } else if (select.group(1).equals("autoload")) {
            if (select.group(2).startsWith("like") || !Set.of("yes", "on", "auto", "auto-on").containsAll(values)) {
                reads.watchAll("unbounded autoload predicate");
                return;
            }
            int index = List.of(columns).indexOf("option_name");
            if (index < 0 || shape.endsWith(" limit 1")) {
                reads.watchAll("unobservable autoload result");
                return;
            }
            for (byte[][] row : rows) reads.addBytes(row[index]);
        } else if (select.group(2).startsWith("like")) {
            collectLike(reads, values.getFirst());
        } else {
            values.forEach(reads::addName); // include missing rows from equality and IN lookups
        }
    }

    private static void collectLike(BootstrapEpoch.Capture reads, String pattern) {
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\') {
                // esc_like() escapes literal underscores. wpdb may double that backslash.
                // An alternative interpretation requiring a literal backslash can only
                // match keys whose writes already invalidate conservatively.
                if (pattern.startsWith("\\\\_", i)) i += 2;
                else if (pattern.startsWith("\\_", i)) i++;
                else {
                    reads.addPrefix(prefix.toString());
                    return;
                }
                prefix.append('_');
            } else if (c == '%' || c == '_') {
                reads.addPrefix(prefix.toString());
                return;
            } else prefix.append(c);
        }
        reads.addName(prefix.toString());
    }
}
