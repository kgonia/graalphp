import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the PHP thread's time is by the machine code that was running, from a JFR recording's execution samples
 * (wiki/ceiling.md): a sample's program counter names its compiled method exactly, whatever the debug information
 * says of the frames inlined into it, so the split has none of a safepoint sampler's bias.
 *
 * <p>A sample's frames, top first, fall into units: the frames inlined into a compiled method and that method. The
 * top unit says what ran:
 * <ul>
 * <li>compiled: a Truffle compilation (its method is OptimizedCallTarget.profiledPERoot): a PHP function's
 * operations and everything partial evaluation inlined into them, a built-in's node inlined there, or a built-in's
 * own compilation (which of the three is the debug information's word);</li>
 * <li>interpreter: the bytecode interpreter's own methods, a function not compiled yet (tier 0);</li>
 * <li>runtime: any other method, so engine code a compilation calls rather than inlines (behind a
 * TruffleBoundary, or a call target's entry): counted for the method the PHP code called (the unit above the
 * nearest PHP unit, "entry") and for the method on top ("top"), each split by whether compiled PHP or the
 * interpreter called it.</li>
 * </ul>
 * Garbage collection pauses and time in native code are not execution samples: the collections' pauses and the
 * native samples of the window are printed beside.
 *
 * <p>A sample under a built-in function (BuiltinRootNode.call below it, or its node in the compiled unit) is also
 * counted for that function: the frame of the built-ins' package nearest to the call, and with SRC (the engine's
 * src/main/java) the name the frame's line registers ({@code r.fn("name", ...)} at or above it).
 *
 * <p>Usage: java JfrSplit.java REC.jfr [FROM_SECONDS [ROWS [THREAD [SRC]]]]   samples from FROM_SECONDS after the
 * recording's first sample (default 0), ROWS methods per table (default 40), of THREAD (default main).
 */
public final class JfrSplit {
    private static final String PE_ROOT = "com.oracle.truffle.runtime.OptimizedCallTarget.profiledPERoot";
    private static final String BUILTIN = "pl.codeloom.php.nodes.call.BuiltinRootNode";
    private static final String BYTECODE = "pl.codeloom.php.bytecode.PhpBytecodeRootNodeGen";
    private static final String BUILTINS = "pl.codeloom.php.builtins.";
    private static final Pattern REGISTERED = Pattern.compile("\\.fn\\(\\s*\"([^\"]+)\"");
    private static final Map<String, List<String>> SOURCES = new HashMap<>();

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        long from = args.length > 1 ? Long.parseLong(args[1]) : 0;
        int rows = args.length > 2 ? Integer.parseInt(args[2]) : 40;
        String thread = args.length > 3 ? args[3] : "main";
        Path sources = args.length > 4 ? Path.of(args[4]) : null;
        List<RecordedEvent> samples = new ArrayList<>();
        List<RecordedEvent> natives = new ArrayList<>();
        List<RecordedEvent> collections = new ArrayList<>();
        try (RecordingFile recording = new RecordingFile(file)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                switch (event.getEventType().getName()) {
                    case "jdk.ExecutionSample" -> samples.add(event);
                    case "jdk.NativeMethodSample" -> natives.add(event);
                    case "jdk.GarbageCollection" -> collections.add(event);
                    default -> {
                    }
                }
            }
        }
        Instant first = samples.stream().map(RecordedEvent::getStartTime).min(Instant::compareTo).orElseThrow();
        Instant last = samples.stream().map(RecordedEvent::getStartTime).max(Instant::compareTo).orElseThrow();
        Instant start = first.plusSeconds(from);
        Map<String, Long> kinds = new HashMap<>();
        Map<String, Long> entries = new HashMap<>();
        Map<String, Long> tops = new HashMap<>();
        Map<String, Long> operations = new HashMap<>();
        Map<String, Long> inlinedTops = new HashMap<>();
        Map<String, Long> builtins = new HashMap<>();
        Map<String, Long> interpreted = new HashMap<>();
        long total = 0;
        for (RecordedEvent event : samples) {
            if (event.getStartTime().isBefore(start) || !thread.equals(threadName(event))) {
                continue;
            }
            RecordedStackTrace stack = event.getStackTrace();
            if (stack == null || stack.getFrames().isEmpty()) {
                continue;
            }
            total++;
            List<RecordedFrame> frames = stack.getFrames();
            // the units' root methods, top first, and each unit's first frame
            List<String> roots = new ArrayList<>();
            List<Integer> starts = new ArrayList<>();
            int unitStart = 0;
            for (int i = 0; i < frames.size(); i++) {
                if (!"Inlined".equals(frames.get(i).getType())) {
                    roots.add(name(frames.get(i)));
                    starts.add(unitStart);
                    unitStart = i + 1;
                }
            }
            if (roots.isEmpty()) {
                kinds.merge("unknown (truncated stack)", 1L, Long::sum);
                continue;
            }
            int php = -1;
            boolean compiled = false;
            for (int u = 0; u < roots.size() && php < 0; u++) {
                if (roots.get(u).equals(PE_ROOT)) {
                    php = u;
                    compiled = true;
                } else if (isInterpreter(roots.get(u))) {
                    php = u;
                }
            }
            if (php == 0 && compiled) {
                int end = frames.size();
                if (starts.size() > 1) {
                    end = starts.get(1);
                }
                String inner = rootNode(frames, 0, end, true);
                String outer = rootNode(frames, 0, end, false);
                String kind = BUILTIN.equals(outer) ? "compiled: a built-in's own compilation"
                        : BUILTIN.equals(inner) ? "compiled: a built-in inlined into a PHP function's compilation"
                        : "compiled: a PHP function's operations";
                kinds.merge(kind, 1L, Long::sum);
                operations.merge(operation(frames, 0, end), 1L, Long::sum);
                if (BUILTIN.equals(inner)) {
                    builtins.merge(builtin(frames, end - 1, sources), 1L, Long::sum);
                }
                inlinedTops.merge(name(frames.get(0)), 1L, Long::sum);
            } else if (php == 0) {
                kinds.merge("interpreter (tier 0)", 1L, Long::sum);
                interpreted.merge(operation(frames, 0, frames.size()), 1L, Long::sum);
            } else if (php < 0) {
                kinds.merge("runtime, no PHP code below (within the recorded depth)", 1L, Long::sum);
                tops.merge("?: " + roots.get(0), 1L, Long::sum);
            } else {
                String caller = compiled ? "compiled" : "interpreter";
                kinds.merge("runtime called by " + (compiled ? "compiled code" : "the interpreter"), 1L, Long::sum);
                entries.merge(caller + ": " + roots.get(php - 1), 1L, Long::sum);
                if (roots.get(php - 1).startsWith(BUILTIN)) {
                    builtins.merge(builtin(frames, starts.get(php) - 1, sources), 1L, Long::sum);
                }
                tops.merge(caller + ": " + roots.get(0), 1L, Long::sum);
                if (!compiled) {
                    interpreted.merge(operation(frames, 0, frames.size()), 1L, Long::sum);
                }
            }
        }
        long nativeSamples = natives.stream()
                .filter(e -> !e.getStartTime().isBefore(start) && thread.equals(threadName(e))).count();
        Duration pauses = Duration.ZERO;
        long collected = 0;
        for (RecordedEvent event : collections) {
            if (!event.getStartTime().isBefore(start)) {
                pauses = pauses.plus(event.getDuration("sumOfPauses"));
                collected++;
            }
        }
        double window = Duration.between(start, last).toMillis() / 1000.0;
        System.out.printf(Locale.ROOT, "%s: thread %s, %d execution samples in %.0f s (from second %d); %d native samples; "
                + "%d collections, pauses %.2f s (%.1f%% of the window)%n%n", file.getFileName(), thread, total,
                window, from, nativeSamples, collected, pauses.toMillis() / 1000.0,
                100.0 * pauses.toMillis() / 1000.0 / Math.max(window, 1e-9));
        table("what ran", kinds, total, rows);
        table("runtime: the method PHP code called (inclusive)", entries, total, rows);
        table("runtime: the method on top", tops, total, rows);
        table("built-in functions (inclusive, compiled or called)", builtins, total, rows);
        table("compiled PHP: the operation (the nearest handler frame; debug information)", operations, total, rows);
        table("interpreter and the runtime it called: the operation (the nearest handler frame)", interpreted, total,
                rows);
        table("compiled PHP: the top frame (debug information: exact only with -XX:+DebugNonSafepoints)",
                inlinedTops, total, rows);
    }

    private static String threadName(RecordedEvent event) {
        return event.getThread("sampledThread") == null ? null : event.getThread("sampledThread").getJavaName();
    }

    private static String name(RecordedFrame frame) {
        return frame.getMethod().getType().getName() + "." + frame.getMethod().getName();
    }

    /**
     * The root node whose code a unit's frames are in: the innermost one (the first execute frame of a root node
     * from the top: an inlined callee's) or the outermost (the compilation's own).
     */
    private static String rootNode(List<RecordedFrame> frames, int start, int end, boolean innermost) {
        String found = null;
        for (int i = start; i < end; i++) {
            String type = frames.get(i).getMethod().getType().getName();
            if (frames.get(i).getMethod().getName().equals("execute")
                    && (type.equals(BUILTIN) || type.equals(BYTECODE))) {
                found = type;
                if (innermost) {
                    break;
                }
            }
        }
        return found;
    }

    /**
     * The built-in function a sample is under: from frame `from` towards the top, the first frame of the built-ins'
     * package that is not the registry's wrapper; its registered name when the sources are given.
     */
    private static String builtin(List<RecordedFrame> frames, int from, Path sources) {
        for (int i = Math.min(from, frames.size() - 1); i >= 0; i--) {
            String type = frames.get(i).getMethod().getType().getName();
            if (!type.startsWith(BUILTINS) || type.startsWith(BUILTINS + "Registry") || type.contains("$$Lambda")) {
                continue;
            }
            String method = frames.get(i).getMethod().getName();
            int line = frames.get(i).getLineNumber();
            String simple = type.substring(BUILTINS.length());
            if (method.startsWith("lambda$register") && sources != null) {
                String registered = registeredAt(sources, type, line);
                if (registered != null) {
                    return registered;
                }
            }
            return simple + "." + method + (method.startsWith("lambda$") ? ":" + line : "");
        }
        // no frame names it (a method reference to another package, or debug information of the root only)
        String type = frames.get(0).getMethod().getType().getName();
        return "(unnamed; on top " + type.substring(type.lastIndexOf('.') + 1) + "."
                + frames.get(0).getMethod().getName() + ")";
    }

    /** The name of the nearest {@code .fn("name"} at or above a line of a built-ins class's source. */
    private static String registeredAt(Path sources, String type, int line) {
        String outer = type.contains("$") ? type.substring(0, type.indexOf('$')) : type;
        List<String> lines = SOURCES.computeIfAbsent(outer, name -> {
            try {
                return Files.readAllLines(sources.resolve(name.replace('.', '/') + ".java"));
            } catch (IOException e) {
                return List.of();
            }
        });
        for (int i = Math.min(line, lines.size()) - 1; i >= 0 && i >= line - 400; i--) {
            Matcher matcher = REGISTERED.matcher(lines.get(i));
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private static boolean isInterpreter(String root) {
        return root.startsWith("pl.codeloom.php.bytecode.PhpBytecodeRootNodeGen");
    }

    /** The bytecode operation running: the nearest handleX_ frame of the unit, or the unit's root node's class. */
    private static String operation(List<RecordedFrame> frames, int start, int end) {
        for (int i = start; i < end; i++) {
            String method = frames.get(i).getMethod().getName();
            String type = frames.get(i).getMethod().getType().getName();
            if (method.startsWith("handle") && type.endsWith("CachedBytecodeNode")) {
                return method;
            }
            if (method.equals("execute") && type.equals(BUILTIN)) {
                return "(a built-in's node)";
            }
        }
        return "(none: the interpreter loop or a root's entry)";
    }

    private static void table(String title, Map<String, Long> counts, long total, int rows) {
        System.out.println("| " + title + " | samples | share |");
        System.out.println("|---|---:|---:|");
        counts.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue())).limit(rows)
                .forEach(e -> System.out.printf(Locale.ROOT, "| %s | %d | %.1f%% |%n", e.getKey(), e.getValue(),
                        100.0 * e.getValue() / Math.max(total, 1)));
        System.out.println();
    }
}
