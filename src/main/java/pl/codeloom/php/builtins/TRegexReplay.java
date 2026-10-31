package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.Str;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Experiment (branches exp/tregex and tregex-25.4, Gate 1 step 4): replays a PcreRecorder recording through PCRE2
 * (JIT, FFM, as the preg wrappers call it) and through TRegex's Python or Java flavor, the closest to PCRE, in this
 * JVM. Checks that both find the same match (the bounds of every group) and times both over the matches TRegex
 * accepts.
 *
 * <p>{@code truffle_tregex_replay(recording, passes, flavor, options, mode, patterns)}: {@code options} are more
 * TRegex source options (e.g. {@code MaxDFASize=8000}), {@code mode} how TRegex is called ({@link Mode}), and
 * {@code patterns} a comma-separated list of the pattern ids to replay (default all).
 *
 * <p>Not product code: it decides which patterns TRegex should run.
 */
final class TRegexReplay {
    private static final int CASELESS = 0x00000008;
    private static final int DOLLAR_ENDONLY = 0x00000010;
    private static final int DOTALL = 0x00000020;
    private static final int DUPNAMES = 0x00000040;
    private static final int EXTENDED = 0x00000080;
    private static final int MULTILINE = 0x00000400;
    private static final int NO_AUTO_CAPTURE = 0x00002000;
    private static final int UNGREEDY = 0x00040000;
    private static final int UTF = 0x00080000;
    private static final int ANCHORED = 0x80000000;
    private static final int NOTEMPTY_ATSTART = 0x00000008;

    /** How a match calls TRegex. */
    enum Mode {
        /** {@code exec}, then group 0's end (which computes all groups of a lazy result): preg with groups. */
        FULL,
        /** {@code execBoolean} only: a preg_match whose caller doesn't ask for $matches. */
        BOOLEAN,
        /** {@code execBoolean}, then {@code exec} on a hit: groups only for the calls that match. */
        TWO_STEP
    }

    private record Pattern(int options, boolean jit, byte[] source) {
    }

    private record Match(int pattern, int subject, long start, int options) {
    }

    /**
     * One compiled TRegex regex: its exec and execBoolean methods, and the guard of a rewrite (a literal that must
     * not precede the match, checked here; null for none).
     */
    private record Compiled(Object exec, Object execBoolean, byte[] guard) {
    }

    /**
     * A pattern's source for TRegex instead of the recorded one. A rewrite {@code (?<!L)R} of a literal L and an R
     * that can't match empty is {@code guard} L and {@code source} R: a match of R that L precedes is skipped, and
     * the search goes on one byte further.
     */
    private record Rewrite(String source, byte[] guard) {
    }

    /** The executables and arguments of a list of matches, as ExecLoop takes them. */
    private record Calls(Object[] full, Object[] bool, Object[] guards, Object[] subjects, Object[] bytes,
                         long[] starts) {
    }

    /** Matches a pattern runs on both engines before it chooses one, and the batch they are timed in. */
    private static final int SHADOW = 1024;
    private static final int BATCH = 64;
    /** TRegex must be this much faster to be chosen. */
    private static final double MARGIN = 0.8;
    private static final int DECIDING_ITERATIONS = 40;

    private final int passes;
    private final String flavor;
    private final String extraOptions;
    private final Mode mode;

    private final Map<Integer, Pattern> patterns = new HashMap<>();
    private final Map<Integer, byte[]> subjects = new HashMap<>();
    private final Map<String, List<Match>> sections = new LinkedHashMap<>();

    // PCRE2
    private MemorySegment context;
    private final Map<Integer, MemorySegment> codes = new HashMap<>();
    private final Map<Integer, MemorySegment> data = new HashMap<>();
    private final Map<Integer, MemorySegment> ovectors = new HashMap<>();
    private final Map<Integer, Integer> groupCounts = new HashMap<>();
    private final Map<Integer, MemorySegment> nativeSubjects = new HashMap<>();

    // TRegex
    private TruffleLanguage.Env env;
    private final InteropLibrary interop = InteropLibrary.getUncached();
    private final Map<Integer, TruffleString> stringSubjects = new HashMap<>();
    private final Map<Long, Compiled> compiled = new HashMap<>();
    private final Map<Integer, String> unsupported = new LinkedHashMap<>();
    private final Set<Integer> differing = new HashSet<>();
    private final Map<Integer, Rewrite> rewrites = new HashMap<>();
    private CallTarget loop;

    private TRegexReplay(int passes, String flavor, String extraOptions, Mode mode) {
        this.passes = passes;
        this.flavor = flavor;
        this.extraOptions = extraOptions;
        this.mode = mode;
    }

    static void register(Registry r) {
        r.fn("truffle_tregex_replay", a -> {
            String flavor = Args.has(a, 2) ? Str.java(Args.str(a, 2)) : "Python";
            String options = Args.has(a, 3) ? Str.java(Args.str(a, 3)) : "";
            Mode mode = Args.has(a, 4) && !Str.java(Args.str(a, 4)).isEmpty()
                    ? Mode.valueOf(Str.java(Args.str(a, 4)).toUpperCase()) : Mode.FULL;
            String only = Args.has(a, 5) ? Str.java(Args.str(a, 5)) : "";
            String rewriteFile = Args.has(a, 6) ? Str.java(Args.str(a, 6)) : "";
            TRegexReplay replay = new TRegexReplay((int) Args.lng(a, 1, 20), flavor, options, mode);
            return Str.of(replay.run(Str.java(Args.str(a, 0)), only, rewriteFile));
        });
    }

    @TruffleBoundary
    private String run(String file, String only, String rewriteFile) {
        read(file);
        if (!rewriteFile.isEmpty()) {
            readRewrites(rewriteFile);
        }
        if (!only.isEmpty()) {
            Set<Integer> ids = new HashSet<>();
            for (String id : only.split(",")) {
                ids.add(Integer.parseInt(id.trim()));
            }
            sections.values().forEach(matches -> matches.removeIf(m -> !ids.contains(m.pattern())));
        }
        preparePcre();
        PhpContext php = PhpContext.get(null);
        env = php.getEnv();
        loop = new ExecLoop(php.getLanguage()).getCallTarget();

        StringBuilder out = new StringBuilder("flavor " + flavor + ", options '" + extraOptions + "', mode " + mode
                + "\n");
        rewrites.forEach((id, rw) -> out.append(String.format("rewrite %d: %s/%s/%n",
                id, rw.guard() == null ? "" : "guard " + new String(rw.guard(), StandardCharsets.ISO_8859_1) + " ",
                rw.source())));
        Map<String, List<Match>> byPattern = new LinkedHashMap<>();
        Map<String, int[]> hits = new HashMap<>();
        out.append(String.format("%-12s %7s %7s %7s %9s %11s %11s %7s %11s%n", "kernel", "matches", "tregex",
                "differ", "time-cov", "pcre ns", "tregex ns", "ratio", "pcre-all ns"));
        for (Map.Entry<String, List<Match>> section : sections.entrySet()) {
            List<Match> all = section.getValue();
            List<Match> covered = new ArrayList<>();
            int differ = 0;
            StringBuilder differences = new StringBuilder();
            for (Match m : all) {
                Pattern p = patterns.get(m.pattern());
                Compiled regex = compiledFor(m, p);
                if (regex == null) {
                    continue;
                }
                long[] pcre = pcreMatch(p, m);
                long[] tregex = tregexMatch(regex, m);
                if (!Arrays.equals(pcre, tregex)) {
                    differing.add(m.pattern());
                    if (differ++ < 3) {
                        differences.append(String.format("    differ: /%s/ opts %x at %d: pcre %s tregex %s%n",
                                new String(p.source(), StandardCharsets.ISO_8859_1), p.options(), m.start(),
                                Arrays.toString(pcre), Arrays.toString(tregex)));
                    }
                    continue;
                }
                covered.add(m);
                String key = section.getKey() + " " + m.pattern();
                byPattern.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
                int[] h = hits.computeIfAbsent(key, k -> new int[1]);
                h[0] += pcre[0] >= 0 ? 1 : 0;
            }
            // interleaved passes, after warmup
            Calls calls = calls(covered);
            long[] pcreCovered = new long[passes];
            long[] tregexCovered = new long[passes];
            long[] pcreAll = new long[passes];
            for (int pass = -passes; pass < passes; pass++) {
                long t0 = System.nanoTime();
                pcreRun(covered);
                long t1 = System.nanoTime();
                tregexRun(calls);
                long t2 = System.nanoTime();
                pcreRun(all);
                long t3 = System.nanoTime();
                if (pass >= 0) {
                    pcreCovered[pass] = t1 - t0;
                    tregexCovered[pass] = t2 - t1;
                    pcreAll[pass] = t3 - t2;
                }
            }
            long pc = median(pcreCovered);
            long tc = median(tregexCovered);
            long pa = median(pcreAll);
            out.append(String.format("%-12s %7d %7d %7d %8.0f%% %11d %11d %7.2f %11d%n", section.getKey(), all.size(),
                    covered.size(), differ, 100.0 * pc / Math.max(1, pa), pc, tc, (double) tc / Math.max(1, pc), pa));
            out.append(differences);
        }
        Map<String, Boolean> oracle = perPattern(out, byPattern, hits);
        adaptive(out, oracle);
        out.append("unsupported patterns: ").append(unsupported.size()).append('\n');
        unsupported.entrySet().stream().limit(25).forEach(e -> out.append("  ").append(e.getValue()).append('\n'));
        return out.toString();
    }

    /**
     * Each pattern's matches in one kernel iteration, timed on both engines (repeated until a batch takes about
     * 100 us), with the pattern's features and the share of calls that match: which patterns TRegex runs faster,
     * and whether a rule on the syntax picks them.
     */
    private Map<String, Boolean> perPattern(StringBuilder out, Map<String, List<Match>> byPattern,
                                            Map<String, int[]> hits) {
        record Row(String kernel, int pattern, int count, int hits, double pcre, double tregex, String features) {
        }
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, List<Match>> e : byPattern.entrySet()) {
            List<Match> matches = e.getValue();
            int id = matches.get(0).pattern();
            Calls calls = calls(matches);
            long t0 = System.nanoTime();
            pcreRun(matches);
            int reps = (int) Math.max(1, Math.min(10_000, 100_000 / Math.max(1, System.nanoTime() - t0)));
            long[] pcre = new long[passes];
            long[] tregex = new long[passes];
            for (int pass = -passes; pass < passes; pass++) {
                long a = System.nanoTime();
                for (int r = 0; r < reps; r++) {
                    pcreRun(matches);
                }
                long b = System.nanoTime();
                for (int r = 0; r < reps; r++) {
                    tregexRun(calls);
                }
                long c = System.nanoTime();
                if (pass >= 0) {
                    pcre[pass] = b - a;
                    tregex[pass] = c - b;
                }
            }
            rows.add(new Row(e.getKey().split(" ")[0], id, matches.size(), hits.get(e.getKey())[0],
                    (double) median(pcre) / reps, (double) median(tregex) / reps, features(patterns.get(id))));
        }
        rows.sort((x, y) -> Double.compare(y.pcre(), x.pcre()));
        out.append(String.format("%nper pattern, one kernel iteration's matches (ns), by PCRE2 time%n"));
        out.append(String.format("%-12s %5s %7s %5s %10s %10s %6s  %-28s %s%n", "kernel", "id", "matches", "hit%",
                "pcre", "tregex", "ratio", "features", "pattern"));
        Map<String, double[]> wins = new LinkedHashMap<>();
        Map<String, Boolean> oracle = new HashMap<>();
        for (Row r : rows) {
            String source = new String(patterns.get(r.pattern()).source(), StandardCharsets.ISO_8859_1);
            out.append(String.format("%-12s %5d %7d %5.0f %10.0f %10.0f %6.2f  %-28s /%s/%n", r.kernel(), r.pattern(),
                    r.count(), 100.0 * r.hits() / r.count(), r.pcre(), r.tregex(), r.tregex() / r.pcre(),
                    r.features(), source.length() > 70 ? source.substring(0, 70) + "..." : source));
            oracle.put(r.kernel() + " " + r.pattern(), r.tregex() < r.pcre());
            double[] w = wins.computeIfAbsent(r.kernel(), k -> new double[4]);
            w[0] += r.pcre();
            if (r.tregex() < r.pcre()) {
                w[1] += r.pcre();
                w[2] += r.tregex();
            }
            if (r.features().equals("-")) {
                w[3] += r.tregex() - r.pcre();
            }
        }
        out.append(String.format("%nper kernel: covered PCRE2 ns, PCRE2 ns of the patterns TRegex wins, their"
                + " TRegex ns, TRegex-PCRE2 ns of featureless patterns%n"));
        wins.forEach((k, w) -> out.append(String.format("%-12s %10.0f %10.0f %10.0f %10.0f%n", k, w[0], w[1], w[2],
                w[3])));
        return oracle;
    }

    /**
     * Adaptive choice, simulated: each kernel iteration's matches run in their recorded order for
     * DECIDING_ITERATIONS iterations. A pattern TRegex can run (and that never differed) runs its first SHADOW
     * matches on both engines, timed per batch of BATCH; the first half warms TRegex up, the second decides. Then
     * the steady state of PCRE2 alone, the adaptive choice and the per-pattern oracle are timed over one iteration.
     */
    private void adaptive(StringBuilder out, Map<String, Boolean> oracle) {
        out.append(String.format("%nadaptive choice (shadow %d matches, batches of %d, margin %.1f), ns per iteration%n",
                SHADOW, BATCH, MARGIN));
        out.append(String.format("%-12s %11s %11s %11s %8s %8s %6s %6s%n", "kernel", "pcre only", "adaptive", "oracle",
                "adapt/p", "orac/p", "#adapt", "#orac"));
        for (Map.Entry<String, List<Match>> section : sections.entrySet()) {
            List<Match> all = section.getValue();
            Map<Integer, Boolean> decided = new HashMap<>();
            Map<Integer, List<Match>> pending = new HashMap<>();
            Map<Integer, long[]> stats = new HashMap<>();
            for (int iteration = 0; iteration < DECIDING_ITERATIONS; iteration++) {
                for (Match m : all) {
                    int id = m.pattern();
                    if (decided.containsKey(id)) {
                        continue;
                    }
                    if (differing.contains(id) || compiledFor(m, patterns.get(id)) == null) {
                        decided.put(id, false);
                        continue;
                    }
                    List<Match> batch = pending.computeIfAbsent(id, k -> new ArrayList<>());
                    batch.add(m);
                    if (batch.size() < BATCH) {
                        continue;
                    }
                    long[] st = stats.computeIfAbsent(id, k -> new long[3]);
                    Calls calls = calls(batch);
                    long a = System.nanoTime();
                    pcreRun(batch);
                    long b = System.nanoTime();
                    tregexRun(calls);
                    long c = System.nanoTime();
                    st[0] += batch.size();
                    if (st[0] > SHADOW / 2) {
                        st[1] += b - a;
                        st[2] += c - b;
                    }
                    batch.clear();
                    if (st[0] >= SHADOW) {
                        decided.put(id, st[2] < MARGIN * st[1]);
                    }
                }
            }
            List<Match> pcreAdaptive = new ArrayList<>();
            List<Match> tregexAdaptive = new ArrayList<>();
            List<Match> pcreOracle = new ArrayList<>();
            List<Match> tregexOracle = new ArrayList<>();
            Set<Integer> chosenAdaptive = new HashSet<>();
            Set<Integer> chosenOracle = new HashSet<>();
            for (Match m : all) {
                int id = m.pattern();
                boolean adaptiveTregex = Boolean.TRUE.equals(decided.get(id));
                boolean oracleTregex = !differing.contains(id)
                        && Boolean.TRUE.equals(oracle.get(section.getKey() + " " + id));
                (adaptiveTregex ? tregexAdaptive : pcreAdaptive).add(m);
                (oracleTregex ? tregexOracle : pcreOracle).add(m);
                if (adaptiveTregex) {
                    chosenAdaptive.add(id);
                }
                if (oracleTregex) {
                    chosenOracle.add(id);
                }
            }
            Calls adaptiveCalls = calls(tregexAdaptive);
            Calls oracleCalls = calls(tregexOracle);
            long[] pcreOnly = new long[passes];
            long[] adaptiveTime = new long[passes];
            long[] oracleTime = new long[passes];
            for (int pass = -passes; pass < passes; pass++) {
                long a = System.nanoTime();
                pcreRun(all);
                long b = System.nanoTime();
                pcreRun(pcreAdaptive);
                tregexRun(adaptiveCalls);
                long c = System.nanoTime();
                pcreRun(pcreOracle);
                tregexRun(oracleCalls);
                long d = System.nanoTime();
                if (pass >= 0) {
                    pcreOnly[pass] = b - a;
                    adaptiveTime[pass] = c - b;
                    oracleTime[pass] = d - c;
                }
            }
            long p = median(pcreOnly);
            long ad = median(adaptiveTime);
            long or = median(oracleTime);
            out.append(String.format("%-12s %11d %11d %11d %8.2f %8.2f %6d %6d%n", section.getKey(), p, ad, or,
                    (double) ad / Math.max(1, p), (double) or / Math.max(1, p), chosenAdaptive.size(),
                    chosenOracle.size()));
            for (int id : chosenOracle) {
                if (!chosenAdaptive.contains(id)) {
                    long[] st = stats.get(id);
                    out.append(String.format("    oracle only: pattern %d (%s)%n", id,
                            st == null ? "undecided" : String.format("shadowed %d, pcre %d tregex %d ns", st[0], st[1],
                                    st[2])));
                }
            }
            for (int id : chosenAdaptive) {
                if (!chosenOracle.contains(id)) {
                    out.append(String.format("    adaptive only: pattern %d%n", id));
                }
            }
        }
    }

    /** The executables, subjects and starts of matches, as ExecLoop takes them. */
    private Calls calls(List<Match> matches) {
        Object[] full = new Object[matches.size()];
        Object[] bool = new Object[matches.size()];
        Object[] guards = new Object[matches.size()];
        Object[] subjectArray = new Object[matches.size()];
        Object[] bytes = new Object[matches.size()];
        long[] starts = new long[matches.size()];
        for (int i = 0; i < matches.size(); i++) {
            Match m = matches.get(i);
            Compiled regex = compiledFor(m, patterns.get(m.pattern()));
            full[i] = regex.exec();
            bool[i] = regex.execBoolean();
            guards[i] = regex.guard();
            subjectArray[i] = stringSubjects.get(m.subject());
            bytes[i] = subjects.get(m.subject());
            starts[i] = m.start();
        }
        return new Calls(full, bool, guards, subjectArray, bytes, starts);
    }

    private void tregexRun(Calls calls) {
        loop.call(calls.full(), calls.bool(), calls.guards(), calls.subjects(), calls.bytes(), calls.starts(),
                mode.ordinal());
    }

    /** Whether guard ends at position end of subject. */
    private static boolean precedes(byte[] guard, byte[] subject, int end) {
        if (end < guard.length) {
            return false;
        }
        return Arrays.equals(guard, 0, guard.length, subject, end - guard.length, end);
    }

    /** The syntax a backtracking-free engine finds hard: lookaround, backreferences, possessive, atomic, flags. */
    private static String features(Pattern p) {
        String s = new String(p.source(), StandardCharsets.ISO_8859_1);
        List<String> f = new ArrayList<>();
        if (s.contains("(?=") || s.contains("(?!")) {
            f.add("ahead");
        }
        if (s.contains("(?<=") || s.contains("(?<!")) {
            f.add("behind");
        }
        if (s.matches("(?s).*\\\\[1-9gk].*")) {
            f.add("backref");
        }
        if (s.matches("(?s).*[*+?}]\\+.*")) {
            f.add("possessive");
        }
        if (s.contains("(?>")) {
            f.add("atomic");
        }
        if ((p.options() & CASELESS) != 0) {
            f.add("i");
        }
        if ((p.options() & UTF) != 0) {
            f.add("u");
        }
        return f.isEmpty() ? "-" : String.join(",", f);
    }

    /**
     * Loops over the matches in compiled code with cached interop, as a node calling TRegex would. Arguments: the
     * exec and execBoolean methods, the guards, the subjects as TruffleStrings and as bytes, the starts, and the
     * Mode's ordinal. A guarded match always calls exec (it needs the match's start).
     */
    private static final class ExecLoop extends RootNode {
        @Child private InteropLibrary execs = InteropLibrary.getFactory().createDispatched(4);
        @Child private InteropLibrary booleans = InteropLibrary.getFactory().createDispatched(4);
        @Child private InteropLibrary results = InteropLibrary.getFactory().createDispatched(4);

        ExecLoop(TruffleLanguage<?> language) {
            super(language);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] arguments = frame.getArguments();
            Object[] full = (Object[]) arguments[0];
            Object[] bool = (Object[]) arguments[1];
            Object[] guards = (Object[]) arguments[2];
            Object[] subjects = (Object[]) arguments[3];
            Object[] bytes = (Object[]) arguments[4];
            long[] starts = (long[]) arguments[5];
            int mode = (int) arguments[6];
            long found = 0;
            try {
                for (int i = 0; i < full.length; i++) {
                    if (guards[i] == null && mode != Mode.FULL.ordinal()) {
                        boolean hit = booleans.asBoolean(booleans.execute(bool[i], subjects[i], starts[i]));
                        if (!hit) {
                            continue;
                        }
                        if (mode == Mode.BOOLEAN.ordinal()) {
                            found++;
                            continue;
                        }
                    }
                    long start = starts[i];
                    while (true) {
                        Object result = execs.execute(full[i], subjects[i], start);
                        if (!results.asBoolean(results.readMember(result, "isMatch"))) {
                            break;
                        }
                        if (guards[i] != null) {
                            int matchStart = results.asInt(results.invokeMember(result, "getStart", 0));
                            if (precedes((byte[]) guards[i], (byte[]) bytes[i], matchStart)) {
                                start = matchStart + 1;
                                continue;
                            }
                        }
                        found += results.asInt(results.invokeMember(result, "getEnd", 0));
                        break;
                    }
                }
            } catch (InteropException e) {
                throw new IllegalStateException(e);
            }
            return found;
        }
    }

    /** The TRegex regex for a match's pattern and matching mode, or null if TRegex can't compile it. */
    private Compiled compiledFor(Match m, Pattern p) {
        boolean anchored = (m.options() & ANCHORED) != 0 || (p.options() & ANCHORED) != 0;
        boolean mustAdvance = (m.options() & NOTEMPTY_ATSTART) != 0;
        long key = (long) m.pattern() << 2 | (anchored ? 1 : 0) | (mustAdvance ? 2 : 0);
        if (compiled.containsKey(key)) {
            return compiled.get(key);
        }
        Compiled result = null;
        String source = new String(p.source(), StandardCharsets.ISO_8859_1);
        int bad = p.options() & (DOLLAR_ENDONLY | DUPNAMES | NO_AUTO_CAPTURE | UNGREEDY);
        if (bad != 0) {
            unsupported.put(m.pattern(), String.format("options %x: /%s/", bad, source));
        } else {
            StringBuilder flags = new StringBuilder();
            if ((p.options() & CASELESS) != 0) {
                flags.append('i');
            }
            if ((p.options() & MULTILINE) != 0) {
                flags.append('m');
            }
            if ((p.options() & DOTALL) != 0) {
                flags.append('s');
            }
            if ((p.options() & EXTENDED) != 0) {
                flags.append('x');
            }
            boolean utf = (p.options() & UTF) != 0;
            String options = "Flavor=" + flavor + ",Encoding=" + (utf ? "UTF-8" : "BYTES") + ",MatchingMode="
                    + (anchored ? "match" : "search") + (mustAdvance ? ",MustAdvance=true" : "")
                    + (extraOptions.isEmpty() ? "" : "," + extraOptions);
            Rewrite rewrite = rewrites.get(m.pattern());
            String tregexSource = rewrite == null ? source : rewrite.source();
            // named groups: PCRE takes (?<name> and (?P<name>, Python only the second, Java only the first
            String pattern = flavor.equals("Python") ? tregexSource.replaceAll("\\(\\?<([A-Za-z_])", "(?P<$1")
                    : tregexSource.replaceAll("\\(\\?P<", "(?<");
            try {
                Object regex = env.parseInternal(Source.newBuilder("regex", options + "/" + pattern + "/" + flags,
                        "replay").internal(true).build()).call();
                if (regex == null || interop.isNull(regex)) {
                    unsupported.put(m.pattern(), "null: /" + source + "/");
                } else {
                    result = new Compiled(interop.readMember(regex, "exec"), interop.readMember(regex, "execBoolean"),
                            rewrite == null ? null : rewrite.guard());
                }
            } catch (Exception e) {
                unsupported.put(m.pattern(), e.getClass().getSimpleName() + " " + e.getMessage() + ": /" + source
                        + "/");
            }
        }
        compiled.put(key, result);
        return result;
    }

    private void preparePcre() {
        MemorySegment stack = PcreFunctions.Native.jitStack();
        context = PcreFunctions.Native.matchContext(stack);
        for (Map.Entry<Integer, Pattern> e : patterns.entrySet()) {
            MemorySegment code = PcreFunctions.Native.compile(e.getValue().source(), e.getValue().options());
            PcreFunctions.Native.jitCompile(code);
            MemorySegment md = PcreFunctions.Native.createMatchData(code);
            codes.put(e.getKey(), code);
            data.put(e.getKey(), md);
            int groups = PcreFunctions.Native.info(code, 4) + 1;
            groupCounts.put(e.getKey(), groups);
            ovectors.put(e.getKey(), PcreFunctions.Native.ovector(md, groups));
        }
        for (Map.Entry<Integer, byte[]> e : subjects.entrySet()) {
            MemorySegment segment = Arena.ofAuto().allocate(Math.max(1, e.getValue().length));
            MemorySegment.copy(e.getValue(), 0, segment, ValueLayout.JAVA_BYTE, 0, e.getValue().length);
            nativeSubjects.put(e.getKey(), segment);
            stringSubjects.put(e.getKey(), Str.of(e.getValue()));
        }
    }

    /** Start and end of every group (-1 when unset), {-1, -1} for no match, and {0, 0} for a match in BOOLEAN mode. */
    private long[] pcreMatch(Pattern p, Match m) {
        int rc = PcreFunctions.Native.match(codes.get(m.pattern()), p.jit(), nativeSubjects.get(m.subject()),
                subjects.get(m.subject()).length, m.start(), m.options(), data.get(m.pattern()), context);
        if (rc < 0) {
            return new long[] {-1, -1};
        }
        if (mode == Mode.BOOLEAN) {
            return new long[] {0, 0};
        }
        int groups = groupCounts.get(m.pattern());
        MemorySegment ovector = ovectors.get(m.pattern());
        long[] bounds = new long[2 * groups];
        for (int i = 0; i < 2 * groups; i++) {
            bounds[i] = i < 2 * rc ? ovector.getAtIndex(ValueLayout.JAVA_LONG, i) : -1;
        }
        return bounds;
    }

    /**
     * As pcreMatch, through TRegex in this replay's mode. BOOLEAN compares only whether there is a match; TWO_STEP
     * also fails the comparison when execBoolean finds a match that exec doesn't.
     */
    private long[] tregexMatch(Compiled regex, Match m) {
        TruffleString subject = stringSubjects.get(m.subject());
        try {
            if (regex.guard() == null && mode != Mode.FULL) {
                boolean hit = interop.asBoolean(interop.execute(regex.execBoolean(), subject, m.start()));
                if (mode == Mode.BOOLEAN || !hit) {
                    return hit ? new long[] {0, 0} : new long[] {-1, -1};
                }
            }
            long start = m.start();
            Object result = interop.execute(regex.exec(), subject, start);
            while (regex.guard() != null && interop.asBoolean(interop.readMember(result, "isMatch"))) {
                int matchStart = interop.asInt(interop.invokeMember(result, "getStart", 0));
                if (!precedes(regex.guard(), subjects.get(m.subject()), matchStart)) {
                    break;
                }
                start = matchStart + 1;
                result = interop.execute(regex.exec(), subject, start);
            }
            if (!interop.asBoolean(interop.readMember(result, "isMatch"))) {
                return mode == Mode.TWO_STEP && regex.guard() == null ? new long[] {-2, -2} : new long[] {-1, -1};
            }
            if (mode == Mode.BOOLEAN) {
                return new long[] {0, 0};
            }
            int groups = groupCounts.get(m.pattern());
            long[] bounds = new long[2 * groups];
            for (int i = 0; i < groups; i++) {
                bounds[2 * i] = interop.asInt(interop.invokeMember(result, "getStart", i));
                bounds[2 * i + 1] = interop.asInt(interop.invokeMember(result, "getEnd", i));
            }
            return bounds;
        } catch (InteropException e) {
            throw new IllegalStateException(e);
        }
    }

    private void pcreRun(List<Match> matches) {
        for (Match m : matches) {
            PcreFunctions.Native.match(codes.get(m.pattern()), patterns.get(m.pattern()).jit(),
                    nativeSubjects.get(m.subject()), subjects.get(m.subject()).length, m.start(), m.options(),
                    data.get(m.pattern()), context);
        }
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private void read(String file) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file), 1 << 16))) {
            List<Match> current = null;
            while (true) {
                int tag;
                try {
                    tag = in.readUnsignedByte();
                } catch (EOFException end) {
                    return;
                }
                switch (tag) {
                    case 'K' -> {
                        String label = new String(bytes(in), StandardCharsets.UTF_8);
                        current = sections.computeIfAbsent(label, k -> new ArrayList<>());
                        current.clear();
                    }
                    case 'E' -> current = null;
                    case 'P' -> {
                        int id = in.readInt();
                        int options = in.readInt();
                        boolean jit = in.readByte() != 0;
                        patterns.put(id, new Pattern(options, jit, bytes(in)));
                    }
                    case 'S' -> {
                        int id = in.readInt();
                        subjects.put(id, bytes(in));
                    }
                    case 'M' -> {
                        Match m = new Match(in.readInt(), in.readInt(), in.readLong(), in.readInt());
                        if (current != null) {
                            current.add(m);
                        }
                    }
                    default -> throw new IllegalStateException("bad record tag " + tag);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Lines of {@code id<TAB>source} or {@code id<TAB>guard<TAB>source}, in ISO-8859-1 (the patterns' bytes). */
    private void readRewrites(String file) {
        try {
            for (String line : Files.readAllLines(Path.of(file), StandardCharsets.ISO_8859_1)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] fields = line.split("	", 3);
                int id = Integer.parseInt(fields[0].trim());
                rewrites.put(id, fields.length == 3
                        ? new Rewrite(fields[2], fields[1].getBytes(StandardCharsets.ISO_8859_1))
                        : new Rewrite(fields[1], null));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] bytes(DataInputStream in) throws IOException {
        byte[] bytes = new byte[in.readInt()];
        in.readFully(bytes);
        return bytes;
    }
}
