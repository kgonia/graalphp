package pl.codeloom.php.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import pl.codeloom.php.parser.zend.CompilerGlobals;
import pl.codeloom.php.parser.zend.ScriptText;
import pl.codeloom.php.parser.zend.ZendCompileError;
import pl.codeloom.php.parser.zend.ZendParser;
import pl.codeloom.php.parser.zend.generated.ZendLanguageParser;
import pl.codeloom.php.parser.zend.generated.ZendLanguageScanner;
import pl.codeloom.php.parser.zend.zval;

/**
 * The Java parser against PHP 8.5 on the two corpora, Gate 2's WordPress (target/gate2/wordpress) and php-src's
 * tests (target/phpt/cases):
 * <ul>
 * <li>its scanner's tokens are token_get_all()'s (tests/parser/reference.sh writes them);
 * <li>its trees are php-ast's exports (tests/gate2/export.sh, tests/phpt/run.sh);
 * <li>a file php-ast rejects, it rejects with the same class, line and message.
 * </ul>
 * Each skips when its reference isn't there.
 */
class ZendParserCorpusTest {
    private static final Path REFERENCE = Path.of("target/parser/reference");
    private static final Path EXPORTS = Path.of("target/ast");
    private static final int SHOWN = 15;
    /** The line of tests/parser/reference.php that calls ast\parse_file. */
    private static final int REFERENCE_CALL_LINE = 25;

    private static final Map<Integer, String> TOKEN_NAMES = tokenNames();

    /** token_name(): the parser's token constants by number, with ext/tokenizer's one rename. */
    private static Map<Integer, String> tokenNames() {
        Map<Integer, String> names = new HashMap<>();
        for (Field field : ZendLanguageParser.Lexer.class.getFields()) {
            if (field.getType() == int.class && field.getName().startsWith("T_")) {
                try {
                    names.put(field.getInt(null), field.getName());
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        // ext/tokenizer/tokenizer_data.c
        names.put(ZendLanguageParser.Lexer.T_PAAMAYIM_NEKUDOTAYIM, "T_DOUBLE_COLON");
        return names;
    }

    /** One token as reference.php writes it. */
    private static String tokenLine(int token, byte[] script, int offset, int length, int line) {
        String text = Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(script, offset,
                offset + length));
        if (token >= 256) {
            return TOKEN_NAMES.getOrDefault(token, "UNKNOWN") + " " + line + " " + text;
        }
        return "'" + new String(script, offset, length, StandardCharsets.ISO_8859_1) + "' - " + text;
    }

    /** ext/tokenizer's tokenize(): token_get_all() without TOKEN_PARSE. */
    static List<String> tokenGetAll(byte[] script) {
        List<String> tokens = new ArrayList<>();
        CompilerGlobals CG = new CompilerGlobals();
        ZendLanguageScanner scanner = new ZendLanguageScanner(CG, script);
        zval token = new zval();
        int tokenLine = 1;
        int needTokens = -1;
        int tokenType;
        while ((tokenType = scanner.lex_scan(token, null)) != 0) {
            tokens.add(tokenLine(tokenType, scanner.yy_input, scanner.yy_text, scanner.yy_leng, tokenLine));
            // after T_HALT_COMPILER collect the next three non-dropped tokens
            if (needTokens != -1) {
                if (tokenType != ZendLanguageParser.Lexer.T_WHITESPACE
                        && tokenType != ZendLanguageParser.Lexer.T_OPEN_TAG
                        && tokenType != ZendLanguageParser.Lexer.T_COMMENT
                        && tokenType != ZendLanguageParser.Lexer.T_DOC_COMMENT && --needTokens == 0) {
                    // fetch the rest into a T_INLINE_HTML
                    if (scanner.yy_cursor < scanner.yy_limit) {
                        tokens.add(tokenLine(ZendLanguageParser.Lexer.T_INLINE_HTML, scanner.yy_input,
                                scanner.yy_cursor, scanner.yy_limit - scanner.yy_cursor, tokenLine));
                    }
                    break;
                }
            } else if (tokenType == ZendLanguageParser.Lexer.T_HALT_COMPILER) {
                needTokens = 3;
            }
            if (CG.increment_lineno != 0) {
                CG.zend_lineno++;
                CG.increment_lineno = 0;
            }
            tokenLine = CG.zend_lineno;
        }
        return tokens;
    }

    private static List<String> lines(Path file) {
        try {
            String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
            List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
            if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
                lines.remove(lines.size() - 1);
            }
            return lines;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Failures across files, the first few kept for the report. */
    private static final class Failures {
        final AtomicInteger count = new AtomicInteger();
        final List<String> shown = Collections.synchronizedList(new ArrayList<>());

        void add(String failure) {
            if (count.incrementAndGet() <= SHOWN) {
                shown.add(failure);
            }
        }

        void report(String what, int total) {
            System.out.printf("%s: %d of %d files differ%n", what, count.get(), total);
            for (String failure : shown) {
                System.out.println("  " + failure);
            }
        }
    }

    @Test
    void tokensAreTokenGetAll() {
        Path files = REFERENCE.resolve("files.txt");
        assumeTrue(Files.exists(files), "no reference at " + REFERENCE + " (tests/parser/reference.sh)");
        List<String> list = lines(files);
        Failures failures = new Failures();
        AtomicInteger tokens = new AtomicInteger();
        list.parallelStream().forEach(file -> {
            List<String> expected = lines(REFERENCE.resolve("tokens").resolve(file + ".tok"));
            List<String> actual;
            try {
                actual = tokenGetAll(read(Path.of(file)));
            } catch (RuntimeException e) {
                failures.add(file + ": " + e);
                return;
            }
            tokens.addAndGet(expected.size());
            for (int i = 0; i < Math.max(expected.size(), actual.size()); i++) {
                String e = i < expected.size() ? expected.get(i) : "(none)";
                String a = i < actual.size() ? actual.get(i) : "(none)";
                if (!e.equals(a)) {
                    failures.add(file + " token " + i + ": " + abbreviate(e) + " != " + abbreviate(a));
                    return;
                }
            }
        });
        failures.report("tokens (" + tokens.get() + ")", list.size());
        assertEquals(0, failures.count.get());
    }

    private static String abbreviate(String line) {
        return line.length() > 160 ? line.substring(0, 160) + "..." : line;
    }

    /** The exports under target/ast, with the source each was made from. */
    private static List<Path> exports(String corpus) {
        Path root = EXPORTS.resolve(corpus);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path sourceOf(Path export) {
        String relative = EXPORTS.relativize(export).toString();
        return Path.of(relative.substring(0, relative.length() - ".json".length()));
    }

    private static void treesAreExports(String corpus) {
        List<Path> exports = exports(corpus);
        assumeTrue(!exports.isEmpty(), "no exports at " + EXPORTS.resolve(corpus));
        Failures failures = new Failures();
        AtomicInteger orphans = new AtomicInteger();
        exports.parallelStream().forEach(export -> {
            Path source = sourceOf(export);
            if (!Files.exists(source)) {
                // an export whose source has gone (a scratch file exported once)
                orphans.incrementAndGet();
                return;
            }
            try {
                Ast expected = EagerAstReader.read(new ByteArrayInputStream(read(export)));
                Ast actual = ZendParser.parse(read(source)).ast();
                String difference = AstTrees.firstDifference(expected, actual);
                if (difference != null) {
                    failures.add(source + ": " + difference);
                }
            } catch (IOException | RuntimeException e) {
                failures.add(source + ": " + e);
            }
        });
        failures.report("trees of " + corpus + " (" + orphans.get() + " exports without a source left out)",
                exports.size() - orphans.get());
        assertEquals(0, failures.count.get());
    }

    @Test
    void treesAreWordPressExports() {
        treesAreExports("target/gate2/wordpress");
    }

    @Test
    void treesArePhptExports() {
        treesAreExports("target/phpt/cases");
    }

    /** The sources of a corpus's exports that are still there, with their exports. */
    private static List<Path[]> sourcesAndExports(String corpus) {
        List<Path[]> pairs = new ArrayList<>();
        for (Path export : exports(corpus)) {
            Path source = sourceOf(export);
            if (Files.exists(source)) {
                pairs.add(new Path[]{source, export});
            }
        }
        return pairs;
    }

    /** The bodies a lazy parse leaves unparsed in a tree: every function's, method's and closure's, nested too. */
    private static long bodies(Object tree) {
        long count = 0;
        if (tree instanceof Ast ast) {
            if ((ast.is("AST_FUNC_DECL") || ast.is("AST_METHOD") || ast.is("AST_CLOSURE"))
                    && ast.get("stmts") != null) {
                count++;
            }
            for (Object child : ast.children()) {
                count += bodies(child);
            }
            for (Object child : ast.namedValues()) {
                count += bodies(child);
            }
        }
        return count;
    }

    /**
     * Parsed with its bodies left as ranges of the text (SourceBody), each body read alone is the tree the whole
     * file's parse gives for it: the trees are the same, and every function, method and closure body was left.
     */
    private static void lazyTreesAreEager(String corpus) {
        List<Path[]> files = sourcesAndExports(corpus);
        assumeTrue(!files.isEmpty(), "no exports at " + EXPORTS.resolve(corpus));
        long bodies = 0;
        AstTrees.bodiesRead = 0;
        for (Path[] file : files) {
            byte[] script = read(file[0]);
            Ast eager = ZendParser.parse(script).ast();
            Ast lazy = ZendParser.parse(script, ScriptText.decode(script), true).ast();
            String difference = AstTrees.firstDifference(eager, lazy);
            assertNull(difference, () -> file[0] + ": " + difference);
            bodies += bodies(eager);
            assertEquals(bodies, AstTrees.bodiesRead, () -> file[0] + ": not every body was left unparsed");
        }
        System.out.printf("%s, %d files: the same trees with bodies parsed again from the text, %d bodies%n",
                corpus, files.size(), bodies);
    }

    @Test
    void lazyTreesAreEagerOnWordPress() {
        lazyTreesAreEager("target/gate2/wordpress");
    }

    @Test
    void lazyTreesAreEagerOnPhpt() {
        lazyTreesAreEager("target/phpt/cases");
    }

    private static long liveHeap() {
        Runtime runtime = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /**
     * The heap WordPress's trees take, as AstCorpusTest.heapEagerAndLazy measures it: from the exports (eager, and
     * bodies as JSON bytes) and from the parser (eager, and bodies as ranges of the text, the text counted: the
     * runtime holds it as the file's Source either way).
     */
    @Test
    void heapOfTrees() throws IOException {
        List<Path[]> files = sourcesAndExports("target/gate2/wordpress");
        assumeTrue(!files.isEmpty(), "no exports");
        List<Object> held = new ArrayList<>();
        long base = liveHeap();
        for (Path[] file : files) {
            held.add(EagerAstReader.read(new ByteArrayInputStream(read(file[1]))));
        }
        long exportEager = liveHeap() - base;
        held.clear();
        base = liveHeap();
        for (Path[] file : files) {
            held.add(AstReader.read(new ByteArrayInputStream(read(file[1]))));
        }
        long exportLazy = liveHeap() - base;
        held.clear();
        base = liveHeap();
        for (Path[] file : files) {
            held.add(ZendParser.parse(read(file[0])).ast());
        }
        long javaEager = liveHeap() - base;
        held.clear();
        base = liveHeap();
        for (Path[] file : files) {
            held.add(ScriptText.decode(read(file[0])));
        }
        long texts = liveHeap() - base;
        held.clear();
        base = liveHeap();
        for (Path[] file : files) {
            byte[] script = read(file[0]);
            held.add(ZendParser.parse(script, ScriptText.decode(script), true).ast());
        }
        long javaLazy = liveHeap() - base;
        System.out.printf("trees of %d files: exports %.0f MB eager, %.0f MB bodies as JSON; parser %.0f MB eager, "
                        + "%.0f MB bodies as text (the texts alone %.0f MB)%n", held.size(), exportEager / 1e6,
                exportLazy / 1e6, javaEager / 1e6, javaLazy / 1e6, texts / 1e6);
    }

    /**
     * Reading WordPress's trees from the exports against parsing the sources, each from bytes in memory, in
     * interleaved rounds (-Dphp.parseRounds, 10): the median of each.
     */
    @Test
    void parseTimeAgainstJsonRead() throws IOException {
        List<Path[]> files = sourcesAndExports("target/gate2/wordpress");
        assumeTrue(!files.isEmpty(), "no exports");
        List<byte[]> sources = new ArrayList<>();
        List<byte[]> exports = new ArrayList<>();
        long sourceBytes = 0;
        long exportBytes = 0;
        for (Path[] file : files) {
            byte[] source = read(file[0]);
            byte[] export = read(file[1]);
            sources.add(source);
            exports.add(export);
            sourceBytes += source.length;
            exportBytes += export.length;
        }
        String[] names = {"export, bodies as JSON (AstReader)", "export, eager (EagerAstReader)",
                "parser, bodies as text", "parser, eager"};
        int rounds = Integer.getInteger("php.parseRounds", 10);
        long[][] times = new long[names.length][rounds];
        long sink = 0;
        for (int round = 0; round < rounds; round++) {
            for (int variant = 0; variant < names.length; variant++) {
                long start = System.nanoTime();
                for (int i = 0; i < sources.size(); i++) {
                    byte[] source = sources.get(i);
                    Ast ast = switch (variant) {
                        case 0 -> AstReader.read(new ByteArrayInputStream(exports.get(i)));
                        case 1 -> EagerAstReader.read(new ByteArrayInputStream(exports.get(i)));
                        case 2 -> ZendParser.parse(source, ScriptText.decode(source), true).ast();
                        default -> ZendParser.parse(source).ast();
                    };
                    sink += ast.line;
                }
                times[variant][round] = System.nanoTime() - start;
            }
        }
        System.out.printf("%d files, %.1f MB of PHP, %.1f MB of JSON, %d rounds (sink %d):%n", files.size(),
                sourceBytes / 1e6, exportBytes / 1e6, rounds, sink);
        for (int variant = 0; variant < names.length; variant++) {
            long[] sorted = times[variant].clone();
            java.util.Arrays.sort(sorted);
            System.out.printf("  %-38s median %6.0f ms  (first round %6.0f ms, best %6.0f ms)%n", names[variant],
                    sorted[rounds / 2] / 1e6, times[variant][0] / 1e6, sorted[0] / 1e6);
        }
    }

    /** A file php-ast rejects, the parser rejects as PHP does: errors.tsv's class, line and message. */
    @Test
    void errorsArePhps() {
        Path errors = REFERENCE.resolve("errors.tsv");
        assumeTrue(Files.exists(errors), "no reference at " + REFERENCE + " (tests/parser/reference.sh)");
        List<String> list = lines(errors);
        Failures failures = new Failures();
        list.parallelStream().forEach(line -> {
            String[] fields = line.split("\t", 4);
            String file = fields[0];
            String expected = fields[1] + " line " + fields[2] + ": " + unescape(fields[3]);
            String actual;
            try {
                ZendParser.parse(read(Path.of(file)));
                actual = "(parsed)";
            } catch (ZendCompileError e) {
                // reference.php calls ast\parse_file on its line 25, which an error without a compiled file takes
                actual = e.className() + " line " + (e.atExecutedLine() ? REFERENCE_CALL_LINE : e.line()) + ": "
                        + new String(e.messageBytes(), StandardCharsets.ISO_8859_1);
            } catch (RuntimeException e) {
                actual = e.toString();
            }
            if (!expected.equals(actual)) {
                failures.add(file + ": " + expected + " != " + actual);
            }
        });
        failures.report("errors", list.size());
        assertEquals(0, failures.count.get());
    }

    /** reference.php's escapes of a message: \\t, \\n, \\\\. */
    private static String unescape(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < text.length()) {
                char next = text.charAt(++i);
                out.append(next == 't' ? '\t' : next == 'n' ? '\n' : next);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
