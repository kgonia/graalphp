package pl.codeloom.php.launcher;

import pl.codeloom.php.runtime.NativeClock;
import pl.codeloom.php.runtime.Phases;

import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * One request's phases (-Dphp.phases=true, {@link Phases}), printed to stderr as a line {@code phases {...}} once
 * the response is sent: the request thread's wall and CPU time, the context's creation and close, the phases, the
 * native calls ({@link NativeClock}, with -Dphp.nativeclock=true), the GC, and the process's CPU so far by thread
 * name, which a harness turns into per-visit compilation and GC time (tests/gate2/phases_report.py). A line
 * {@code phases-start {...}} gives the process's CPU when the server starts listening, where the first visit's
 * window begins.
 *
 * <p>The process's threads come from /proc/self/task: a thread's name is the one Linux keeps (15 characters: the
 * JVM keeps a name's first, a native image its last), digits dropped so that a pool's threads add up
 * ("TruffleCompiler", "GC Thread#", natively "mpilerThread-"); a thread counts its user and system ticks. Where
 * there is no /proc the map is empty.
 */
final class RequestPhases {
    /** Linux's USER_HZ: /proc's ticks per second. */
    private static final int TICKS = 100;

    private final long start;
    /** The JVM's thread bean; looked up per request, not held in a static: a native image builds this class. */
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final long cpuStart;
    private final long nativeStart;
    private final long nativeCompileStart;
    private final long gcTimeStart;
    private final long gcCountStart;
    private final Phases phases;
    private long contextReady;
    private long evaluated;

    RequestPhases() {
        phases = Phases.begin();
        start = System.nanoTime();
        cpuStart = threads.getCurrentThreadCpuTime();
        nativeStart = NativeClock.nanos();
        nativeCompileStart = NativeClock.compileNanos();
        gcTimeStart = gcTime();
        gcCountStart = gcCount();
    }

    /** The context is built: the language's per-request setup is done. */
    void contextReady() {
        contextReady = System.nanoTime();
    }

    /** The script has run; what follows is the context's close. */
    void evaluated() {
        evaluated = System.nanoTime();
    }

    /** Prints the request's line; call after the response is sent. */
    void print(String method, String uri, int status) {
        long end = System.nanoTime();
        long cpu = threads.getCurrentThreadCpuTime() - cpuStart;
        Phases now = Phases.current();
        StringBuilder line = new StringBuilder(1024).append("phases {");
        field(line, "method", method);
        field(line, "uri", uri);
        field(line, "status", status);
        field(line, "wall_ns", end - start);
        field(line, "cpu_ns", cpu);
        field(line, "context_ns", contextReady - start);
        field(line, "close_ns", evaluated == 0 ? 0 : end - evaluated);
        String[] names = {"execution", "include", "read", "parse", "body", "file_translate", "translate"};
        for (int i = 0; i < Phases.COUNT; i++) {
            field(line, names[i] + "_ns", now.nanos[i]);
            field(line, names[i] + "_n", now.entries[i]);
        }
        field(line, "file_bytes", now.fileBytes);
        field(line, "body_chars_left", now.bodyCharsLeft);
        field(line, "body_chars_read", now.bodyCharsRead);
        String[] kinds = {"functions", "methods", "closures"};
        for (int i = 0; i < kinds.length; i++) {
            field(line, "declared_" + kinds[i], now.declared[i]);
            field(line, "translated_" + kinds[i], now.translated[i]);
            field(line, "declared_" + kinds[i] + "_total", Phases.declaredTotal(i));
            field(line, "translated_" + kinds[i] + "_total", Phases.translatedTotal(i));
        }
        field(line, "native_ns", NativeClock.nanos() - nativeStart);
        field(line, "native_compile_ns", NativeClock.compileNanos() - nativeCompileStart);
        field(line, "gc_ms", gcTime() - gcTimeStart);
        field(line, "gc_n", gcCount() - gcCountStart);
        System.err.println(processCpu(line));
    }

    /** Prints the process's CPU so far as a line {@code phases-start {...}}: the server has started. */
    static void printStart() {
        System.err.println(processCpu(new StringBuilder(1024).append("phases-start {")));
    }

    /** The GC's total, the process's CPU and its threads', ending the line's object. */
    private static StringBuilder processCpu(StringBuilder line) {
        field(line, "gc_ms_total", gcTime());
        field(line, "process_cpu_ms", processCpuMillis());
        line.append("\"threads_ms\":{");
        boolean first = true;
        for (Map.Entry<String, Long> group : threadCpuMillis().entrySet()) {
            if (!first) {
                line.append(',');
            }
            first = false;
            quote(line, group.getKey()).append(':').append(group.getValue());
        }
        return line.append("}}");
    }

    private static void field(StringBuilder line, String name, long value) {
        quote(line, name).append(':').append(value).append(',');
    }

    private static void field(StringBuilder line, String name, String value) {
        quote(line, name).append(':');
        quote(line, value).append(',');
    }

    private static StringBuilder quote(StringBuilder line, String text) {
        line.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                line.append('\\').append(c);
            } else if (c < 0x20) {
                line.append(String.format("\\u%04x", (int) c));
            } else {
                line.append(c);
            }
        }
        return line.append('"');
    }

    private static long gcTime() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, gc.getCollectionTime());
        }
        return total;
    }

    private static long gcCount() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            total += Math.max(0, gc.getCollectionCount());
        }
        return total;
    }

    /** The process's user and system CPU so far (/proc/self/stat), dead threads included; -1 without /proc. */
    private static long processCpuMillis() {
        try {
            long[] ticks = ticks(Files.readString(Path.of("/proc/self/stat")));
            return ticks == null ? -1 : (ticks[0] + ticks[1]) * 1000 / TICKS;
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    /** The live threads' CPU so far, by name with the digits dropped. */
    private static Map<String, Long> threadCpuMillis() {
        Map<String, Long> groups = new TreeMap<>();
        Path all = Path.of("/proc/self/task");
        if (!Files.isDirectory(all)) {
            return groups;
        }
        try (DirectoryStream<Path> tasks = Files.newDirectoryStream(all)) {
            for (Path task : tasks) {
                String stat;
                try {
                    stat = Files.readString(task.resolve("stat"));
                } catch (IOException e) {
                    // the thread ended
                    continue;
                }
                String name = stat.substring(stat.indexOf('(') + 1, stat.lastIndexOf(')')).replaceAll("[0-9]", "");
                long[] ticks = ticks(stat);
                if (ticks != null) {
                    groups.merge(name, (ticks[0] + ticks[1]) * 1000 / TICKS, Long::sum);
                }
            }
        } catch (IOException e) {
            groups.clear();
        }
        return groups;
    }

    /** utime and stime of a /proc stat line: the 14th and 15th fields, the 12th and 13th after the name. */
    private static long[] ticks(String stat) {
        String[] fields = stat.substring(stat.lastIndexOf(')') + 2).trim().split(" ");
        if (fields.length < 13) {
            return null;
        }
        return new long[] {Long.parseLong(fields[11]), Long.parseLong(fields[12])};
    }
}
