package pl.codeloom.php.runtime;

import com.oracle.truffle.api.strings.TruffleString;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Generation of site configuration visible to the embedded mysqli runtime. */
public final class BootstrapEpoch {
    private static final AtomicLong version = new AtomicLong();
    // MariaDB's /*! ... */ and /*M! ... */ can execute SQL. Skip only ordinary comments and hints.
    private static final Pattern TRIVIA = Pattern.compile("\\s++|/\\*(?![Mm]?!)[\\s\\S]*?\\*/|(?:#|--(?=\\s|$))[^\\r\\n]*");
    private static final Pattern NON_MUTATING = Pattern.compile("(?i)(?:select|show|set|desc|describe|explain|use|begin|commit|rollback)\\b");
    private static final Pattern TRANSACTION_BOUNDARY = Pattern.compile("(?i)(?:begin|start|commit|rollback)\\b");
    private static final Pattern CONFIG = Pattern.compile("(?i)\\bwp_(?:[0-9]+_options|options|sitemeta|site|blogs|blogmeta)\\b");
    private static final Pattern DDL = Pattern.compile("(?i)(?:alter|create|drop|rename|truncate)\\b");
    private static final Pattern OPTIONS = Pattern.compile("(?i)\\bwp_options\\b");
    private static final Pattern INDIRECT = Pattern.compile("(?i)(?:(?:call|execute)\\b|/\\*m?!)");
    private static final Pattern STATEMENT = Pattern.compile("(?i)statement\\b");
    private static final int PREVIEW_LENGTH = 160;
    private static final String ANY = "*";
    private static long captureId;
    // null also covers startup, an in-progress/aborted capture and unknown dependencies.
    private static volatile ReadSet watched;

    private record ReadSet(Set<String> names, Set<String> prefixes) {
        boolean contains(String key) {
            if (names.contains(key)) return true;
            for (String prefix : prefixes) if (key.startsWith(prefix)) return true;
            return false;
        }
    }

    public static synchronized Capture beginCapture() {
        watched = null;
        return new Capture(++captureId, current());
    }

    /** Private to the thread building one frontend/REST pair, until publication. */
    public static final class Capture {
        private final long id;
        public final long generation;
        private final Set<String> names = new HashSet<>(), prefixes = new HashSet<>(), variants = new HashSet<>();
        private String fallback;

        private Capture(long id, long generation) {
            this.id = id;
            this.generation = generation;
        }

        public void watchAll(String reason) {
            if (fallback == null) fallback = reason;
        }

        void addName(String raw) {
            String key = OptionQuery.name(raw);
            if (key == null) watchAll("unsupported option name");
            else names.add(key);
        }

        void addBytes(byte[] raw) {
            addName(raw == null ? null : new String(raw, StandardCharsets.ISO_8859_1));
        }

        void addPrefix(String raw) {
            String key = OptionQuery.name(raw);
            if (key == null) watchAll("unbounded LIKE predicate");
            else prefixes.add(key);
        }

        public void observeQuery(byte[] bytes, String[] columns, List<byte[][]> rows) {
            if (fallback != null) return;
            String sql = statement(bytes);
            if (indirect(sql)) {
                watchAll("indirect SQL");
            } else if (OPTIONS.matcher(sql).find()) {
                OptionQuery query = columns == null ? null : OptionQuery.parse(sql);
                if (query == null) watchAll("unrecognized options query");
                else query.collect(this, columns, rows);
            }
        }

        public void includeCache(String variant, Object cacheReference) {
            Object cache = PhpRef.deref(cacheReference);
            if (cache instanceof PhpObject obj && obj.getPhpClass().getName().equals("WP_Object_Cache")
                    && Boolean.FALSE.equals(PhpRef.deref(obj.getProperty("multisite")))
                    && PhpRef.deref(obj.getProperty("cache")) instanceof PhpArray groups
                    && PhpRef.deref(groups.get(Str.of("options"))) instanceof PhpArray options) {
                includeOptions(variant, options);
            } else watchAll("unsupported object cache");
        }

        private static String cacheKey(Object key) {
            if (key instanceof TruffleString text) return Str.java(text);
            return key instanceof Long ? key.toString() : null;
        }

        private void includeOptions(String variant, PhpArray options) {
            variants.add(variant);
            if (!(PhpRef.deref(options.get(Str.of("alloptions"))) instanceof PhpArray)) {
                watchAll("missing alloptions cache");
                return;
            }
            for (int p = options.firstPosition(); p >= 0; p = options.nextPosition(p)) {
                String key = cacheKey(options.keyAt(p));
                if ("alloptions".equals(key) || "notoptions".equals(key)) {
                    if (PhpRef.deref(options.valueAt(p)) instanceof PhpArray nested) {
                        for (int i = nested.firstPosition(); i >= 0; i = nested.nextPosition(i)) {
                            addName(cacheKey(nested.keyAt(i)));
                        }
                    } else watchAll("unsupported nested options cache");
                } else addName(key);
            }
        }

        public boolean publish() {
            synchronized (BootstrapEpoch.class) {
                if (id != captureId || current() != generation) return false;
                if (!variants.equals(Set.of("frontend", "rest"))) watchAll("incomplete cache observations");
                watched = fallback == null ? new ReadSet(Set.copyOf(names), Set.copyOf(prefixes)) : null;
                System.err.println("freeze option_reads epoch=" + generation + " names=" + names.size()
                        + " prefixes=" + prefixes.size() + " policy=" + (fallback == null ? "selective" : "all: " + fallback));
                return true;
            }
        }
    }

    private BootstrapEpoch() {}
    public static long current() { return version.get(); }
    public static synchronized void invalidate(byte[] bytes) {
        long generation = version.incrementAndGet();
        String sql = statement(bytes);
        String preview = sql.substring(0, Math.min(sql.length(), PREVIEW_LENGTH))
                .replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}]", " ");
        System.err.println("freeze invalidate epoch=" + generation + " sql=" + preview
                + (sql.length() > PREVIEW_LENGTH ? "..." : ""));
    }

    private static String statement(byte[] bytes) {
        String sql = new String(bytes, StandardCharsets.ISO_8859_1);
        return sql.substring(afterTrivia(sql, 0));
    }

    private static int afterTrivia(String sql, int start) {
        Matcher trivia = TRIVIA.matcher(sql);
        while (trivia.region(start, sql.length()).lookingAt()) start = trivia.end();
        return start;
    }

    private static boolean indirect(String sql) {
        if (INDIRECT.matcher(sql).lookingAt()) return true;
        if (!sql.regionMatches(true, 0, "set", 0, 3)) return false;
        int next = afterTrivia(sql, 3);
        return next > 3 && STATEMENT.matcher(sql).region(next, sql.length()).lookingAt();
    }

    /** A commit-and-chain or a new BEGIN can make writes visible while IN_TRANSACTION stays set. */
    public static boolean transactionBoundary(byte[] bytes) {
        return TRANSACTION_BOUNDARY.matcher(statement(bytes)).lookingAt();
    }

    /** Null for other SQL, ANY for an unbounded write, otherwise the only possible option key. */
    public static String mutationKey(byte[] bytes) {
        String sql = statement(bytes);
        if (indirect(sql)) return ANY;
        if (NON_MUTATING.matcher(sql).lookingAt()) return null;
        if (DDL.matcher(sql).lookingAt()) return ANY;
        if (!CONFIG.matcher(sql).find()) return null;
        OptionQuery query = OptionQuery.parse(sql);
        String key = query == null ? null : query.singleWrite();
        return key == null ? ANY : key;
    }

    private static boolean relevant(String key, ReadSet reads) {
        return key != null && (reads == null || ANY.equals(key) || reads.contains(key));
    }

    public static boolean configWrite(byte[] bytes) {
        return relevant(mutationKey(bytes), watched);
    }

    /** Decide against the pair current at acknowledgement (or commit), not at SQL submission. */
    public static synchronized void applied(String key, Set<String> transaction, byte[] sql) {
        if (relevant(key, watched)) {
            invalidate(sql);
        } else if (transaction != null) {
            for (String earlier : transaction) {
                if (relevant(earlier, watched)) {
                    invalidate(sql);
                    break;
                }
            }
        }
    }
}
