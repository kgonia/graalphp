package pl.codeloom.php.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The php-ast export corpus (Gate 2's WordPress and plugins; -Dphp.astCorpus=dir for another): AstReader's unread
 * function bodies against the eager reader, and what they save. Skipped when the corpus isn't exported.
 */
class AstCorpusTest {
    /** The kinds whose "stmts" child is a function body, translated on the function's first call. */
    static final Set<String> FUNCTIONS = Set.of("AST_FUNC_DECL", "AST_METHOD", "AST_CLOSURE", "AST_ARROW_FUNC");

    private static final JsonFactory JSON = JsonFactory.builder()
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(100_000)
                    .maxStringLength(Integer.MAX_VALUE).build())
            .build();

    private static List<Path> corpus;

    /** A function body's bytes in its file's export: [start, end). */
    record Range(long start, long end) {
        long length() {
            return end - start;
        }
    }

    @BeforeAll
    static void findCorpus() throws IOException {
        Path root = Path.of(System.getProperty("php.astCorpus", "target/ast/target/gate2/wordpress"));
        assumeTrue(Files.isDirectory(root), "no export at " + root);
        try (Stream<Path> files = Files.walk(root)) {
            corpus = files.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    /**
     * AstReader leaves every function body unread, and each one, read through its AstBody, is the tree the eager
     * reader (AstReader before bodies were left unread) gives for it: the trees are the same, bodies included.
     */
    @Test
    void lazyTreesAreTheEagerTrees() throws IOException {
        long bodies = 0;
        AstTrees.bodiesRead = 0;
        for (Path file : corpus) {
            byte[] json = Files.readAllBytes(file);
            Ast eager = EagerAstReader.read(new ByteArrayInputStream(json));
            Ast lazy = AstReader.read(new ByteArrayInputStream(json));
            String difference = AstTrees.firstDifference(eager, lazy);
            assertNull(difference, () -> file + ": " + difference);
            bodies += bodyRanges(json).size();
        }
        // every body was left unread and read once by the comparison
        assertEquals(bodies, AstTrees.bodiesRead);
        System.out.printf("%d files: the same trees read lazily, %d bodies read through AstBody%n", corpus.size(),
                bodies);
    }

    /** What a lazily read body could leave out of memory: the share of the export in bodies and doc comments. */
    @Test
    void shareOfBodiesAndDocComments() throws IOException {
        long total = 0;
        long outermost = 0;
        long bodies = 0;
        long docComments = 0;
        for (Path file : corpus) {
            byte[] json = Files.readAllBytes(file);
            total += json.length;
            List<Range> ranges = bodyRanges(json);
            bodies += ranges.size();
            long end = -1;
            for (Range range : ranges) {
                // in document order, a nested body starts inside the body before it
                if (range.start() >= end) {
                    outermost += range.length();
                    end = range.end();
                }
            }
            docComments += docCommentBytes(json);
        }
        System.out.printf("%d files, %.1f MB of JSON: %d function bodies, the outermost %.1f MB (%.0f%%); "
                        + "doc comments %.1f MB (%.0f%%)%n", corpus.size(), total / 1e6, bodies, outermost / 1e6,
                100.0 * outermost / total, docComments / 1e6, 100.0 * docComments / total);
    }

    /**
     * The heap the corpus's trees take read eagerly and read as AstReader does, bodies left unread as bytes: what
     * a lazily read body saves while the function isn't called. Live heap after a GC with each set of trees held,
     * so a rough number.
     */
    @Test
    void heapEagerAndLazy() throws IOException {
        long base = liveHeap();
        List<Object> eager = new ArrayList<>();
        for (Path file : corpus) {
            eager.add(EagerAstReader.read(new ByteArrayInputStream(Files.readAllBytes(file))));
        }
        long eagerBytes = liveHeap() - base;
        eager.clear();
        base = liveHeap();
        List<Object> lazy = new ArrayList<>();
        for (Path file : corpus) {
            lazy.add(AstReader.read(new ByteArrayInputStream(Files.readAllBytes(file))));
        }
        long lazyBytes = liveHeap() - base;
        System.out.printf("trees of %d files: %.0f MB read eagerly, %.0f MB with bodies unread (%.0f%% less)%n",
                lazy.size(), eagerBytes / 1e6, lazyBytes / 1e6, 100.0 - 100.0 * lazyBytes / eagerBytes);
    }

    private static long liveHeap() {
        Runtime runtime = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }

    /** The byte range of every function body in an export, in document order, found by scanning its tokens. */
    static List<Range> bodyRanges(byte[] json) {
        try (JsonParser parser = JSON.createParser(json)) {
            List<Range> ranges = new ArrayList<>();
            parser.nextToken();
            scan(parser, false, ranges);
            return ranges;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Scans the value at the current token; inFunction: it is the named children of a function node. */
    private static void scan(JsonParser parser, boolean inFunction, List<Range> ranges) throws IOException {
        JsonToken token = parser.currentToken();
        if (token == JsonToken.START_ARRAY) {
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                scan(parser, false, ranges);
            }
        } else if (token == JsonToken.START_OBJECT) {
            String kind = null;
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                parser.nextToken();
                if (field.equals("k")) {
                    kind = parser.getText();
                } else if (field.equals("c")) {
                    // the exporter writes the kind before the children
                    scan(parser, FUNCTIONS.contains(kind), ranges);
                } else if (inFunction && field.equals("stmts") && parser.currentToken() == JsonToken.START_OBJECT) {
                    long start = parser.currentTokenLocation().getByteOffset();
                    int index = ranges.size();
                    ranges.add(null);
                    scan(parser, false, ranges);
                    ranges.set(index, new Range(start, parser.currentLocation().getByteOffset()));
                } else {
                    scan(parser, false, ranges);
                }
            }
        }
    }

    private static long docCommentBytes(byte[] json) throws IOException {
        long bytes = 0;
        try (JsonParser parser = JSON.createParser(json)) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() == JsonToken.FIELD_NAME && parser.currentName().equals("docComment")
                        && parser.nextToken() == JsonToken.VALUE_STRING) {
                    bytes += parser.getTextLength();
                }
            }
        }
        return bytes;
    }
}
