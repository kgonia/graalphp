package pl.codeloom.php.runtime;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.nodes.EncapsulatingNodeReference;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.PhpContext;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Stack traces as php-src's zend_fetch_debug_backtrace builds them: an entry per call, the innermost first, with
 * the file and line the call was made from (none when internal code made it: "[internal function]"), the
 * function, its class and call type, and its arguments. A call php-src compiles without a frame (strlen, count,
 * call_user_func...) has no entry, and what it calls seems called from its call site.
 */
public final class Backtrace {
    /** debug_backtrace()'s options. */
    public static final int PROVIDE_OBJECT = 1;
    public static final int IGNORE_ARGS = 2;
    /** ini precision's default, which a float argument is shown with. */
    private static final int PRECISION = 14;

    static final TruffleString FILE = Str.of("file");
    static final TruffleString LINE = Str.of("line");
    static final TruffleString FUNCTION = Str.of("function");
    static final TruffleString CLASS = Str.of("class");
    static final TruffleString OBJECT = Str.of("object");
    static final TruffleString TYPE = Str.of("type");
    static final TruffleString ARGS = Str.of("args");
    private static final TruffleString ARROW = Str.of("->");
    private static final TruffleString DOUBLE_COLON = Str.of("::");

    private Backtrace() {
    }

    /** A root whose calls a trace lists: a function or method, a built-in, a generator's body, an included file. */
    public interface Callee {
        /**
         * Adds the entry's keys after file and line, in php-src's order: function, class, object, type, args (an
         * include's: args, function). frame is the call's frame, site the node that made the call (null if none
         * did). False leaves the call out of the trace.
         */
        boolean describe(Frame frame, PhpArray entry, int options, CallSite site);
    }

    /** A node that may make a call, as traces see it: every expression. */
    public interface CallSite {
        /** php-src compiles the call without a frame: the callee isn't listed, and its calls seem made from here. */
        default boolean isFrameless() {
            return false;
        }

        /** include, require, include_once or require_once for an include; null for other calls. */
        default String includeKind() {
            return null;
        }

        /**
         * A built-in compiled in place that calls back (array_map): its name, for the entry php-src's frame of it
         * makes; null for other calls. What it calls is called from internal code.
         */
        default String inlineBuiltin() {
            return null;
        }

        /** The arguments of such a built-in's call, from the frame it runs in. */
        default Object[] inlineArguments(Frame frame) {
            return new Object[0];
        }
    }

    /** A frame: its root, the frame itself, and the node in it that made the call above. */
    private record Level(RootNode root, Frame frame, Node call) {
    }

    /** The trace of the code running now, leaving out the innermost skip frames (debug_backtrace's own). */
    @TruffleBoundary
    public static PhpArray here(int options, int limit, int skip) {
        List<Level> levels = new ArrayList<>();
        Truffle.getRuntime().iterateFrames(frame -> {
            levels.add(new Level(((RootCallTarget) frame.getCallTarget()).getRootNode(),
                    frame.getFrame(FrameInstance.FrameAccess.READ_ONLY), frame.getCallNode()));
            return null;
        });
        return build(levels.subList(Math.min(skip, levels.size()), levels.size()), options, limit);
    }

    /** The trace a Throwable made now records (zend_default_exception_new's). */
    @TruffleBoundary
    public static PhpArray forThrowable() {
        return here(exceptionOptions(), 0, 0);
    }

    private static PhpArray build(List<Level> levels, int options, int limit) {
        PhpArray trace = new PhpArray();
        for (int i = 0; i < levels.size() && (limit <= 0 || trace.size() < limit); i++) {
            if (!(levels.get(i).root() instanceof Callee callee) || isFrameless(levels, i)) {
                continue;
            }
            int caller = i + 1;
            if (isFrameless(levels, caller)) {
                caller++;
            }
            Node call = caller < levels.size() ? levels.get(caller).call() : null;
            CallSite site = callSite(call);
            String inline = site == null ? null : site.inlineBuiltin();
            PhpArray entry = new PhpArray();
            if (call != null && inline == null && SourceLocation.isUserCode(levels.get(caller).root())) {
                locate(entry, SourceLocation.of(call));
            }
            if (callee.describe(levels.get(i).frame(), entry, options, site)) {
                trace.append(entry);
            }
            if (inline != null) {
                Frame frame = levels.get(caller).frame();
                PhpArray builtin = new PhpArray();
                locate(builtin, SourceLocation.of((Node) site));
                describeCall(builtin, inline, null, null, options, arguments(site.inlineArguments(frame)));
                trace.append(builtin);
            }
        }
        return trace;
    }

    private static void locate(PhpArray entry, SourceLocation at) {
        if (at != SourceLocation.UNKNOWN) {
            entry.put(FILE, Str.of(at.file()));
            entry.put(LINE, (long) at.line());
        }
    }

    private static PhpArray arguments(Object[] values) {
        PhpArray args = new PhpArray();
        for (Object value : values) {
            args.append(PhpRef.deref(value));
        }
        return args;
    }

    /** Whether the frame at index is a call php-src makes without a frame. */
    private static boolean isFrameless(List<Level> levels, int index) {
        if (index + 1 >= levels.size()) {
            return false;
        }
        CallSite site = callSite(levels.get(index + 1).call());
        return site != null && site.isFrameless();
    }

    /** The expression a call node belongs to. */
    private static CallSite callSite(Node call) {
        for (Node node = call; node != null; node = node.getParent()) {
            if (node instanceof CallSite site) {
                return site;
            }
        }
        return null;
    }

    /** Adds function, class, object (with PROVIDE_OBJECT), type and args (unless IGNORE_ARGS) to an entry. */
    public static void describeCall(PhpArray entry, String function, String className, PhpObject self, int options,
                                    PhpArray args) {
        entry.put(FUNCTION, Str.of(function));
        if (className != null) {
            entry.put(CLASS, Str.of(className));
            if (self != null && (options & PROVIDE_OBJECT) != 0) {
                entry.put(OBJECT, self);
            }
            entry.put(TYPE, self != null ? ARROW : DOUBLE_COLON);
        }
        if ((options & IGNORE_ARGS) == 0) {
            entry.put(ARGS, args);
        }
    }

    /**
     * Runs a call that a node makes through runtime code (an Iterator's method in foreach, a magic method) with the
     * node as the call's location, so traces show its line as php-src shows the opline's.
     */
    @TruffleBoundary
    public static Object callFrom(Node site, Supplier<Object> call) {
        EncapsulatingNodeReference encapsulating = EncapsulatingNodeReference.getCurrent();
        Node previous = encapsulating.set(site);
        try {
            return call.get();
        } finally {
            encapsulating.set(previous);
        }
    }

    /** A built-in call's entry, for a call made without a Truffle frame: at the call site, with the values. */
    @TruffleBoundary
    public static PhpArray entry(SourceLocation at, String function, Object[] values) {
        PhpArray entry = new PhpArray();
        locate(entry, at);
        describeCall(entry, function, null, null, exceptionOptions(), arguments(values));
        return entry;
    }

    /** An include's entry: the included file as its argument (unless IGNORE_ARGS), then include, require... */
    public static void describeInclude(PhpArray entry, String kind, String path, int options) {
        if ((options & IGNORE_ARGS) == 0) {
            entry.put(ARGS, PhpArray.list(Str.of(path)));
        }
        entry.put(FUNCTION, Str.of(kind));
    }

    /** The options a Throwable's trace is taken with: its arguments unless zend.exception_ignore_args. */
    public static int exceptionOptions() {
        return PhpContext.get(null).errors().ini().bool("zend.exception_ignore_args") ? IGNORE_ARGS : 0;
    }

    /** zend_trace_to_string: "#0 file(line): Class->function(args)" per entry, then "#n {main}" if asked. */
    @TruffleBoundary
    public static TruffleString toString(PhpArray trace, boolean includeMain) {
        int maxLength = (int) PhpContext.get(null).errors().ini().lng("zend.exception_string_param_max_len");
        ByteBuilder out = new ByteBuilder();
        long number = 0;
        for (int p = trace.firstPosition(); p >= 0; p = trace.nextPosition(p)) {
            if (!(PhpRef.deref(trace.valueAt(p)) instanceof PhpArray entry)) {
                continue;
            }
            out.write('#');
            out.writeLong(number++);
            out.write(' ');
            Object file = entry.get(FILE);
            if (file == null) {
                ascii(out, "[internal function]: ");
            } else if (!(file instanceof TruffleString name)) {
                ascii(out, "[unknown file]: ");
            } else {
                out.write(name);
                out.write('(');
                out.writeLong(entry.get(LINE) instanceof Long line ? line : 0);
                ascii(out, "): ");
            }
            appendKey(out, entry, CLASS);
            appendKey(out, entry, TYPE);
            appendKey(out, entry, FUNCTION);
            out.write('(');
            if (entry.get(ARGS) instanceof PhpArray args && args.size() > 0) {
                for (int a = args.firstPosition(); a >= 0; a = args.nextPosition(a)) {
                    if (args.keyAt(a) instanceof TruffleString name) {
                        out.write(name);
                        ascii(out, ": ");
                    }
                    appendArgument(out, PhpRef.deref(args.valueAt(a)), maxLength);
                }
                out.truncate(out.size() - 2);
            }
            ascii(out, ")\n");
        }
        if (includeMain) {
            out.write('#');
            out.writeLong(number);
            ascii(out, " {main}");
        }
        return Str.of(out.toByteArray());
    }

    private static void appendKey(ByteBuilder out, PhpArray entry, TruffleString key) {
        Object value = entry.get(key);
        if (value instanceof TruffleString text) {
            out.write(text);
        } else if (value != null) {
            ascii(out, "[unknown]");
        }
    }

    /** _build_trace_args: a scalar as smart_str_append_scalar shows it, a string truncated; then ", ". */
    private static void appendArgument(ByteBuilder out, Object value, int maxLength) {
        if (value == PhpNull.NULL || value == null) {
            ascii(out, "NULL");
        } else if (value instanceof Boolean b) {
            ascii(out, b ? "true" : "false");
        } else if (value instanceof Long l) {
            out.writeLong(l);
        } else if (value instanceof Double d) {
            String text = Convert.gcvt(d, PRECISION, 'E');
            ascii(out, text);
            if (Double.isFinite(d) && text.indexOf('.') < 0) {
                ascii(out, ".0");
            }
        } else if (value instanceof TruffleString s) {
            byte[] bytes = Str.bytes(s);
            out.write('\'');
            appendEscaped(out, bytes, Math.min(bytes.length, maxLength));
            if (bytes.length > maxLength) {
                ascii(out, "...");
            }
            out.write('\'');
        } else if (value instanceof PhpArray) {
            ascii(out, "Array");
        } else if (value instanceof PhpClosure) {
            ascii(out, "Object(Closure)");
        } else if (value instanceof PhpObject object) {
            ascii(out, "Object(");
            out.write(Str.of(object.getPhpClass().getName()));
            out.write(')');
        } else if (value instanceof PhpResource resource) {
            ascii(out, "Resource id #");
            out.writeLong(resource.getId());
        }
        ascii(out, ", ");
    }

    /** smart_str_append_escaped: control bytes, backslash and bytes above 126 as C escapes. */
    private static void appendEscaped(ByteBuilder out, byte[] bytes, int length) {
        for (int i = 0; i < length; i++) {
            int c = bytes[i] & 0xFF;
            if (c >= 32 && c != '\\' && c <= 126) {
                out.write(c);
                continue;
            }
            out.write('\\');
            switch (c) {
                case '\n' -> out.write('n');
                case '\r' -> out.write('r');
                case '\t' -> out.write('t');
                case '\f' -> out.write('f');
                case 0x0B -> out.write('v');
                case '\\' -> out.write('\\');
                case 0x1B -> out.write('e');
                default -> {
                    out.write('x');
                    out.write(Character.toUpperCase(Character.forDigit(c >> 4, 16)));
                    out.write(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
                }
            }
        }
    }

    private static void ascii(ByteBuilder out, String text) {
        out.write(text.getBytes(StandardCharsets.ISO_8859_1));
    }
}
