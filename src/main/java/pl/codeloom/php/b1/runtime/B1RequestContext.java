package pl.codeloom.php.b1.runtime;

import java.io.OutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** All mutable execution and linkage state belongs to this request. */
public final class B1RequestContext {
    private final OutputStream output;
    private final Map<String, B1Callable> functions = new HashMap<>();
    private final Map<String, B1Callable> files = new HashMap<>();
    private final Map<String, ClassDef> classes = new HashMap<>();
    private final Map<String, Map<String, ClassDef>> statementClasses = new HashMap<>();
    private final Set<String> internalClasses;
    private final LinkedHashMap<String, B1Ref> globals = new LinkedHashMap<>();
    private boolean abortDestructors;
    boolean destructorsAborted() { return abortDestructors; }
    private final LinkedHashSet<B1Object> destructibles = new LinkedHashSet<>();
    private B1Frame frame;
    private String exceptionHandler;

    public record ClassDef(String name, List<String> props, Map<String, B1Callable> methods) {
        public ClassDef {
            props = List.copyOf(props);
            methods = Map.copyOf(methods);
        }
        @HotPath(lookup = true)
        static B1Callable method(B1RequestContext ctx, String cls, String lower) { return findClass(ctx, cls).methods.get(lower); }
    }

    public B1RequestContext(OutputStream output) {
        this.output = output;
        var methods = new HashMap<String, B1Callable>();
        for (String method : List.of("__construct", "getMessage", "getLine", "getPrevious", "getFile", "getCode", "getTrace", "getTraceAsString")) {
            methods.put(pl.codeloom.php.ir.AsciiCase.lower(method), new ExceptionMethod(method));
        }
        for (String type : List.of("Exception", "Error", "TypeError", "ArgumentCountError")) {
            classes.put(pl.codeloom.php.ir.AsciiCase.lower(type), new ClassDef(type,
                    List.of("message", "file", "line", "code", "trace", "previous"), methods));
        }
        classes.put("stdclass", new ClassDef("stdClass", List.of(), Map.of()));
        internalClasses = Set.copyOf(classes.keySet());
        functions.put("set_exception_handler", new B1Callable(new boolean[1], new String[]{"callback"}) {
            @Override public Object invoke(B1RequestContext ctx, B1Object self, Object[] args) {
                Object previous = ctx.exceptionHandler;
                ctx.exceptionHandler = (String) args[0];
                return previous;
            }
        });
    }

    private static final class ExceptionMethod extends B1Callable {
        private final String name;
        ExceptionMethod(String name) { super(new boolean[0], new String[0]); this.name = name; }
        @Override public Object invoke(B1RequestContext ctx, B1Object self, Object[] args) {
            String owner = self.type.equals("exception") ? "Exception" : "Error";
            if (name.equals("__construct")) {
                if (args.length > 3) throw ctx.error("ArgumentCountError", owner
                        + "::__construct() expects at most 3 arguments, " + args.length + " given");
                if (args.length > 2 && args[2] != null
                        && !(args[2] instanceof B1Object obj && B1Thrown.throwable(obj.type))) {
                    throw ctx.error("TypeError", owner + "::__construct(): Argument #3 ($previous) must be of type ?Throwable, "
                            + ctx.typeName(args[2]) + " given");
                }
                self.write(ctx, "message", args.length == 0 ? "" : ctx.string(args[0]));
                if (args.length > 1) self.write(ctx, "code", args[1]);
                if (args.length > 2) self.write(ctx, "previous", args[2]);
                return null;
            }
            if (args.length != 0) throw ctx.error("ArgumentCountError", owner + "::" + name
                    + "() expects exactly 0 arguments, " + args.length + " given");
            if (name.equals("getTraceAsString")) return B1Exception.traceString(ctx, (Map<?, ?>) self.read("trace"));
            return B1Object.retain(self.read(pl.codeloom.php.ir.AsciiCase.lower(name.substring(3))));
        }
    }

    public boolean isInternalClass(String lowerName) { return internalClasses.contains(lowerName); }

    public void declareClass(String path, String lowerName) {
        ClassDef cls = statementClasses.get(path).get(lowerName);
        if (classes.putIfAbsent(lowerName, cls) != null)
            throw error("Error", "Cannot redeclare class " + cls.name());
    }

    /** Publish a fully linked unit to this request. PHP symbol keys have already been ASCII-folded. */
    public void install(String path, B1Callable entry, Map<String, B1Callable> declarations,
                        Map<String, ClassDef> types, Map<String, ClassDef> lateTypes) {
        for (String key : types.keySet()) {
            if (classes.containsKey(key)) throw new IllegalStateException("class redeclared: " + key);
        }
        for (String key : declarations.keySet()) {
            if (functions.containsKey(key)) throw new IllegalStateException("function redeclared: " + key);
        }
        statementClasses.put(path, Map.copyOf(lateTypes));
        classes.putAll(types);
        functions.putAll(declarations);
        files.put(path, entry);
    }
    public B1Callable file(String path) { return files.get(path); }
    @HotPath(lookup = true)
    public static B1Callable function(B1RequestContext ctx, String lower) {
        var fn = ctx.functions.get(lower);
        if (fn == null) throw new IllegalStateException("undefined function: " + lower);
        return fn;
    }
    @HotPath(lookup = true)
    private static ClassDef findClass(B1RequestContext ctx, String lower) { return ctx.classes.get(lower); }
    B1Callable method(String lowerClass, String lowerMethod) { return ClassDef.method(this, lowerClass, lowerMethod); }
    public B1Callable resolve(B1Object recv, String lowerName, String name) {
        var target = method(recv.type, lowerName);
        if (target == null) throw error("Error", "Call to undefined method " + typeName(recv) + "::" + name + "()");
        return target;
    }
    public B1Callable constructor(B1Object recv) { return method(recv.type, "__construct"); }

    public B1Object allocate(String lower, String name) {
        var cls = findClass(this, lower);
        if (cls == null) throw error("Error", "Class \"" + name + "\" not found");
        var obj = new B1Object(lower, cls.props);
        if (cls.methods.containsKey("__destruct")) destructibles.add(obj);
        if (B1Thrown.throwable(lower)) {
            obj.write(this, "line", frame == null ? 0L : (long) frame.line);
            obj.write(this, "file", frame == null ? "" : frame.file);
            obj.write(this, "code", 0L);
            obj.write(this, "trace", B1Exception.trace(frame));
        }
        return obj;
    }
    void forget(B1Object obj) { destructibles.remove(obj); }
    public int liveDestructibleCount() { return destructibles.size(); }

    public B1Frame enter(B1Callable fn, String file, String name, int temps, boolean top) {
        if (top) for (String var : fn.locals) globals.putIfAbsent(var, null);
        frame = new B1Frame(frame, file, name, fn.locals, temps, top);
        return frame;
    }
    void pop(B1Frame f) {
        if (frame != f) throw new IllegalStateException("unbalanced PHP frame");
        frame = f.caller;
    }
    B1Ref global(String name) { return globals.computeIfAbsent(name, unused -> new B1Ref()); }
    public B1Ref findGlobal(String name) { return globals.get(name); }

    public void echo(Object value) {
        try { output.write(string(value).getBytes(StandardCharsets.UTF_8)); }
        catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
    B1Thrown error(String type, String message) {
        var obj = allocate(pl.codeloom.php.ir.AsciiCase.lower(type), type);
        obj.constructing = false;
        obj.write(this, "message", message);
        var thrown = new B1Thrown(obj);
        B1Object.release(this, obj);
        return thrown;
    }

    String typeName(Object value) {
        if (value instanceof B1Object obj) return findClass(this, obj.type).name();
        if (value instanceof Map) return "array";
        if (value instanceof Long) return "int";
        if (value instanceof Double) return "float";
        if (value instanceof String) return "string";
        return value == null ? "null" : "bool";
    }

    void diagnostic(String level, String message) {
        echo("\n" + level + ": " + message + " in " + frame.file + " on line " + frame.line + "\n");
    }

    public static Object readProperty(Object receiver, B1RequestContext ctx, String name) {
        if (receiver instanceof B1Object obj) {
            if (obj.has(name)) return obj.read(name);
            ctx.diagnostic("Warning", "Undefined property: " + ctx.typeName(obj) + "::$" + name);
        } else ctx.diagnostic("Warning", "Attempt to read property \"" + name + "\" on " + ctx.typeName(receiver));
        return null;
    }

    public String string(Object value) {
        if (value instanceof B1Object obj) {
            var convert = method(obj.type, "__tostring");
            if (convert == null) throw error("Error", "Object of class " + typeName(obj) + " could not be converted to string");
            Object result = convert.invoke0(this, obj);
            try {
                if (result instanceof String text) return text;
                if (result instanceof Number || result instanceof Boolean) return string(result);
                throw error("TypeError", typeName(obj) + "::__toString(): Return value must be of type string, "
                        + typeName(result) + " returned");
            } finally { B1Object.release(this, result); }
        }
        if (value instanceof Map) { diagnostic("Warning", "Array to string conversion"); return "Array"; }
        if (value instanceof Boolean flag) return flag ? "1" : "";
        if (value instanceof Double d) return pl.codeloom.php.ir.PhpFloatFormat.formatDouble(d, 14);
        return value == null ? "" : value.toString();
    }

    public Object concat(Object left, Object right) { return string(left) + string(right); }

    private Number number(Object value, boolean leading) {
        if (value instanceof Number n) return n;
        if (value instanceof Boolean flag) return flag ? 1L : 0L;
        if (value == null) return leading ? 0L : null;
        if (!(value instanceof String str)) return null;
        byte[] bytes = str.getBytes(StandardCharsets.UTF_8);
        Number number = pl.codeloom.php.ir.PhpNumericString.parse(bytes, false);
        if (number == null && leading) {
            number = pl.codeloom.php.ir.PhpNumericString.parse(bytes, true);
            if (number != null) diagnostic("Warning", "A non-numeric value encountered");
        }
        return number;
    }

    Long integer(Object value) {
        Number n = number(value, false);
        if (n instanceof Long l) return l;
        if (n == null || !(n.doubleValue() >= -0x1p63 && n.doubleValue() < 0x1p63)) return null;
        long l = n.longValue();
        if ((double)l != n.doubleValue()) diagnostic("Deprecated", "Implicit conversion from "
                + (value instanceof String ? "float-string \"" + value + "\"" : "float " + value)
                + " to int loses precision");
        return l;
    }

    public Object add(Object left, Object right) {
        Number a = number(left, true), b = number(right, true);
        if (a == null || b == null) throw error("TypeError", "Unsupported operand types: " + typeName(left) + " + " + typeName(right));
        if (a instanceof Long && b instanceof Long) {
            try { return Math.addExact(a.longValue(), b.longValue()); } catch (ArithmeticException overflow) { }
        }
        return a.doubleValue() + b.doubleValue();
    }

    private void handleUncaught(B1Thrown thrown) {
        String installed = exceptionHandler;
        if (installed == null) throw thrown;
        exceptionHandler = null;
        try {
            var handler = function(this, pl.codeloom.php.ir.AsciiCase.lower(installed));
            B1Object.release(this, handler.invoke1(this, null, thrown.exception));
            if (exceptionHandler == null) exceptionHandler = installed;
        } finally { B1Object.release(this, thrown.exception); }
    }

    public void runScript(B1Callable entry) {
        try { B1Object.release(this, entry.invoke0(this, null)); }
        catch (B1Thrown thrown) { handleUncaught(thrown); }
        finally { shutdown(); }
    }

    private void shutdownStep(Runnable release) {
        try { release.run(); }
        catch (B1Thrown thrown) { handleUncaught(thrown); }
    }

    public void shutdown() {
        Throwable pending = null;
        try {
            pl.codeloom.php.ir.D17Shutdown.destruct(globals,
                    slot -> !slot.reference() && slot.get() instanceof B1Object obj && obj.uniquelyHeld(),
                    key -> shutdownStep(() -> globals.remove(key).unbind(this)), destructibles, obj -> shutdownStep(() -> obj.destruct(this)));
        } catch (Throwable ex) { abortDestructors = true; pending = ex; }
        while (!globals.isEmpty()) {
            var slot = globals.pollLastEntry().getValue();
            if (slot == null) continue;
            try { slot.unbind(this); } catch (Throwable ex) { pending = B1Thrown.chain(this, ex, pending); }
        }
        while (!destructibles.isEmpty()) {
            try { destructibles.getFirst().destruct(this); } catch (Throwable ex) { pending = B1Thrown.chain(this, ex, pending); }
        }
        if (frame != null || liveDestructibleCount() != 0) throw new IllegalStateException("incomplete PHP shutdown");
        B1Thrown.propagate(pending);
    }
}
