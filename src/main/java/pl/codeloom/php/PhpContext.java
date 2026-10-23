package pl.codeloom.php;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.builtins.BuiltinState;
import pl.codeloom.php.builtins.Builtins;
import pl.codeloom.php.nodes.FileRootNode;
import pl.codeloom.php.parser.Ast;
import pl.codeloom.php.parser.AstReader;
import pl.codeloom.php.parser.Translator;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.Str;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One PHP execution: the declared functions, classes and constants, global variables, includes and output. */
public final class PhpContext {
    private static final ContextReference<PhpContext> CONTEXT = ContextReference.create(PhpLanguage.class);

    private final PhpLanguage language;
    private final TruffleLanguage.Env env;
    private final Path astRoot;
    private final Path sourceRoot;
    private final OutputStream out;

    /** The declared functions by lowercase name, in declaration order, as php-src's function table. */
    private final Map<String, PhpFunction> functions = new java.util.LinkedHashMap<>();
    /** The declared functions by their name's language-level id (null: not declared here). */
    private PhpFunction[] functionSlots = new PhpFunction[256];
    /** The declared classes by lowercase name, in declaration order, as php-src's class table. */
    private final Map<String, PhpClass> classes = new java.util.LinkedHashMap<>();
    /** spl_autoload_register's callables, in calling order; and the classes being autoloaded now. */
    private final List<Object> autoloaders = new java.util.ArrayList<>();
    private final Set<String> autoloading = new HashSet<>();
    /** The declared classes by their lowercase name's language-level id (null: not declared here). */
    private PhpClass[] classSlots = new PhpClass[256];
    /** The defined constants by name, in definition order (get_defined_constants). */
    private final Map<String, Object> constants = new java.util.LinkedHashMap<>();
    /** The defined constants by their name's language-level id (null: not defined here). */
    private Object[] constantSlots = new Object[256];
    private final Map<String, PhpRef> globals = new HashMap<>();
    private final Set<String> included = new HashSet<>();
    private final java.util.ArrayDeque<OutputBuffer> buffers = new java.util.ArrayDeque<>();
    private Object errorHandler = pl.codeloom.php.runtime.PhpNull.NULL;
    /** The next object id: ids start at 1 in each context, as in each php process. */
    private int nextObjectId = 1;
    private final BuiltinState builtinState = new BuiltinState();
    /** Static variables (`static $x`) by function, then name. */
    private final Map<PhpFunction, Map<String, PhpRef>> staticVariables = new HashMap<>();
    private final pl.codeloom.php.runtime.Callables.LastCall lastCall = new pl.codeloom.php.runtime.Callables.LastCall();

    /** The prelude files this context has run (see {@link Prelude}). */
    private final java.util.Set<String> preludeLoaded = new java.util.HashSet<>();
    /** The CGI SAPI's response, or null under the CLI SAPI. */
    private final pl.codeloom.php.builtins.CgiSapi.Response response;
    /** The request body (php://input): stdin under the CGI SAPI, empty under the CLI. */
    private final byte[] requestBody;

    public PhpContext(PhpLanguage language, TruffleLanguage.Env env, String astRoot, String sourceRoot, boolean cgi,
                      boolean debug) {
        this.language = language;
        this.env = env;
        this.astRoot = Path.of(astRoot).toAbsolutePath().normalize();
        this.sourceRoot = Path.of(sourceRoot).toAbsolutePath().normalize();
        this.out = new BufferedOutputStream(env.out(), 1 << 16);
        Builtins.install(this, language.builtins(debug));
        PhpArray argv = new PhpArray();
        for (String argument : env.getApplicationArguments()) {
            argv.append(Str.of(argument));
        }
        globalRef("argv").set(argv);
        globalRef("argc").set((long) argv.size());
        response = cgi ? new pl.codeloom.php.builtins.CgiSapi.Response() : null;
        requestBody = cgi ? readRequestBody(env) : new byte[0];
        superglobals(argv);
        if (cgi) {
            pl.codeloom.php.builtins.CgiSapi.installRequest(this, env.getEnvironment(), requestBody);
        }
    }

    private static byte[] readRequestBody(TruffleLanguage.Env env) {
        try {
            return env.in().readAllBytes();
        } catch (IOException e) {
            throw new PhpError("reading the request body failed: " + e.getMessage());
        }
    }

    public pl.codeloom.php.builtins.CgiSapi.Response response() {
        return response;
    }

    public byte[] requestBody() {
        return requestBody;
    }

    /** The superglobal names: global variables visible in every scope. */
    public static final java.util.Set<String> SUPERGLOBALS = java.util.Set.of("_SERVER", "_GET", "_POST", "_COOKIE",
            "_FILES", "_REQUEST", "_ENV", "_SESSION");

    /**
     * As the CLI SAPI fills them: $_SERVER has the environment, argv/argc and the request time; the rest empty. Under
     * the CGI SAPI the environment holds the request's variables (SCRIPT_NAME, ...), and argv/argc are left out.
     */
    private void superglobals(PhpArray argv) {
        PhpArray server = new PhpArray();
        for (Map.Entry<String, String> variable : new java.util.TreeMap<>(env.getEnvironment()).entrySet()) {
            server.put(pl.codeloom.php.runtime.ArrayKey.of(Str.of(variable.getKey())), Str.of(variable.getValue()));
        }
        long millis = System.currentTimeMillis();
        if (response == null) {
            String script = argv.size() > 0 ? Str.java((TruffleString) argv.get(0L)) : "";
            server.put(Str.of("PHP_SELF"), Str.of(script));
            server.put(Str.of("SCRIPT_NAME"), Str.of(script));
            server.put(Str.of("SCRIPT_FILENAME"), Str.of(script));
            server.put(Str.of("PATH_TRANSLATED"), Str.of(script));
            server.put(Str.of("DOCUMENT_ROOT"), Str.EMPTY);
        }
        server.put(Str.of("REQUEST_TIME_FLOAT"), millis / 1000.0);
        server.put(Str.of("REQUEST_TIME"), millis / 1000);
        if (response == null) {
            server.put(Str.of("argv"), argv);
            server.put(Str.of("argc"), (long) argv.size());
        }
        globalRef("_SERVER").set(server);
        for (String name : SUPERGLOBALS) {
            if (!name.equals("_SERVER") && !name.equals("_SESSION")) {
                globalRef(name).set(new PhpArray());
            }
        }
    }

    public static PhpContext get(Node node) {
        return CONTEXT.get(node);
    }

    /** get_resource_id()'s numbering: 1-3 are STDIN, STDOUT and STDERR in php-cli. */
    private int nextResourceId = 4;

    public int nextResourceId() {
        return nextResourceId++;
    }

    public int nextObjectId() {
        return nextObjectId++;
    }

    /** The callable that Callables.call resolved last in this context. */
    public pl.codeloom.php.runtime.Callables.LastCall lastCall() {
        return lastCall;
    }

    public BuiltinState builtinState() {
        return builtinState;
    }

    /** The reference of a function's `static $name` variable, or null before its first initialization. */
    @TruffleBoundary
    public PhpRef findStaticVariable(PhpFunction function, String name) {
        Map<String, PhpRef> variables = staticVariables.get(function);
        return variables == null ? null : variables.get(name);
    }

    @TruffleBoundary
    public PhpRef createStaticVariable(PhpFunction function, String name, Object initial) {
        PhpRef ref = new PhpRef(initial);
        ref.bind();
        staticVariables.computeIfAbsent(function, f -> new HashMap<>()).put(name, ref);
        return ref;
    }

    public PhpLanguage getLanguage() {
        return language;
    }

    public TruffleLanguage.Env getEnv() {
        return env;
    }

    // ---- files ----

    /**
     * The translated top-level code of a PHP file. Translations are language-level: the first context to load a
     * file reads its AST export and translates it, and every context shares the result until the export
     * changes (checked at each load, as opcache's timestamp validation).
     */
    @TruffleBoundary
    public FileRootNode loadFile(String path) {
        String canonical = canonicalPath(path);
        Path relative = sourceRoot.relativize(Path.of(canonical));
        Path json = astRoot.resolve(relative.toString() + ".json");
        TruffleFile file = env.getPublicTruffleFile(json.toString());
        long modified;
        long size;
        try {
            modified = file.getLastModifiedTime().toMillis();
            size = file.size();
        } catch (IOException | SecurityException e) {
            throw noExport(path, json);
        }
        return language.file(canonical, modified, size, () -> translate(path, canonical, file, json));
    }

    private FileRootNode translate(String path, String canonical, TruffleFile file, Path json) {
        Ast ast;
        try (InputStream in = file.newInputStream()) {
            ast = AstReader.read(in);
        } catch (IOException e) {
            throw noExport(path, json);
        }
        return new Translator(language, canonical, phpSource(canonical)).translateFile(ast);
    }

    private static PhpError noExport(String path, Path json) {
        return new PhpError("Failed opening '" + path + "' for inclusion (no AST export at " + json + ")");
    }

    private Source phpSource(String canonical) {
        try {
            return Source.newBuilder(PhpLanguage.ID, env.getPublicTruffleFile(canonical)).build();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @TruffleBoundary
    public String canonicalPath(String path) {
        return Path.of(path).toAbsolutePath().normalize().toString();
    }

    /** Marks a file as included; false if it already was (include_once / require_once). */
    @TruffleBoundary
    public boolean markIncluded(String canonical) {
        return included.add(canonical);
    }

    // ---- declarations ----

    @TruffleBoundary
    public PhpFunction findFunction(String lowerName) {
        return functions.get(lowerName);
    }

    @TruffleBoundary
    public void declareFunction(PhpFunction function) {
        String key = Str.lower(function.getName());
        if (functions.putIfAbsent(key, function) != null) {
            throw new PhpError("Cannot redeclare function " + function.getName() + "()");
        }
        int id = language.functionId(key);
        if (id >= functionSlots.length) {
            functionSlots = Arrays.copyOf(functionSlots, grown(functionSlots.length, id));
        }
        functionSlots[id] = function;
    }

    private static int grown(int length, int id) {
        return Math.max(id + 1, length * 2);
    }

    /** The function declared under a name id (PhpLanguage.functionId), or null. */
    public PhpFunction functionAt(int id) {
        PhpFunction[] slots = functionSlots;
        return id < slots.length ? slots[id] : null;
    }

    @TruffleBoundary
    public PhpClass findClass(String lowerName) {
        PhpClass phpClass = classes.get(lowerName);
        if (phpClass == null && loadPrelude(lowerName)) {
            phpClass = classes.get(lowerName);
        }
        return phpClass;
    }

    /** Runs the prelude file declaring a built-in class written in PHP, once; false if there's none to run. */
    private boolean loadPrelude(String lowerName) {
        String file = language.preludeFile(lowerName);
        if (file == null || !preludeLoaded.add(file)) {
            return false;
        }
        language.preludeRoot(file).getCallTarget().call(new Object[PhpFunction.ARG_FIRST]);
        return true;
    }

    /** The class, running the autoloaders if it isn't declared yet; throws if it still isn't. */
    @TruffleBoundary
    public PhpClass getClass(String name) {
        PhpClass phpClass = findClassAutoloading(name);
        if (phpClass == null) {
            throw new PhpError("Class \"" + stripSlash(name) + "\" not found");
        }
        return phpClass;
    }

    /** The class, running the autoloaders (spl_autoload_register) if it isn't declared yet; or null. */
    @TruffleBoundary
    public PhpClass findClassAutoloading(String name) {
        String className = stripSlash(name);
        String lowerName = Str.lower(className);
        PhpClass phpClass = findClass(lowerName);
        if (phpClass != null || autoloaders.isEmpty() || !autoloading.add(lowerName)) {
            return phpClass;
        }
        try {
            for (Object autoloader : List.copyOf(autoloaders)) {
                pl.codeloom.php.runtime.Callables.call(this, autoloader, Str.of(className));
                phpClass = classes.get(lowerName);
                if (phpClass != null) {
                    return phpClass;
                }
            }
            return null;
        } finally {
            autoloading.remove(lowerName);
        }
    }

    private static String stripSlash(String name) {
        return name.startsWith("\\") ? name.substring(1) : name;
    }

    /** spl_autoload_register(): false when the callable is registered already. */
    @TruffleBoundary
    public boolean addAutoloader(Object callable, boolean prepend) {
        if (autoloaders.contains(callable)) {
            return false;
        }
        if (prepend) {
            autoloaders.add(0, callable);
        } else {
            autoloaders.add(callable);
        }
        return true;
    }

    @TruffleBoundary
    public boolean removeAutoloader(Object callable) {
        return autoloaders.remove(callable);
    }

    @TruffleBoundary
    public List<Object> autoloaders() {
        return List.copyOf(autoloaders);
    }

    @TruffleBoundary
    public void declareClass(PhpClass phpClass) {
        if (classes.putIfAbsent(phpClass.getLowerName(), phpClass) != null) {
            throw new PhpError("Cannot declare class " + phpClass.getName() + ", because the name is already in use");
        }
        int id = language.classId(phpClass.getLowerName());
        if (id >= classSlots.length) {
            classSlots = Arrays.copyOf(classSlots, grown(classSlots.length, id));
        }
        classSlots[id] = phpClass;
    }

    /** The class declared under a name id (PhpLanguage.classId), or null. */
    public PhpClass classAt(int id) {
        PhpClass[] slots = classSlots;
        return id < slots.length ? slots[id] : null;
    }

    /** A global constant's value, or null if undefined. true, false and null are folded by the translator. */
    @TruffleBoundary
    public Object findConstant(String name) {
        return constants.get(name);
    }

    @TruffleBoundary
    public boolean defineConstant(String name, Object value) {
        if (constants.putIfAbsent(name, value) != null) {
            return false;
        }
        int id = language.constantId(name);
        if (id >= constantSlots.length) {
            constantSlots = Arrays.copyOf(constantSlots, grown(constantSlots.length, id));
        }
        constantSlots[id] = value;
        return true;
    }

    /** The constant defined under a name id (PhpLanguage.constantId), or null. */
    public Object constantAt(int id) {
        Object[] slots = constantSlots;
        return id < slots.length ? slots[id] : null;
    }

    // ---- global variables ----

    private int globalsVersion;

    /** The global variable's reference, created (holding null) if absent: `global $x`, top-level code. */
    @TruffleBoundary
    public PhpRef globalRef(String name) {
        return globals.computeIfAbsent(name, n -> {
            PhpRef ref = new PhpRef(pl.codeloom.php.runtime.PhpNull.NULL);
            ref.bind();
            return ref;
        });
    }

    @TruffleBoundary
    public PhpRef findGlobal(String name) {
        return globals.get(name);
    }

    @TruffleBoundary
    public void bindGlobal(String name, PhpRef ref) {
        ref.bind();
        PhpRef old = globals.put(name, ref);
        if (old != null) {
            old.unbind();
        }
        globalsVersion++;
    }

    @TruffleBoundary
    public void unsetGlobal(String name) {
        PhpRef old = globals.remove(name);
        if (old != null) {
            old.unbind();
        }
        globalsVersion++;
    }

    /** date_default_timezone_set(); UTC until set, as php.ini's date.timezone default. */
    private java.time.ZoneId timezone = java.time.ZoneId.of("UTC");

    public java.time.ZoneId timezone() {
        return timezone;
    }

    public void setTimezone(java.time.ZoneId zone) {
        timezone = zone;
    }

    // ---- lifecycle ----

    /** register_shutdown_function's callables with their arguments, run when the script ends or exits. */
    private final List<Object[]> shutdownFunctions = new java.util.ArrayList<>();

    @TruffleBoundary
    public void addShutdownFunction(Object callable, Object[] arguments) {
        Object[] entry = new Object[arguments.length + 1];
        entry[0] = callable;
        System.arraycopy(arguments, 0, entry, 1, arguments.length);
        shutdownFunctions.add(entry);
    }

    /** Runs the shutdown functions (also those they register), once. */
    @TruffleBoundary
    public void runShutdownFunctions() {
        for (int i = 0; i < shutdownFunctions.size(); i++) {
            Object[] entry = shutdownFunctions.get(i);
            pl.codeloom.php.runtime.Callables.call(this, entry[0], java.util.Arrays.copyOfRange(entry, 1, entry.length));
        }
        shutdownFunctions.clear();
    }

    /** exit()/die(): shutdown functions run, output is flushed, and the context closes with the status. */
    @TruffleBoundary
    public void exit(int status) {
        runShutdownFunctions();
        endRequest();
        env.getContext().closeExited(null, status);
    }

    /** get_defined_constants(): the defined constants by name. */
    @TruffleBoundary
    public PhpArray constantsArray() {
        PhpArray array = new PhpArray();
        for (Map.Entry<String, Object> constant : constants.entrySet()) {
            array.put(pl.codeloom.php.runtime.ArrayKey.of(Str.of(constant.getKey())), constant.getValue());
        }
        return array;
    }

    /** `$GLOBALS` read as a whole: a copy of the global variables (PHP 8.1+: read-only). */
    @TruffleBoundary
    public PhpArray globalsArray() {
        PhpArray array = new PhpArray();
        for (Map.Entry<String, PhpRef> global : globals.entrySet()) {
            array.put(pl.codeloom.php.runtime.ArrayKey.of(Str.of(global.getKey())), global.getValue().get());
        }
        return array;
    }

    /** Changes whenever a global variable is rebound or unset, so nodes may cache a global's reference. */
    public int globalsVersion() {
        return globalsVersion;
    }

    /** set_error_handler(): stores the handler and returns the previous one. This runtime raises no warnings. */
    public Object swapErrorHandler(Object handler) {
        Object previous = errorHandler;
        errorHandler = handler;
        return previous;
    }

    public Object errorHandler() {
        return errorHandler;
    }

    // ---- output ----

    @TruffleBoundary
    public void write(TruffleString s) {
        try {
            if (buffers.isEmpty()) {
                if (response != null && !response.isSent()) {
                    out.write(response.block());
                }
                out.write(Str.readBytes(s));
            } else {
                buffers.peek().bytes.write(Str.readBytes(s));
            }
        } catch (IOException e) {
            throw new PhpError("output failed: " + e.getMessage());
        }
    }

    // the phases a buffer's handler is called in, and the flags of what a buffer allows (PHP_OUTPUT_HANDLER_*)
    private static final int PHASE_START = 1;
    private static final int PHASE_CLEAN = 2;
    private static final int PHASE_FLUSH = 4;
    private static final int PHASE_FINAL = 8;
    public static final long CLEANABLE = 16;
    public static final long FLUSHABLE = 32;
    public static final long REMOVABLE = 64;

    /** An ob_start() level: what was written to it, and the handler its contents pass through (or null). */
    private static final class OutputBuffer {
        final ByteBuilder bytes = new ByteBuilder();
        final Object handler;
        final long flags;
        boolean started;

        OutputBuffer(Object handler, long flags) {
            this.handler = handler;
            this.flags = flags;
        }
    }

    /** ob_start($handler, 0, $flags); handler is null for none. */
    @TruffleBoundary
    public void startBuffer(Object handler, long flags) {
        buffers.push(new OutputBuffer(handler, flags));
    }

    /** ob_start() with no handler, everything allowed. */
    public void startBuffer() {
        startBuffer(null, CLEANABLE | FLUSHABLE | REMOVABLE);
    }

    /** ob_get_contents(): the current buffer, or null if none. */
    @TruffleBoundary
    public TruffleString bufferContents() {
        return buffers.isEmpty() ? null : Str.of(buffers.peek().bytes.toByteArray());
    }

    /** ob_get_level(). */
    public int bufferLevel() {
        return buffers.size();
    }

    /** ob_list_handlers(): the handlers' names, outermost first. */
    @TruffleBoundary
    public List<String> bufferHandlerNames() {
        List<String> names = new java.util.ArrayList<>();
        for (java.util.Iterator<OutputBuffer> it = buffers.descendingIterator(); it.hasNext(); ) {
            Object handler = it.next().handler;
            names.add(handler == null ? "default output handler"
                    : handler instanceof TruffleString name ? Str.java(name) : "Closure::__invoke");
        }
        return names;
    }

    /** Whether the current buffer allows an operation (one of the flags); false if there is no buffer. */
    @TruffleBoundary
    public boolean bufferAllows(long flag) {
        return !buffers.isEmpty() && (buffers.peek().flags & flag) != 0;
    }

    /** A buffer's contents through its handler, in a phase; false from the handler passes them unchanged. */
    private TruffleString process(OutputBuffer buffer, int phase) {
        TruffleString contents = Str.of(buffer.bytes.toByteArray());
        if (buffer.handler == null) {
            return contents;
        }
        int mode = buffer.started ? phase : phase | PHASE_START;
        buffer.started = true;
        Object result = pl.codeloom.php.runtime.Callables.call(this, buffer.handler, contents, (long) mode);
        return result == Boolean.FALSE ? contents : pl.codeloom.php.runtime.Convert.toStr(result);
    }

    /** Ends the current buffer, passing its contents to the next level (ob_end_flush); false if there was none. */
    @TruffleBoundary
    public boolean flushBuffer() {
        OutputBuffer top = buffers.peek();
        if (top == null) {
            return false;
        }
        TruffleString output = process(top, PHASE_FINAL);
        buffers.poll();
        write(output);
        return true;
    }

    /** ob_flush(): the current buffer's contents go to the next level, the buffer stays (empty). */
    @TruffleBoundary
    public void flushBufferContents() {
        OutputBuffer top = buffers.peek();
        TruffleString output = process(top, PHASE_FLUSH);
        top.bytes.reset();
        buffers.poll();
        write(output);
        buffers.push(top);
    }

    /** ob_clean(): the current buffer's contents are discarded (its handler still sees them), the buffer stays. */
    @TruffleBoundary
    public void cleanBuffer() {
        OutputBuffer top = buffers.peek();
        process(top, PHASE_CLEAN);
        top.bytes.reset();
    }

    /** The end of the script or request: open output buffers are flushed, then the output (and headers). */
    @TruffleBoundary
    public void endRequest() {
        while (flushBuffer()) {
            // each level passes its contents to the one below
        }
        flush();
    }

    /** Discards the current buffer (ob_end_clean); its handler sees the contents last. False if there was none. */
    @TruffleBoundary
    public boolean endBuffer() {
        OutputBuffer top = buffers.peek();
        if (top == null) {
            return false;
        }
        process(top, PHASE_CLEAN | PHASE_FINAL);
        buffers.poll();
        return true;
    }

    @TruffleBoundary
    public void flush() {
        try {
            if (response != null && !response.isSent()) {
                out.write(response.block());
            }
            out.flush();
        } catch (IOException e) {
            throw new PhpError("output failed: " + e.getMessage());
        }
    }
}
