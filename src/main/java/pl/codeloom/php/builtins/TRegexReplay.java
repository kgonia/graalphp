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

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.BufferedInputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Experiment (branch exp/tregex, Gate 1 step 4): replays a PcreRecorder recording through PCRE2 (JIT, FFM, as the
 * preg wrappers call it) and through TRegex's Python flavor, the closest to PCRE, in this JVM. Checks that both find
 * the same match (start and end of group 0) and times both over the matches TRegex accepts.
 *
 * <p>Not product code: it decides whether a PCRE flavor for TRegex is worth writing.
 */
final class TRegexReplay {
    private static final int CASELESS = 0x00000008;
    private static final int DOLLAR_ENDONLY = 0x00000010;
    private static final int DOTALL = 0x00000020;
    private static final int DUPNAMES = 0x00000040;
    private static final int EXTENDED = 0x00000080;
    private static final int MULTILINE = 0x00000400;
    private static final int NO_AUTO_CAPTURE = 0x00002000;
    private static final int UCP = 0x00020000;
    private static final int UNGREEDY = 0x00040000;
    private static final int UTF = 0x00080000;
    private static final int ANCHORED = 0x80000000;
    private static final int NO_UTF_CHECK = 0x40000000;
    private static final int NOTEMPTY_ATSTART = 0x00000008;

    private record Pattern(int options, boolean jit, byte[] source) {
    }

    private record Match(int pattern, int subject, long start, int options) {
    }

    private TRegexReplay() {
    }

    static void register(Registry r) {
        r.fn("truffle_tregex_replay", a -> Str.of(run(Str.java(Args.str(a, 0)), (int) Args.lng(a, 1, 20),
                Args.has(a, 2) ? Str.java(Args.str(a, 2)) : "Python")));
    }

    @TruffleBoundary
    private static String run(String file, int passes, String flavor) {
        Map<Integer, Pattern> patterns = new HashMap<>();
        Map<Integer, byte[]> subjects = new HashMap<>();
        Map<String, List<Match>> sections = new LinkedHashMap<>();
        read(file, patterns, subjects, sections);

        // PCRE2 side
        MemorySegment stack = PcreFunctions.Native.jitStack();
        MemorySegment context = PcreFunctions.Native.matchContext(stack);
        Map<Integer, MemorySegment> codes = new HashMap<>();
        Map<Integer, MemorySegment> data = new HashMap<>();
        Map<Integer, MemorySegment> ovectors = new HashMap<>();
        for (Map.Entry<Integer, Pattern> e : patterns.entrySet()) {
            MemorySegment code = PcreFunctions.Native.compile(e.getValue().source(), e.getValue().options());
            PcreFunctions.Native.jitCompile(code);
            MemorySegment md = PcreFunctions.Native.createMatchData(code);
            codes.put(e.getKey(), code);
            data.put(e.getKey(), md);
            ovectors.put(e.getKey(), PcreFunctions.Native.ovector(md, 1));
        }
        Map<Integer, MemorySegment> nativeSubjects = new HashMap<>();
        Map<Integer, TruffleString> stringSubjects = new HashMap<>();
        for (Map.Entry<Integer, byte[]> e : subjects.entrySet()) {
            MemorySegment segment = Arena.ofAuto().allocate(Math.max(1, e.getValue().length));
            MemorySegment.copy(e.getValue(), 0, segment, ValueLayout.JAVA_BYTE, 0, e.getValue().length);
            nativeSubjects.put(e.getKey(), segment);
            stringSubjects.put(e.getKey(), Str.of(e.getValue()));
        }

        // TRegex side: one compiled regex per pattern and matching mode
        PhpContext php = PhpContext.get(null);
        TruffleLanguage.Env env = php.getEnv();
        InteropLibrary interop = InteropLibrary.getUncached();
        Map<Long, Object> execs = new HashMap<>();
        Map<Integer, String> unsupported = new LinkedHashMap<>();

        StringBuilder out = new StringBuilder("flavor " + flavor + "\n");
        Map<String, List<Match>> byPattern = new LinkedHashMap<>();
        out.append(String.format("%-12s %7s %7s %7s %9s %11s %11s %7s %11s%n", "kernel", "matches", "tregex",
                "differ", "time-cov", "pcre ns", "tregex ns", "ratio", "pcre-all ns"));
        CallTarget loop = new ExecLoop(php.getLanguage()).getCallTarget();
        for (Map.Entry<String, List<Match>> section : sections.entrySet()) {
            List<Match> all = section.getValue();
            List<Match> covered = new ArrayList<>();
            List<Object> coveredExecs = new ArrayList<>();
            int differ = 0;
            StringBuilder differences = new StringBuilder();
            for (Match m : all) {
                Pattern p = patterns.get(m.pattern());
                Object exec = execFor(env, interop, execs, unsupported, m, p, flavor);
                if (exec == null) {
                    continue;
                }
                long[] pcre = pcreMatch(codes, data, ovectors, nativeSubjects, subjects, context, p, m);
                long[] tregex = tregexMatch(interop, exec, stringSubjects.get(m.subject()), m.start());
                if (pcre[0] != tregex[0] || pcre[0] >= 0 && pcre[1] != tregex[1]) {
                    if (differ++ < 3) {
                        differences.append(String.format("    differ: /%s/ opts %x at %d: pcre %d-%d tregex %d-%d%n",
                                new String(p.source(), StandardCharsets.ISO_8859_1), p.options(), m.start(),
                                pcre[0], pcre[1], tregex[0], tregex[1]));
                    }
                    continue;
                }
                covered.add(m);
                coveredExecs.add(exec);
                byPattern.computeIfAbsent(section.getKey() + " " + m.pattern(), k -> new ArrayList<>()).add(m);
            }
            // interleaved passes, after warmup
            Object[] execArray = coveredExecs.toArray();
            Object[] subjectArray = new Object[covered.size()];
            long[] starts = new long[covered.size()];
            for (int i = 0; i < covered.size(); i++) {
                subjectArray[i] = stringSubjects.get(covered.get(i).subject());
                starts[i] = covered.get(i).start();
            }
            long[] pcreCovered = new long[passes];
            long[] tregexCovered = new long[passes];
            long[] pcreAll = new long[passes];
            for (int pass = -passes; pass < passes; pass++) {
                long t0 = System.nanoTime();
                pcreRun(codes, data, nativeSubjects, subjects, context, patterns, covered);
                long t1 = System.nanoTime();
                loop.call(execArray, subjectArray, starts);
                long t2 = System.nanoTime();
                pcreRun(codes, data, nativeSubjects, subjects, context, patterns, all);
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
        perPattern(out, byPattern, passes, patterns, codes, data, nativeSubjects, subjects, context, stringSubjects,
                loop, env, interop, execs, unsupported, flavor);
        out.append("unsupported patterns: ").append(unsupported.size()).append('\n');
        unsupported.entrySet().stream().limit(25).forEach(e -> out.append("  ").append(e.getValue()).append('\n'));
        return out.toString();
    }

    /**
     * Each pattern's matches in one kernel iteration, timed on both engines (repeated until a batch takes about
     * 100 us), with the pattern's features: which patterns TRegex runs faster, and whether a rule on the syntax
     * picks them.
     */
    private static void perPattern(StringBuilder out, Map<String, List<Match>> byPattern, int passes,
                                   Map<Integer, Pattern> patterns, Map<Integer, MemorySegment> codes,
                                   Map<Integer, MemorySegment> data, Map<Integer, MemorySegment> nativeSubjects,
                                   Map<Integer, byte[]> subjects, MemorySegment context,
                                   Map<Integer, TruffleString> stringSubjects, CallTarget loop,
                                   TruffleLanguage.Env env, InteropLibrary interop, Map<Long, Object> execs,
                                   Map<Integer, String> unsupported, String flavor) {
        record Row(String kernel, int pattern, int count, double pcre, double tregex, String features) {
        }
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, List<Match>> e : byPattern.entrySet()) {
            List<Match> matches = e.getValue();
            int id = matches.get(0).pattern();
            Pattern p = patterns.get(id);
            Object[] fns = new Object[matches.size()];
            Object[] subjectArray = new Object[matches.size()];
            long[] starts = new long[matches.size()];
            for (int i = 0; i < matches.size(); i++) {
                fns[i] = execFor(env, interop, execs, unsupported, matches.get(i), p, flavor);
                subjectArray[i] = stringSubjects.get(matches.get(i).subject());
                starts[i] = matches.get(i).start();
            }
            long t0 = System.nanoTime();
            pcreRun(codes, data, nativeSubjects, subjects, context, patterns, matches);
            int reps = (int) Math.max(1, Math.min(10_000, 100_000 / Math.max(1, System.nanoTime() - t0)));
            long[] pcre = new long[passes];
            long[] tregex = new long[passes];
            for (int pass = -passes; pass < passes; pass++) {
                long a = System.nanoTime();
                for (int r = 0; r < reps; r++) {
                    pcreRun(codes, data, nativeSubjects, subjects, context, patterns, matches);
                }
                long b = System.nanoTime();
                for (int r = 0; r < reps; r++) {
                    loop.call(fns, subjectArray, starts);
                }
                long c = System.nanoTime();
                if (pass >= 0) {
                    pcre[pass] = b - a;
                    tregex[pass] = c - b;
                }
            }
            rows.add(new Row(e.getKey().split(" ")[0], id, matches.size(), (double) median(pcre) / reps,
                    (double) median(tregex) / reps, features(p)));
        }
        rows.sort((x, y) -> Double.compare(y.pcre(), x.pcre()));
        out.append(String.format("%nper pattern, one kernel iteration's matches (ns), by PCRE2 time%n"));
        out.append(String.format("%-12s %5s %7s %10s %10s %6s  %-28s %s%n", "kernel", "id", "matches", "pcre",
                "tregex", "ratio", "features", "pattern"));
        Map<String, double[]> wins = new LinkedHashMap<>();
        for (Row r : rows) {
            String source = new String(patterns.get(r.pattern()).source(), StandardCharsets.ISO_8859_1);
            out.append(String.format("%-12s %5d %7d %10.0f %10.0f %6.2f  %-28s /%s/%n", r.kernel(), r.pattern(),
                    r.count(), r.pcre(), r.tregex(), r.tregex() / r.pcre(), r.features(),
                    source.length() > 70 ? source.substring(0, 70) + "..." : source));
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

    /** Loops over the matches in compiled code with cached interop, as a node calling TRegex would. */
    private static final class ExecLoop extends RootNode {
        @Child private InteropLibrary execs = InteropLibrary.getFactory().createDispatched(4);
        @Child private InteropLibrary results = InteropLibrary.getFactory().createDispatched(4);

        ExecLoop(TruffleLanguage<?> language) {
            super(language);
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] fns = (Object[]) frame.getArguments()[0];
            Object[] subjects = (Object[]) frame.getArguments()[1];
            long[] starts = (long[]) frame.getArguments()[2];
            long found = 0;
            try {
                for (int i = 0; i < fns.length; i++) {
                    Object result = execs.execute(fns[i], subjects[i], starts[i]);
                    if (results.asBoolean(results.readMember(result, "isMatch"))) {
                        found += results.asInt(results.invokeMember(result, "getEnd", 0));
                    }
                }
            } catch (InteropException e) {
                throw new IllegalStateException(e);
            }
            return found;
        }
    }

    private static Object execFor(TruffleLanguage.Env env, InteropLibrary interop, Map<Long, Object> execs,
                                  Map<Integer, String> unsupported, Match m, Pattern p, String flavor) {
        boolean anchored = (m.options() & ANCHORED) != 0 || (p.options() & ANCHORED) != 0;
        boolean mustAdvance = (m.options() & NOTEMPTY_ATSTART) != 0;
        long key = (long) m.pattern() << 2 | (anchored ? 1 : 0) | (mustAdvance ? 2 : 0);
        if (execs.containsKey(key)) {
            return execs.get(key);
        }
        Object exec = null;
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
                    + (anchored ? "match" : "search") + (mustAdvance ? ",MustAdvance=true" : "");
            String pattern = !flavor.equals("Python") ? source : source.replaceAll("\\(\\?<([A-Za-z_])", "(?P<$1");
            try {
                Object regex = env.parseInternal(Source.newBuilder("regex", options + "/" + pattern + "/" + flags,
                        "replay").internal(true).build()).call();
                if (regex == null || interop.isNull(regex)) {
                    unsupported.put(m.pattern(), "null: /" + source + "/");
                } else {
                    exec = interop.readMember(regex, "exec");
                }
            } catch (Exception e) {
                unsupported.put(m.pattern(), e.getClass().getSimpleName() + " " + e.getMessage() + ": /" + source
                        + "/");
            }
        }
        execs.put(key, exec);
        return exec;
    }

    private static long[] pcreMatch(Map<Integer, MemorySegment> codes, Map<Integer, MemorySegment> data,
                                    Map<Integer, MemorySegment> ovectors, Map<Integer, MemorySegment> nativeSubjects,
                                    Map<Integer, byte[]> subjects, MemorySegment context, Pattern p, Match m) {
        int rc = PcreFunctions.Native.match(codes.get(m.pattern()), p.jit(), nativeSubjects.get(m.subject()),
                subjects.get(m.subject()).length, m.start(), m.options(), data.get(m.pattern()), context);
        if (rc < 0) {
            return new long[] {-1, -1};
        }
        MemorySegment ovector = ovectors.get(m.pattern());
        return new long[] {ovector.getAtIndex(ValueLayout.JAVA_LONG, 0), ovector.getAtIndex(ValueLayout.JAVA_LONG, 1)};
    }

    private static long[] tregexMatch(InteropLibrary interop, Object exec, TruffleString subject, long start) {
        try {
            Object result = interop.execute(exec, subject, start);
            if (!interop.asBoolean(interop.readMember(result, "isMatch"))) {
                return new long[] {-1, -1};
            }
            return new long[] {interop.asInt(interop.invokeMember(result, "getStart", 0)),
                    interop.asInt(interop.invokeMember(result, "getEnd", 0))};
        } catch (InteropException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void pcreRun(Map<Integer, MemorySegment> codes, Map<Integer, MemorySegment> data,
                                Map<Integer, MemorySegment> nativeSubjects, Map<Integer, byte[]> subjects,
                                MemorySegment context, Map<Integer, Pattern> patterns, List<Match> matches) {
        for (Match m : matches) {
            PcreFunctions.Native.match(codes.get(m.pattern()), patterns.get(m.pattern()).jit(),
                    nativeSubjects.get(m.subject()), subjects.get(m.subject()).length, m.start(), m.options(),
                    data.get(m.pattern()), context);
        }
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        java.util.Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static void read(String file, Map<Integer, Pattern> patterns, Map<Integer, byte[]> subjects,
                             Map<String, List<Match>> sections) {
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

    private static byte[] bytes(DataInputStream in) throws IOException {
        byte[] bytes = new byte[in.readInt()];
        in.readFully(bytes);
        return bytes;
    }
}
