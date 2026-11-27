package pl.codeloom.php.b1;

import pl.codeloom.php.b1.runtime.B1Array;
import pl.codeloom.php.b1.runtime.B1Bytes;
import pl.codeloom.php.b1.runtime.B1Object;
import pl.codeloom.php.b1.runtime.B1RequestContext;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

/**
 * Real-PHP B1 benchmark driver: Gate 1 hooks, bench/b1-precheck/probe-b/php-ref/hot.php kernels and bench/k0, all
 * compiled by B1 from PHP source (bench/b1-week2/php drivers, unmodified WordPress and K0 files). Truffle-free.
 * <pre>
 *   check                                  every kernel and variant once, against PHP 8.5.13 checksums
 *   hooks|hooks-ctxb|hooks-2ctx ROUNDS     bench/gate1/run.php hooks (default 400 rounds)
 *   aget|post|mega|none|one|hasf ROUNDS    hot.php kernel, 250 calls per round (default 3000 rounds)
 *   mega-ctxb|mega-2ctx ROUNDS             CtxB alone, or CtxB and CtxA alternating
 *   k0 KERNEL|all ROUNDS                   bench/k0/run.php
 * </pre>
 * Paths resolve against the working directory (the repository root); -Db1.gate1=, -Db1.wordpress= (hot.php's
 * WordPress, default target/gate2/wordpress) and -Db1.budget= (bytecode budget, default 7500) override them.
 * Rows: "kernel round ns checksum", as ProbeBMain and the PHP drivers print them; STEADY_START_MS/STEADY_END_MS
 * bracket the second half. Every mode shuts its contexts down and requires no live destructibles or GC roots.
 */
public final class B1BenchMain {
    // PHP 8.5.13 (bench/b1-week2/php/oracle.php, bench/gate1/run.php, hot.php and bench/k0/run.php).
    static final String HOOKS = "393035:a61726cd", HOOKS_CTXB = "416555:9e619366";
    static final Map<String, Long> HOT = Map.of("mega", 535000L, "none", 195000L, "one", 20000L, "hasf", 110000L,
            "aget", 60000L, "post", 130000L);
    static final long MEGA_CTXB = 560000L;
    static final Map<String, String> K0 = ordered("fannkuch", "9:68433f01", "spectralnorm", "13:6030bc28",
            "binarytrees", "288:74e4d5ab", "nbody", "30:55491bc6", "fib", "6:caa8fa41", "intloop", "6:c82c23fb",
            "floatloop", "16:1d63536c", "mandelbrot", "9:530345e3", "sieve", "5:24dd8c4a", "bubblesort", "9:dd1cc795");
    static final Map<String, String> K0_SMALL = ordered("fannkuch", "8:d9faf27e", "spectralnorm", "13:3dd4949d",
            "binarytrees", "155:10ccaec4", "nbody", "30:913ad1d9", "fib", "3:3f23772e", "intloop", "6:68595e66",
            "floatloop", "16:38ab3bc6", "mandelbrot", "9:6b88a669", "sieve", "3:09be594d", "bubblesort", "9:233ed811");
    static final Map<String, Long> K0_SMALL_ARGUMENT = Map.of("fannkuch", 7L, "spectralnorm", 50L, "binarytrees", 6L,
            "nbody", 1000L, "fib", 15L, "intloop", 10000L, "floatloop", 10000L, "mandelbrot", 40L, "sieve", 5000L,
            "bubblesort", 100L);
    static final int INNER = 250;
    static volatile long sink;

    private final Path root;
    private final int budget;
    private final B1CodeCache cache = new B1CodeCache();
    private final List<B1RequestContext> contexts = new ArrayList<>();
    private final Map<B1RequestContext, ByteArrayOutputStream> outputs = new LinkedHashMap<>();

    B1BenchMain(Path root, int budget) {
        this.root = root;
        this.budget = budget;
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return java.util.Collections.unmodifiableMap(map);
    }

    Path gate1Wordpress() { return path("b1.gate1", "bench/gate1/wordpress"); }
    Path hotWordpress() { return path("b1.wordpress", "target/gate2/wordpress"); }
    Path corpus() { return path("b1.corpus", "bench/gate1/corpus.php"); }
    Path drivers() { return root.resolve("bench/b1-week2/php"); }
    private Path path(String property, String fallback) {
        return root.resolve(System.getProperty(property, fallback)).toAbsolutePath().normalize();
    }

    /** A request context sharing this run's linked code. PHP output (there should be none) is kept per context. */
    B1RequestContext context() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        B1RequestContext ctx = new B1RequestContext(out);
        ctx.shareCode(cache);
        contexts.add(ctx);
        outputs.put(ctx, out);
        return ctx;
    }

    /** Shuts down every context and requires D17 completeness: no output, destructibles or GC roots left. */
    void finish() {
        int maxCode = 0, helpers = 0;
        for (B1RequestContext ctx : contexts) {
            maxCode = Math.max(maxCode, ctx.largestMethod());
            helpers = Math.max(helpers, ctx.outlineHelpers());
            ctx.shutdown();
            if (ctx.liveDestructibleCount() != 0 || ctx.gcRootCount() != 0)
                throw new AssertionError("D17: " + ctx.liveDestructibleCount() + " live destructibles, "
                        + ctx.gcRootCount() + " GC roots after shutdown");
            byte[] printed = outputs.get(ctx).toByteArray();
            if (printed.length != 0) throw new AssertionError("Unexpected PHP output: " + new String(printed, StandardCharsets.UTF_8));
        }
        if (Boolean.getBoolean("b1.methods")) {
            for (B1Compiler.CompiledFile file : cache.files()) for (B1Compiler.MethodSize method : file.methods())
                System.out.println("B1METHOD " + method.function() + " " + method.hiddenClass() + " " + method.method() + " " + method.bytes());
        }
        System.out.printf("B1LINT hidden_classes=%d linked_units=%d shared_installs=%d max_code=%d outline_helpers=%d violations=0%n",
                cache.hiddenClasses(), cache.size(), cache.hits(), maxCode, helpers);
        contexts.clear();
        outputs.clear();
    }

    private static long uptimeMs() { return ManagementFactory.getRuntimeMXBean().getUptime(); }

    // ---------------------------------------------------------------- Gate 1 hooks

    /** bench/gate1/kernels.php bench_headings, as bench/b1-precheck/probe-b Corpus.java reads corpus.php. */
    static B1Array headings(Path corpus) throws IOException {
        String text = Files.readString(corpus, StandardCharsets.ISO_8859_1);
        CorpusReader src = new CorpusReader(text);
        B1Array headings = new B1Array(64);
        src.expect("return [");
        src.space();
        Pattern heading = Pattern.compile("<h[1-6][^>]*>(.*?)</h[1-6]>", Pattern.DOTALL);
        while (src.text.charAt(src.at) == '[') {
            src.expect("[");
            headings.append(null, bytes(src.quoted()));
            src.expect(",");
            Matcher matches = heading.matcher(src.quoted());
            while (matches.find()) headings.append(null, bytes(matches.group(1)));
            src.expect(",");
            src.space();
            src.expect(src.text.startsWith("true", src.at) ? "true" : "false");
            src.expect("],");
            src.space();
        }
        src.expect("];");
        src.space();
        if (src.at != src.text.length()) throw new IllegalArgumentException("Trailing corpus data");
        // Read-only host data shared by every context: frozen, so no request counts owners or GC roots on it.
        return headings.freeze();
    }
    private static B1Bytes bytes(String latin1) { return B1Bytes.of(latin1.getBytes(StandardCharsets.ISO_8859_1)); }

    private static final class CorpusReader {
        final String text;
        int at;
        CorpusReader(String text) { this.text = text; at = text.indexOf("return ["); }
        void space() { while (at < text.length() && " \t\n\r".indexOf(text.charAt(at)) >= 0) at++; }
        void expect(String token) {
            space();
            if (!text.startsWith(token, at)) throw new IllegalArgumentException("Expected " + token + " at " + at);
            at += token.length();
        }
        String quoted() {
            expect("'");
            StringBuilder s = new StringBuilder();
            while (at < text.length()) {
                char c = text.charAt(at++);
                if (c == '\'') return s.toString();
                if (c == '\\' && (text.charAt(at) == '\\' || text.charAt(at) == '\'')) c = text.charAt(at++);
                s.append(c);
            }
            throw new IllegalArgumentException("Unterminated PHP string literal");
        }
    }

    /** The hooks kernel closure of hooks-a.php (CtxA) or hooks-b.php (CtxB), owned by the caller. */
    Object hooksKernel(B1RequestContext ctx, boolean ctxB, B1Array headings) {
        ctx.setGlobal("b1_wordpress", B1Bytes.ofUtf8(gate1Wordpress().toString()));
        Object factory = B1Compiler.runScript(ctx, drivers().resolve(ctxB ? "hooks-b.php" : "hooks-a.php"), budget);
        try { return ctx.callHost(factory, headings); }
        finally { B1Object.release(ctx, factory); }
    }

    static String hooksRound(B1RequestContext ctx, Object kernel, B1Array corpus, long[] ns) {
        long t = System.nanoTime();
        Object result = ctx.callHost(kernel, corpus);
        ns[0] = System.nanoTime() - t;
        try { return checksum(result); }
        finally { B1Object.release(ctx, result); }
    }

    /** bench/gate1/run.php bench_checksum. */
    static String checksum(Object result) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        render(out, result);
        byte[] bytes = out.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return bytes.length + ":" + HexFormat.of().toHexDigits((int) crc.getValue());
    }
    private static void render(ByteArrayOutputStream out, Object value) {
        if (value instanceof B1Array array) {
            out.write('[');
            for (int i = 0; i < array.size(); i++) {
                Object key = array.keyAt(i);
                out.writeBytes(key instanceof B1Bytes text ? text.toString().getBytes(StandardCharsets.UTF_8) : key.toString().getBytes(StandardCharsets.US_ASCII));
                out.writeBytes("=>".getBytes(StandardCharsets.US_ASCII));
                render(out, array.valueAt(i));
                out.write(',');
            }
            out.write(']');
        } else if (value instanceof B1Bytes text) {
            out.writeBytes("string:".getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(text.toByteArray());
        } else if (value instanceof Long n) out.writeBytes(("integer:" + n).getBytes(StandardCharsets.US_ASCII));
        else if (value instanceof Boolean b) out.writeBytes((b ? "true" : "false").getBytes(StandardCharsets.US_ASCII));
        else if (value == null) out.writeBytes("null".getBytes(StandardCharsets.US_ASCII));
        else throw new AssertionError("bench_render of " + value.getClass().getSimpleName() + " is not part of this harness");
    }

    void hooks(String mode, int rounds) throws IOException {
        B1Array headings = headings(corpus());
        B1Array corpus = new B1Array(0).freeze();
        boolean a = !mode.equals("hooks-ctxb"), b = !mode.equals("hooks");
        B1RequestContext ctxA = a ? context() : null, ctxB = b ? context() : null;
        Object kernelA = a ? hooksKernel(ctxA, false, headings) : null;
        Object kernelB = b ? hooksKernel(ctxB, true, headings) : null;
        long[] ns = new long[1];
        int half = rounds / 2;
        for (int round = 1; round <= rounds; round++) {
            if (round == half + 1) System.out.println("STEADY_START_MS " + uptimeMs());
            if (b) {
                String check = hooksRound(ctxB, kernelB, corpus, ns);
                if (!HOOKS_CTXB.equals(check)) throw new AssertionError("hooks CtxB checksum " + check + " != " + HOOKS_CTXB);
                System.out.println((a ? "hooks-2ctx-b " : "hooks-ctxb ") + round + " " + ns[0] + " " + check);
            }
            if (a) {
                String check = hooksRound(ctxA, kernelA, corpus, ns);
                if (!HOOKS.equals(check)) throw new AssertionError("hooks checksum " + check + " != " + HOOKS);
                System.out.println((b ? "hooks-2ctx-a " : "hooks ") + round + " " + ns[0] + " " + check);
            }
        }
        System.out.println("STEADY_END_MS " + uptimeMs());
        if (b && !(ctxB.globalValue("b1_all_calls") instanceof Long calls && calls > 0))
            throw new AssertionError("CtxB 'all' hook never fired");
        if (a && ctxA.globalValue("b1_all_calls") != null) throw new AssertionError("CtxA must not see CtxB's 'all' hook");
        if (a) B1Object.release(ctxA, kernelA);
        if (b) B1Object.release(ctxB, kernelB);
        finish();
    }

    // ---------------------------------------------------------------- hot.php kernels

    /** hot.php's setup in a context: ['kernels' => [...], 'run' => runner], owned by the caller. */
    B1Array hot(B1RequestContext ctx, boolean ctxB) {
        B1Compiler.linkDeclarations(ctx, root.resolve("bench/b1-precheck/probe-b/php-ref/hot-fns.php"),
                Set.of("_wp_array_get", "__return_true"), budget);
        ctx.setGlobal("b1_wordpress", B1Bytes.ofUtf8(hotWordpress().toString()));
        ctx.setGlobal("b1_ctxb", ctxB);
        return (B1Array) B1Compiler.runScript(ctx, drivers().resolve("hot.php"), budget);
    }

    static long hotRound(B1RequestContext ctx, B1Array hot, String kernel, long[] ns) {
        Object closure = ((B1Array) hot.get("kernels")).get(kernel);
        if (closure == null) throw new IllegalArgumentException("Unknown hot.php kernel " + kernel);
        long t = System.nanoTime();
        Object sum = ctx.callHost(hot.get("run"), closure, (long) INNER);
        ns[0] = Math.round(Math.rint((System.nanoTime() - t) / (double) INNER));
        return (Long) sum;
    }

    void hotKernel(String mode, int rounds) {
        boolean megaPair = mode.startsWith("mega-");
        String kernel = megaPair ? "mega" : mode;
        boolean a = !mode.equals("mega-ctxb"), b = megaPair;
        B1RequestContext ctxA = a ? context() : null, ctxB = b ? context() : null;
        B1Array hotA = a ? hot(ctxA, false) : null;
        B1Array hotB = b ? hot(ctxB, true) : null;
        long expected = HOT.get(kernel);
        long[] ns = new long[1];
        int half = rounds / 2;
        for (int round = 0; round < rounds; round++) {
            if (round == half) System.out.println("STEADY_START_MS " + uptimeMs());
            if (b) {
                long sum = hotRound(ctxB, hotB, kernel, ns);
                if (sum != MEGA_CTXB) throw new AssertionError("mega CtxB checksum " + sum + " != " + MEGA_CTXB);
                System.out.println((a ? "mega-2ctx-b " : "mega-ctxb ") + round + " " + ns[0] + " " + sum);
            }
            if (a) {
                long sum = hotRound(ctxA, hotA, kernel, ns);
                sink = sum;
                if (sum != expected) throw new AssertionError(kernel + " checksum " + sum + " != " + expected);
                System.out.println((b ? "mega-2ctx-a " : kernel + " ") + round + " " + ns[0] + " " + sum);
            }
        }
        System.out.println("STEADY_END_MS " + uptimeMs());
        if (b && !(ctxB.globalValue("b1_all_calls") instanceof Long calls && calls > 0))
            throw new AssertionError("CtxB 'all' hook never fired");
        if (a && !Long.valueOf(0).equals(ctxA.globalValue("b1_all_calls"))) throw new AssertionError("CtxA saw an 'all' hook");
        if (a) B1Object.release(ctxA, hotA);
        if (b) B1Object.release(ctxB, hotB);
        finish();
    }

    // ---------------------------------------------------------------- bench/k0

    void k0(String selection, int rounds) {
        B1RequestContext ctx = context();
        B1Array kernels = (B1Array) B1Compiler.runScript(ctx, root.resolve("bench/k0/kernels.php"), budget);
        List<String> names = selection.equals("all") ? List.copyOf(K0.keySet()) : List.of(selection.split(","));
        int half = rounds / 2;
        for (String name : names) {
            Object closure = kernels.get(name);
            if (closure == null || !K0.containsKey(name)) throw new IllegalArgumentException("Unknown K0 kernel " + name);
            for (int i = 1; i <= rounds; i++) {
                if (names.size() == 1 && i == half + 1) System.out.println("STEADY_START_MS " + uptimeMs());
                long t = System.nanoTime();
                Object result = ctx.callHost(closure);
                long ns = System.nanoTime() - t;
                String check;
                try { check = json(result); } finally { B1Object.release(ctx, result); }
                if (!K0.get(name).equals(check)) throw new AssertionError(name + " checksum " + check + " != " + K0.get(name));
                System.out.println(name + " " + i + " " + ns + " " + check);
            }
            if (names.size() == 1) System.out.println("STEADY_END_MS " + uptimeMs());
        }
        B1Object.release(ctx, kernels);
        finish();
    }

    /** bench/k0/run.php: json_encode of the result, then "length:crc32". */
    static String json(Object result) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        jsonValue(out, result);
        byte[] bytes = out.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return bytes.length + ":" + HexFormat.of().toHexDigits((int) crc.getValue());
    }
    private static void jsonValue(ByteArrayOutputStream out, Object value) {
        if (value instanceof B1Array array) {
            boolean list = true;
            for (int i = 0; i < array.size() && list; i++) list = Long.valueOf(i).equals(array.keyAt(i));
            out.write(list ? '[' : '{');
            for (int i = 0; i < array.size(); i++) {
                if (i > 0) out.write(',');
                if (!list) {
                    Object key = array.keyAt(i);
                    jsonValue(out, key instanceof B1Bytes ? key : B1Bytes.ofUtf8(key.toString()));
                    out.write(':');
                }
                jsonValue(out, array.valueAt(i));
            }
            out.write(list ? ']' : '}');
        } else if (value instanceof B1Bytes text) {
            out.write('"');
            for (byte b : text.toByteArray()) {
                switch (b) {
                    case '"' -> out.writeBytes("\\\"".getBytes(StandardCharsets.US_ASCII));
                    case '\\' -> out.writeBytes("\\\\".getBytes(StandardCharsets.US_ASCII));
                    case '/' -> out.writeBytes("\\/".getBytes(StandardCharsets.US_ASCII));
                    case '\b' -> out.writeBytes("\\b".getBytes(StandardCharsets.US_ASCII));
                    case '\f' -> out.writeBytes("\\f".getBytes(StandardCharsets.US_ASCII));
                    case '\n' -> out.writeBytes("\\n".getBytes(StandardCharsets.US_ASCII));
                    case '\r' -> out.writeBytes("\\r".getBytes(StandardCharsets.US_ASCII));
                    case '\t' -> out.writeBytes("\\t".getBytes(StandardCharsets.US_ASCII));
                    default -> {
                        if (b < 0) throw new AssertionError("json_encode of non-ASCII text is not part of this harness");
                        if (b < 0x20) out.writeBytes(String.format("\\u%04x", (int) b).getBytes(StandardCharsets.US_ASCII));
                        else out.write(b);
                    }
                }
            }
            out.write('"');
        } else if (value instanceof Long n) out.writeBytes(n.toString().getBytes(StandardCharsets.US_ASCII));
        else if (value instanceof Boolean b) out.writeBytes((b ? "true" : "false").getBytes(StandardCharsets.US_ASCII));
        else if (value == null) out.writeBytes("null".getBytes(StandardCharsets.US_ASCII));
        else throw new AssertionError("json_encode of " + value.getClass().getSimpleName() + " is not part of this harness");
    }

    // ---------------------------------------------------------------- check

    /** Every kernel and paired variant against PHP's checksums, then the runtime lint. */
    void check() throws IOException {
        long[] ns = new long[1];
        B1Array headings = headings(corpus());
        B1Array corpus = new B1Array(0).freeze();
        for (boolean ctxB : new boolean[]{false, true}) {
            B1RequestContext ctx = context();
            Object kernel = hooksKernel(ctx, ctxB, headings);
            for (int i = 0; i < 2; i++) {
                String check = hooksRound(ctx, kernel, corpus, ns);
                require(ctxB ? "hooks-ctxb" : "hooks", ctxB ? HOOKS_CTXB : HOOKS, check);
            }
            if (ctxB) require("hooks-ctxb all-hook calls", 50400L, ctx.globalValue("b1_all_calls"));
            B1Object.release(ctx, kernel);
        }
        finish();
        for (boolean ctxB : new boolean[]{false, true}) {
            B1RequestContext ctx = context();
            B1Array hot = hot(ctx, ctxB);
            for (String kernel : List.of("mega", "none", "one", "hasf", "aget", "post")) {
                long sum = hotRound(ctx, hot, kernel, ns);
                require(kernel + (ctxB ? "-ctxb" : ""), ctxB && kernel.equals("mega") ? MEGA_CTXB : HOT.get(kernel), sum);
            }
            require(ctxB ? "hot-b all-hook calls" : "hot-a all-hook calls", ctxB ? 40000L : 0L, ctx.globalValue("b1_all_calls"));
            B1Object.release(ctx, hot);
        }
        finish();
        // Paired contexts: both share every hidden class and keep their own results.
        B1RequestContext pairA = context(), pairB = context();
        Object hooksA = hooksKernel(pairA, false, headings), hooksB = hooksKernel(pairB, true, headings);
        for (int i = 0; i < 2; i++) {
            require("hooks-2ctx-b", HOOKS_CTXB, hooksRound(pairB, hooksB, corpus, ns));
            require("hooks-2ctx-a", HOOKS, hooksRound(pairA, hooksA, corpus, ns));
        }
        B1Object.release(pairA, hooksA);
        B1Object.release(pairB, hooksB);
        finish();
        pairA = context();
        pairB = context();
        B1Array hotA = hot(pairA, false), hotB = hot(pairB, true);
        for (int i = 0; i < 2; i++) {
            require("mega-2ctx-b", MEGA_CTXB, hotRound(pairB, hotB, "mega", ns));
            require("mega-2ctx-a", HOT.get("mega"), hotRound(pairA, hotA, "mega", ns));
        }
        B1Object.release(pairA, hotA);
        B1Object.release(pairB, hotB);
        finish();
        B1RequestContext ctx = context();
        B1Array kernels = (B1Array) B1Compiler.runScript(ctx, root.resolve("bench/k0/kernels.php"), budget);
        for (String name : K0.keySet()) {
            Object small = ctx.callHost(B1Bytes.ofUtf8("k0_" + name), K0_SMALL_ARGUMENT.get(name));
            try { require(name + "-small", K0_SMALL.get(name), json(small)); } finally { B1Object.release(ctx, small); }
            Object full = ctx.callHost(kernels.get(name));
            try { require(name, K0.get(name), json(full)); } finally { B1Object.release(ctx, full); }
        }
        B1Object.release(ctx, kernels);
        finish();
        System.out.println(runtimeLint());
        System.out.println("CHECK OK");
    }

    /** Runs one PHP file at the B1 language level as a CLI request: output to stdout, uncaught errors fatal. */
    void script(Path file) {
        B1RequestContext ctx = new B1RequestContext(System.out);
        ctx.shareCode(cache);
        try {
            B1Object.release(ctx, B1Compiler.runScript(ctx, file, budget));
        } catch (pl.codeloom.php.b1.runtime.B1Thrown thrown) {
            System.out.print("PHP Fatal error:  Uncaught " + thrown.exception.read("message"));
        } finally {
            ctx.shutdown();
        }
        if (ctx.liveDestructibleCount() != 0 || ctx.gcRootCount() != 0) throw new AssertionError("D17 after shutdown");
    }

    private static void require(String name, Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError(name + ": " + actual + " != PHP " + expected);
        System.out.println("PASS " + name + " " + actual);
    }

    /** B1-01..B1-13 over the runtime classes (the emitted ones are linted when they are linked). */
    static String runtimeLint() {
        try {
            Path runtime = Path.of(B1RequestContext.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .resolve("pl/codeloom/php/b1/runtime");
            int count = 0;
            try (java.util.stream.Stream<Path> files = Files.walk(runtime)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                    B1Lint.requireClean(Files.readAllBytes(file), B1Lint.Kind.RUNTIME);
                    count++;
                }
            }
            return "B1LINT runtime_classes=" + count + " violations=0";
        } catch (IOException | java.net.URISyntaxException | java.nio.file.FileSystemNotFoundException ex) {
            return "B1LINT runtime_classes=unavailable (" + ex + ")";
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Usage: B1BenchMain check | hooks|hooks-ctxb|hooks-2ctx ROUNDS"
                + " | aget|post|mega|none|one|hasf|mega-ctxb|mega-2ctx ROUNDS | k0 KERNEL|all ROUNDS");
        B1BenchMain bench = new B1BenchMain(Path.of("").toAbsolutePath(), Integer.getInteger("b1.budget", 7500));
        switch (args[0]) {
            case "check" -> bench.check();
            case "script" -> bench.script(Path.of(args[1]));
            case "hooks", "hooks-ctxb", "hooks-2ctx" -> bench.hooks(args[0], args.length > 1 ? Integer.parseInt(args[1]) : 400);
            case "aget", "post", "mega", "none", "one", "hasf", "mega-ctxb", "mega-2ctx" ->
                    bench.hotKernel(args[0], args.length > 1 ? Integer.parseInt(args[1]) : 3000);
            case "k0" -> bench.k0(args.length > 1 ? args[1] : "all", args.length > 2 ? Integer.parseInt(args[2]) : 10);
            default -> throw new IllegalArgumentException("Unknown mode " + args[0]);
        }
    }
}
