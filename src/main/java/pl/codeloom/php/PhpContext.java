package pl.codeloom.php;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleFile;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.source.Source;
import com.oracle.truffle.api.strings.TruffleString;
import pl.codeloom.php.builtins.BuiltinState;
import pl.codeloom.php.builtins.Builtins;
import pl.codeloom.php.builtins.ErrorFunctions;
import pl.codeloom.php.builtins.Ini;
import com.oracle.truffle.api.nodes.RootNode;
import pl.codeloom.php.parser.Ast;
import pl.codeloom.php.parser.AstReader;
import pl.codeloom.php.parser.Translator;
import pl.codeloom.php.runtime.ClassTemplate;
import pl.codeloom.php.runtime.ByteBuilder;
import pl.codeloom.php.runtime.Callables;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpBailout;
import pl.codeloom.php.runtime.PhpClass;
import pl.codeloom.php.runtime.PhpClosure;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpGenerator;
import pl.codeloom.php.runtime.PhpRef;
import pl.codeloom.php.runtime.PhpThrowable;
import pl.codeloom.php.runtime.Phases;
import pl.codeloom.php.runtime.RefIteration;
import pl.codeloom.php.runtime.Str;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
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
    /** php.Parser=java: files are parsed by the runtime's parser ({@link SourceFiles}), not read from exports. */
    private final boolean javaParser;
    private final OutputStream out;
    private final String sapiName;

    /** The declared functions by lowercase name, in declaration order, as php-src's function table. */
    private final Map<String, PhpFunction> functions = new java.util.LinkedHashMap<>();
    /** The declared functions by their name's language-level id (null: not declared here). */
    private PhpFunction[] functionSlots = new PhpFunction[256];
    /** The declared classes by lowercase name, in declaration order, as php-src's class table. */
    private final Map<String, PhpClass> classes = new java.util.LinkedHashMap<>();
    /** The keys of {@link #classes} that class_alias() made (php-src's IS_ALIAS_PTR entries), to their class. */
    private final Map<String, PhpClass> classAliases = new HashMap<>();
    /** How many of the first functions and classes declared are the built-ins (the prelude's come later). */
    private final int internalFunctions;
    private final int internalClasses;
    /** spl_autoload_register's callables, in calling order; and the classes being autoloaded now. */
    private final List<Object> autoloaders = new java.util.ArrayList<>();
    private final Set<String> autoloading = new HashSet<>();
    /** The classes being declared (instantiated, not yet in the class table), by lowercase name. */
    private final Map<String, ClassTemplate> linking = new HashMap<>();

    /** A class being declared: its template, found by inheritance checks (ZEND_FETCH_CLASS_ALLOW_UNLINKED). */
    public ClassTemplate linkingClass(String lowerName) {
        return linking.get(lowerName);
    }

    /**
     * While an early binding's checks run as the compile's (zend_can_early_bind with ZEND_COMPILE_IGNORE_OTHER_FILES):
     * the file being compiled, whose classes and the built-ins are the only ones they see; else null.
     */
    private String compiledFile;

    public String compiledFile() {
        return compiledFile;
    }

    public void setCompiledFile(String file) {
        compiledFile = file;
    }

    public void setLinking(String lowerName, ClassTemplate template) {
        if (template == null) {
            linking.remove(lowerName);
        } else {
            linking.put(lowerName, template);
        }
    }
    /** The declared classes by their lowercase name's language-level id (null: not declared here). */
    private PhpClass[] classSlots = new PhpClass[256];
    /**
     * The defined constants by {@link PhpLanguage#constantKey key}, in definition order (get_defined_constants).
     */
    private final Map<String, Object> constants = new java.util.LinkedHashMap<>();
    /** The names as defined of the constants whose key differs (a namespace not in lowercase), by key. */
    private final Map<String, String> constantNames = new HashMap<>();
    /** The defined constants by their name's language-level id (null: not defined here). */
    private Object[] constantSlots = new Object[256];
    private final Map<String, PhpRef> globals = new HashMap<>();
    private final Set<String> included = new HashSet<>();
    /** The files whose compile diagnostics this request raised: OPcache compiles a file once and doesn't replay them. */
    private final Set<Object> compiled = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** The include sites (IncludeNode) that ran each file in this context, by canonical path. */
    private final Map<String, Set<Object>> includeSites = new HashMap<>();
    private final java.util.ArrayDeque<OutputBuffer> buffers = new java.util.ArrayDeque<>();
    /** OG(running): the buffer whose handler is running, or null; an output-buffer operation meanwhile is fatal. */
    private OutputBuffer running;
    /** The next object id: ids start at 1 in each context, as in each php process. */
    private int nextObjectId = 1;
    private final BuiltinState builtinState;
    /** Static variables (`static $x`) by function, then name. */
    private final Map<PhpFunction, Map<String, PhpRef>> staticVariables = new HashMap<>();
    /** The innermost foreach by reference in progress; it links the ones it runs inside. */
    private RefIteration innermostRefIteration;
    private final Callables.LastCall lastCall = new Callables.LastCall();

    /** The prelude files this context has run (see {@link Prelude}). */
    private final java.util.Set<String> preludeLoaded = new java.util.HashSet<>();
    /** The CGI SAPI's response, or null under the CLI SAPI. */
    private final pl.codeloom.php.builtins.CgiSapi.Response response;
    /** The request body (php://input): stdin under the CGI SAPI, empty under the CLI. */
    private final byte[] requestBody;
    /** The server's end of the request (CgiSapi.REQUEST_END), or null; whether fastcgi_finish_request() ran it. */
    private final Runnable requestEnd;
    private boolean requestEnded;

    public PhpContext(PhpLanguage language, TruffleLanguage.Env env, String astRoot, String sourceRoot,
                      String sapiName, boolean debug, Map<String, String> ini) {
        this.sapiName = sapiName;
        boolean cgi = !sapiName.equals("cli");
        this.builtinState = new BuiltinState(cgi, ini);
        this.language = language;
        this.env = env;
        this.astRoot = Path.of(astRoot).toAbsolutePath().normalize();
        this.sourceRoot = Path.of(sourceRoot).toAbsolutePath().normalize();
        this.javaParser = parser(env.getOptions().get(PhpLanguage.Parser));
        this.out = new BufferedOutputStream(env.out(), 1 << 16);
        Builtins.install(this, language.builtins(debug, sapiName));
        if (!cgi) {
            Builtins.defineStdStreams(this);
        }
        internalFunctions = functions.size();
        internalClasses = classes.size();
        PhpArray argv = new PhpArray();
        for (String argument : env.getApplicationArguments()) {
            argv.append(Str.of(argument));
        }
        globalRef("argv").set(argv);
        globalRef("argc").set((long) argv.size());
        response = cgi ? new pl.codeloom.php.builtins.CgiSapi.Response() : null;
        if (response != null && builtinState.ini().isOn("expose_php")) {
            // php_request_startup: SAPI_PHP_VERSION_HEADER, which header_remove() can take away
            response.header("X-Powered-By: PHP/" + Builtins.PHP_VERSION, true, 0);
        }
        requestBody = cgi ? readRequestBody(env) : new byte[0];
        requestEnd = cgi ? pl.codeloom.php.builtins.CgiSapi.REQUEST_END.get() : null;
        builtinState.startupErrors(this, cgi);
        superglobals(argv);
        if (cgi) {
            pl.codeloom.php.builtins.CgiSapi.installRequest(this, env.getEnvironment(), requestBody);
        }
    }

    private static boolean parser(String option) {
        if (!option.equals("export") && !option.equals("java")) {
            throw new IllegalArgumentException("php.Parser is export or java, not " + option);
        }
        return option.equals("java");
    }

    private static byte[] readRequestBody(TruffleLanguage.Env env) {
        try {
            return env.in().readAllBytes();
        } catch (IOException e) {
            throw new PhpError("reading the request body failed: " + e.getMessage());
        }
    }

    /** php_sapi_name(): cli, cgi-fcgi or fpm-fcgi. */
    public String sapiName() {
        return sapiName;
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
        long micros = pl.codeloom.php.runtime.WallClock.micros();
        if (response == null) {
            String script = argv.size() > 0 ? Str.java((TruffleString) argv.get(0L)) : "";
            server.put(Str.of("PHP_SELF"), Str.of(script));
            server.put(Str.of("SCRIPT_NAME"), Str.of(script));
            server.put(Str.of("SCRIPT_FILENAME"), Str.of(script));
            server.put(Str.of("PATH_TRANSLATED"), Str.of(script));
            server.put(Str.of("DOCUMENT_ROOT"), Str.EMPTY);
        }
        server.put(Str.of("REQUEST_TIME_FLOAT"), micros / 1e6);
        server.put(Str.of("REQUEST_TIME"), micros / 1_000_000);
        if (response == null) {
            server.put(Str.of("argv"), argv);
            server.put(Str.of("argc"), (long) argv.size());
        }
        for (String name : SUPERGLOBALS) {
            if (!name.equals("_SERVER") && !name.equals("_SESSION")) {
                globalRef(name).set(new PhpArray());
            }
        }
        globalRef("_SERVER").set(builtinState.requestGlobals(this, server, (PhpArray) globalRef("_ENV").get(),
                response != null));
    }

    public static PhpContext get(Node node) {
        return CONTEXT.get(node);
    }

    /**
     * The context's roots for the refcount census (Census, -Dphp.rc.verify=true): globals, static variables,
     * constants and classes' static properties, each with a name for the report.
     */
    public void censusRoots(java.util.function.BiConsumer<String, Object> root) {
        globals.forEach((name, ref) -> root.accept("$" + name, ref));
        staticVariables.forEach((function, vars) -> vars.forEach((name, ref) ->
                root.accept("static $" + name + " in " + function.getName(), ref)));
        constants.forEach((name, value) -> root.accept("constant " + name, value));
        for (PhpClass phpClass : classes.values()) {
            phpClass.staticPropertiesForCensus().forEach((name, ref) ->
                    root.accept(phpClass.getName() + "::$" + name, ref));
        }
    }

    /**
     * The values built-ins keep in Java state, each one a holder php-src counts (a zval it copied): autoloaders,
     * shutdown functions and their arguments, output handlers, error and exception handlers. For the refcount
     * census (Census.audit), which counts them as holders.
     */
    public void censusHolders(java.util.function.BiConsumer<String, Object> holder) {
        autoloaders.forEach(callable -> holder.accept("autoloader", callable));
        for (Object[] entry : shutdownFunctions) {
            for (int i = 0; i < entry.length; i++) {
                holder.accept(i == 0 ? "shutdown function" : "shutdown function argument " + i, entry[i]);
            }
        }
        for (OutputBuffer buffer : buffers) {
            if (buffer.handler != null) {
                holder.accept("output handler", buffer.handler);
            }
        }
        errors().forEachHandler(handler -> holder.accept("error/exception handler", handler));
        pendingReleases.forEach(value -> holder.accept("pending release", value));
    }

    /**
     * The counts results of PHP code called from runtime code still own (Refcount.pending). Built-ins drain it above
     * their mark, so it is a stack's: each fiber has its own, swapped in while it runs (PhpFiber).
     */
    private ArrayList<Object> pendingReleases = new ArrayList<>();

    /** Arguments moved into a built-in's call, waiting for it to start (Refcount.toBuiltin, takeMoved). */
    private final ArrayList<Object> movedToBuiltins = new ArrayList<>();

    public ArrayList<Object> movedToBuiltins() {
        return movedToBuiltins;
    }

    public ArrayList<Object> pendingReleases() {
        return pendingReleases;
    }

    /** Makes pending the running stack's pending releases; returns the list it replaces. */
    public ArrayList<Object> swapPendingReleases(ArrayList<Object> pending) {
        ArrayList<Object> replaced = pendingReleases;
        pendingReleases = pending;
        return replaced;
    }

    /** The objects made in this context, kept for the refcount audit (-Dphp.rc.audit=true); empty otherwise. */
    private final ArrayList<Object> auditedObjects = new ArrayList<>();

    public ArrayList<Object> auditedObjects() {
        return auditedObjects;
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
    public Callables.LastCall lastCall() {
        return lastCall;
    }

    public BuiltinState builtinState() {
        return builtinState;
    }

    public RefIteration innermostRefIteration() {
        return innermostRefIteration;
    }

    public void setInnermostRefIteration(RefIteration iteration) {
        innermostRefIteration = iteration;
    }

    /** The reference of a function's `static $name` variable, or null before its first initialization. */
    @TruffleBoundary
    public PhpRef findStaticVariable(PhpFunction function, String name) {
        Map<String, PhpRef> variables = staticVariables.get(function);
        return variables == null ? null : variables.get(name);
    }

    /** `static $name` in a call whose frame's closure is closure: a closure object's own, else the function's. */
    public PhpRef findStaticVariable(PhpFunction function, Object closure, String name) {
        return closure instanceof PhpClosure c && c.hasStatics() ? c.findStatic(name)
                : findStaticVariable(function, name);
    }

    public PhpRef createStaticVariable(PhpFunction function, Object closure, String name, Object initial) {
        return closure instanceof PhpClosure c && c.hasStatics() ? c.createStatic(name, initial)
                : createStaticVariable(function, name, initial);
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
     * changes (checked at each load, as opcache's timestamp validation). With php.Parser=java the file itself is
     * parsed, and it is the file's changes that count ({@link SourceFiles}).
     */
    @TruffleBoundary
    public RootNode loadFile(String path) {
        String canonical = canonicalPath(path);
        if (javaParser) {
            return SourceFiles.load(this, path, canonical);
        }
        int outer = Phases.enter(Phases.INCLUDE);
        try {
            return loadExport(path, canonical);
        } finally {
            Phases.exit(outer);
        }
    }

    private RootNode loadExport(String path, String canonical) {
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

    private RootNode translate(String path, String canonical, TruffleFile file, Path json) {
        Ast ast;
        byte[] export;
        int outer = Phases.enter(Phases.PARSE);
        try (InputStream in = file.newInputStream()) {
            export = in.readAllBytes();
            ast = AstReader.read(export);
        } catch (IOException e) {
            throw noExport(path, json);
        } finally {
            Phases.exit(outer);
        }
        outer = Phases.enter(Phases.FILE_TRANSLATE);
        try {
            return new Translator(language, canonical, phpSource(canonical), false).translateFile(ast,
                    () -> AstReader.read(export));
        } finally {
            Phases.exit(outer);
        }
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

    /**
     * Whether an include site ran a file before in this context: a site that runs a file twice in one request (a
     * template part in a loop) runs it a number of times that depends on the data (PhpBytecodeRootNode.prepareForCompilation).
     */
    @TruffleBoundary
    public boolean includedBefore(String canonical, Object site) {
        return !includeSites.computeIfAbsent(canonical, path -> new HashSet<>(2)).add(site);
    }

    /** Whether the request compiles this file's translation (its diagnostics) for the first time. */
    @TruffleBoundary
    public boolean firstCompile(Object translation) {
        return compiled.add(translation);
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

    /**
     * The function each call by an unqualified name in a namespace found first, by its site's number
     * (PhpLanguage.nextFallbackSite): INIT_NS_FCALL_BY_NAME's runtime cache slot, which keeps it for the request.
     */
    private PhpFunction[] fallbackFunctions = new PhpFunction[64];

    public PhpFunction fallbackFunction(int site) {
        PhpFunction[] kept = fallbackFunctions;
        return site < kept.length ? kept[site] : null;
    }

    @TruffleBoundary
    public void keepFallbackFunction(int site, PhpFunction function) {
        if (site >= fallbackFunctions.length) {
            fallbackFunctions = Arrays.copyOf(fallbackFunctions, grown(fallbackFunctions.length, site));
        }
        fallbackFunctions[site] = function;
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

    /**
     * The declared functions, the built-ins first then the script's, each in declaration order: what
     * get_defined_functions() lists as "internal" and "user". The runtime's own helpers aren't listed.
     */
    @TruffleBoundary
    public List<List<PhpFunction>> declaredFunctions() {
        List<PhpFunction> internal = new java.util.ArrayList<>();
        List<PhpFunction> user = new java.util.ArrayList<>();
        int index = 0;
        for (PhpFunction function : functions.values()) {
            if (index++ < internalFunctions) {
                if (!isRuntimeHelper(function.getName())) {
                    internal.add(function);
                }
            } else {
                user.add(function);
            }
        }
        return List.of(internal, user);
    }

    /**
     * The prelude's and built-ins' own helpers, which php-src doesn't have: their names start with "Truffle"
     * (trait TruffleDateTime, functions in namespace Truffle, the php.Debug truffle_* functions).
     */
    private static boolean isRuntimeHelper(String name) {
        return name.regionMatches(true, 0, "truffle", 0, "truffle".length());
    }

    /**
     * The declared classes, interfaces and traits as get_declared_classes() and the like list them: the built-in
     * ones first (with every prelude class, which php-src has from the start), then the script's in declaration
     * order. Anonymous classes are listed once declared, where php-src binds them at compile time.
     */
    /** The classes declared so far, in no particular order (aliases repeat theirs). */
    @TruffleBoundary
    public java.util.Collection<PhpClass> classValues() {
        return classes.values();
    }

    @TruffleBoundary
    public List<Map.Entry<String, PhpClass>> declaredClasses() {
        for (String file : language.preludeFiles()) {
            if (preludeLoaded.add(file)) {
                language.preludeRoot(file).getCallTarget().call(new Object[PhpFunction.ARG_FIRST]);
            }
        }
        List<Map.Entry<String, PhpClass>> internal = new java.util.ArrayList<>();
        List<Map.Entry<String, PhpClass>> user = new java.util.ArrayList<>();
        int index = 0;
        for (Map.Entry<String, PhpClass> entry : classes.entrySet()) {
            PhpClass phpClass = entry.getValue();
            // an alias is listed by its key, in lower case (get_declared_class_impl)
            String name = classAliases.containsKey(entry.getKey()) ? entry.getKey() : phpClass.getName();
            boolean builtin = index++ < internalClasses || language.preludeFile(entry.getKey()) != null;
            if (!builtin) {
                user.add(Map.entry(name, phpClass));
            } else if (!isRuntimeHelper(phpClass.getName())) {
                internal.add(Map.entry(name, phpClass));
            }
        }
        internal.addAll(user);
        return internal;
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
        if (phpClass != null || autoloaders.isEmpty() || !validClassName(className)
                || !autoloading.add(lowerName)) {
            return phpClass;
        }
        try {
            for (Object autoloader : List.copyOf(autoloaders)) {
                pl.codeloom.php.runtime.Refcount.done(Callables.call(this, autoloader, Str.of(className)));
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

    /** zend_is_valid_class_name, non-empty: what php-src passes to an autoloader (0-9, a-z, A-Z, _, a backslash, 0x80-0xff). */
    private static boolean validClassName(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '\\'
                    || c >= 0x80)) {
                return false;
            }
        }
        return !name.isEmpty();
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
        pl.codeloom.php.runtime.Refcount.hold(callable);
        return true;
    }

    @TruffleBoundary
    public boolean removeAutoloader(Object callable) {
        int index = autoloaders.indexOf(callable);
        if (index < 0) {
            return false;
        }
        pl.codeloom.php.runtime.Refcount.unhold(autoloaders.remove(index));
        return true;
    }

    @TruffleBoundary
    public List<Object> autoloaders() {
        return List.copyOf(autoloaders);
    }

    @TruffleBoundary
    public void declareClass(PhpClass phpClass) {
        PhpClass previous = classes.putIfAbsent(phpClass.getLowerName(), phpClass);
        if (previous == phpClass) {
            // declared while it was linked (Variance's delayed autoloads)
            return;
        }
        if (previous != null) {
            throw new PhpError("Cannot declare class " + phpClass.getName() + ", because the name is already in use");
        }
        phpClass.setContextAliases(classAliases);
        phpClass.makeKey(language);
        int id = language.classId(phpClass.getLowerName());
        if (id >= classSlots.length) {
            classSlots = Arrays.copyOf(classSlots, grown(classSlots.length, id));
        }
        classSlots[id] = phpClass;
    }

    /**
     * class_alias(): the class under another name too (zend_register_class_alias_ex); false if the name is taken.
     * The alias is the class itself, so get_class() and ::class keep the class's own name.
     */
    @TruffleBoundary
    public boolean declareAlias(String lowerAlias, PhpClass phpClass) {
        if (findClass(lowerAlias) != null) {
            return false;
        }
        classes.put(lowerAlias, phpClass);
        classAliases.put(lowerAlias, phpClass);
        int id = language.classId(lowerAlias);
        if (id >= classSlots.length) {
            classSlots = Arrays.copyOf(classSlots, grown(classSlots.length, id));
        }
        classSlots[id] = phpClass;
        return true;
    }

    /** The class declared under a name id (PhpLanguage.classId), or null. */
    public PhpClass classAt(int id) {
        PhpClass[] slots = classSlots;
        return id < slots.length ? slots[id] : null;
    }

    /**
     * A global constant's value, or null if undefined: the name without a leading backslash, its namespace in any
     * case. true, false and null are folded by the translator.
     */
    @TruffleBoundary
    public Object findConstant(String name) {
        return constants.get(PhpLanguage.constantKey(name));
    }

    @TruffleBoundary
    public boolean defineConstant(String name, Object value) {
        String key = PhpLanguage.constantKey(name);
        if (constants.putIfAbsent(key, value) != null) {
            return false;
        }
        if (!key.equals(name)) {
            constantNames.put(key, name);
        }
        int id = language.constantId(key);
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

    /**
     * constantAt for a read that is an error when the constant isn't defined. The slots grow when a constant is
     * defined, so an id past them is such an error: compiled code leaves that case to the interpreter, rather
     * than merging a null into the value it tests next.
     */
    public Object requiredConstantAt(int id) {
        Object[] slots = constantSlots;
        if (id >= slots.length) {
            CompilerDirectives.transferToInterpreter();
            return null;
        }
        return slots[id];
    }

    // ---- global variables ----

    private int globalsVersion;
    /**
     * The globals' references by name id (PhpLanguage.globalId), as the map has them: a site of `global $x` that
     * serves several contexts reads its id's slot (globalAt) instead of the map. Every change of the map changes
     * the slot.
     */
    private PhpRef[] globalSlots = new PhpRef[256];

    /** The global variable's reference, created (holding null) if absent: `global $x`, top-level code. */
    @TruffleBoundary
    public PhpRef globalRef(String name) {
        PhpRef found = globals.get(name);
        if (found != null) {
            return found;
        }
        PhpRef ref = new PhpRef(pl.codeloom.php.runtime.PhpNull.NULL);
        ref.bind();
        globals.put(name, ref);
        setGlobalSlot(name, ref);
        return ref;
    }

    @TruffleBoundary
    public PhpRef findGlobal(String name) {
        return globals.get(name);
    }

    /** The reference of the global whose name has this id (PhpLanguage.globalId), or null: see globalSlots. */
    public PhpRef globalAt(int id) {
        PhpRef[] slots = globalSlots;
        return id < slots.length ? slots[id] : null;
    }

    private void setGlobalSlot(String name, PhpRef ref) {
        int id = language.globalId(name);
        if (id >= globalSlots.length) {
            globalSlots = Arrays.copyOf(globalSlots, grown(globalSlots.length, id));
        }
        globalSlots[id] = ref;
    }

    @TruffleBoundary
    public void bindGlobal(String name, PhpRef ref) {
        ref.bind();
        PhpRef old = globals.put(name, ref);
        if (old != null) {
            old.unbind();
        }
        setGlobalSlot(name, ref);
        globalsVersion++;
    }

    @TruffleBoundary
    public void unsetGlobal(String name) {
        PhpRef old = globals.remove(name);
        if (old != null) {
            old.unbind();
            setGlobalSlot(name, null);
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
    /** The shutdown functions have started: exit() in one doesn't run them again. */
    private boolean shuttingDown;

    @TruffleBoundary
    public void addShutdownFunction(Object callable, Object[] arguments) {
        Object[] entry = new Object[arguments.length + 1];
        entry[0] = callable;
        System.arraycopy(arguments, 0, entry, 1, arguments.length);
        for (Object value : entry) {
            pl.codeloom.php.runtime.Refcount.hold(value);
        }
        shutdownFunctions.add(entry);
    }

    /**
     * Runs the shutdown functions (also those they register), once. An exception one of them doesn't catch is an
     * uncaught fatal error, and it, a fatal error or exit() in one ends them all (php-src's zend_try around them).
     */
    @TruffleBoundary
    public void runShutdownFunctions() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        guarded(() -> {
            for (int i = 0; i < shutdownFunctions.size(); i++) {
                Object[] entry = shutdownFunctions.get(i);
                pl.codeloom.php.runtime.Refcount.done(Callables.call(this, entry[0],
                        Arrays.copyOfRange(entry, 1, entry.length)));
            }
        });
        // php_free_shutdown_functions: the entries let go once all ran
        List<Object[]> ran = List.copyOf(shutdownFunctions);
        shutdownFunctions.clear();
        guarded(() -> {
            for (Object[] entry : ran) {
                for (Object value : entry) {
                    pl.codeloom.php.runtime.Refcount.unhold(value);
                }
            }
        });
    }

    /** exit()/die(): shutdown functions run, output is flushed, and the context closes with the status. */
    @TruffleBoundary
    public void exit(int status) {
        if (!shuttingDown) {
            pl.codeloom.php.runtime.Refcount.audit(this, "exit");
        }
        runShutdownFunctions();
        guarded(this::destroyGenerators);
        destroyFibers();
        endRequest();
        env.getContext().closeExited(null, status);
    }

    /**
     * The script's end (php_request_shutdown), also after an uncaught exception or a fatal error: the shutdown
     * functions, the output; the context closes with the exit status a fatal error left (255).
     */
    @TruffleBoundary
    public void endScript() {
        if (!shuttingDown) {
            guarded(() -> pl.codeloom.php.runtime.Refcount.releasePending(pendingReleases, 0));
            pl.codeloom.php.runtime.Refcount.audit(this, "end of script");
        }
        runShutdownFunctions();
        guarded(this::destroyGenerators);
        destroyFibers();
        // an output handler's exception at the end, as php-src's zend_try around php_output_end_all
        guarded(this::endRequest);
        int status = errors().exitStatus();
        if (status != 0) {
            env.getContext().closeExited(null, status);
        }
    }

    /**
     * php-src's zend_try around a step of the request's end: an exception it doesn't catch is reported as uncaught,
     * and a fatal error (already reported) ends the step.
     */
    private void guarded(Runnable step) {
        try {
            step.run();
        } catch (PhpThrowable e) {
            ErrorFunctions.uncaughtInternal(this, e);
        } catch (PhpBailout ended) {
            // reported where it was raised
        }
    }

    /**
     * The generators with finally blocks made in this request, in the order they were made, for the request's end.
     * Pruned of closed ones as it grows.
     */
    private final List<PhpGenerator> generatorsWithFinally = new ArrayList<>();
    private int generatorsPruneAt = 64;

    /** A generator whose body has finally blocks: destroyed at the request's end if it is still suspended. */
    @TruffleBoundary
    public void generatorWithFinally(PhpGenerator generator) {
        if (generatorsWithFinally.size() >= generatorsPruneAt) {
            generatorsWithFinally.removeIf(PhpGenerator::isClosed);
            generatorsPruneAt = Math.max(64, generatorsWithFinally.size() * 2);
        }
        generatorsWithFinally.add(generator);
    }

    /**
     * zend_objects_store_call_destructors for generators: at the end of a request that ends cleanly (no fatal
     * error, which php-src's unclean shutdown skips destructors after), each generator still suspended runs its
     * finally blocks, in the order the generators were made. php-src destroys a generator as soon as nothing
     * references it, which needs object reference counts (issue 005): here every one lives until the end.
     */
    private void destroyGenerators() {
        if (errors().exitStatus() != 0) {
            generatorsWithFinally.clear();
            return;
        }
        for (int i = 0; i < generatorsWithFinally.size(); i++) {
            generatorsWithFinally.get(i).destroy();
        }
        generatorsWithFinally.clear();
    }

    /** EG(active_fiber): the fiber running, null on the request's own stack. */
    private pl.codeloom.php.runtime.PhpFiber currentFiber;
    /** The fibers started in this request, in the order they were started, for the request's end. */
    private final List<pl.codeloom.php.runtime.PhpFiber> fibers = new ArrayList<>();
    private int fibersPruneAt = 64;
    /** Every fiber thread made here that may still be alive, for the request's end to wait for. */
    private final List<pl.codeloom.php.runtime.FiberThread> fiberThreads = new ArrayList<>();
    private int fiberThreadsPruneAt = 64;
    /** Fiber threads waiting for a fiber, the last one freed on top. */
    private final java.util.ArrayDeque<pl.codeloom.php.runtime.FiberThread> idleFiberThreads =
            new java.util.ArrayDeque<>();
    /** How many idle fiber threads a context keeps; one more ends instead. */
    private static final int IDLE_FIBER_THREADS = 16;
    /** Whether a fiber was started here: the context admits other threads until it is disposed. */
    private boolean hadFibers;
    /** The request's end is destroying the fibers: the list grows (a finally block starting one) but isn't pruned. */
    private boolean drainingFibers;
    /** zend_fiber_switch_blocking: the context is being finalized, PHP code can't switch fibers any more. */
    private boolean fibersBlocked;

    public boolean fibersBlocked() {
        return fibersBlocked;
    }

    /** An idle fiber thread with a stack of stackSize at least, or null (one with a smaller stack ends). */
    public pl.codeloom.php.runtime.FiberThread idleFiberThread(long stackSize) {
        pl.codeloom.php.runtime.FiberThread idle;
        while ((idle = idleFiberThreads.pollFirst()) != null) {
            if (idle.stackSizeAtLeast(stackSize)) {
                return idle;
            }
            idle.retire();
        }
        return null;
    }

    /** A new fiber thread, not started. */
    public pl.codeloom.php.runtime.FiberThread newFiberThread(long stackSize) {
        if (fiberThreads.size() >= fiberThreadsPruneAt) {
            fiberThreads.removeIf(thread -> !thread.isAlive());
            fiberThreadsPruneAt = Math.max(64, fiberThreads.size() * 2);
        }
        pl.codeloom.php.runtime.FiberThread thread = pl.codeloom.php.runtime.FiberThread.create(this, stackSize);
        fiberThreads.add(thread);
        return thread;
    }

    /**
     * A fiber ended on thread: whether the thread waits for the next fiber. Not while the request's end destroys
     * fibers or the context closes, nor beyond {@link #IDLE_FIBER_THREADS}.
     */
    public boolean keepFiberThread(pl.codeloom.php.runtime.FiberThread thread) {
        if (drainingFibers || fibersBlocked || idleFiberThreads.size() >= IDLE_FIBER_THREADS) {
            return false;
        }
        idleFiberThreads.addFirst(thread);
        return true;
    }

    /** Ends the idle fiber threads. */
    private void retireIdleFiberThreads() {
        pl.codeloom.php.runtime.FiberThread idle;
        while ((idle = idleFiberThreads.pollFirst()) != null) {
            idle.retire();
        }
    }

    /** The context is disposed: it no longer needs other threads admitted. */
    public void disposeFibers() {
        if (hadFibers) {
            hadFibers = false;
            pl.codeloom.php.runtime.PhpFiber.contextDisposed();
        }
    }

    public pl.codeloom.php.runtime.PhpFiber currentFiber() {
        return currentFiber;
    }

    public void setCurrentFiber(pl.codeloom.php.runtime.PhpFiber fiber) {
        currentFiber = fiber;
    }

    /** A fiber's thread started: it must end before the context closes. */
    public void fiberStarted(pl.codeloom.php.runtime.PhpFiber fiber) {
        if (!hadFibers) {
            hadFibers = true;
            pl.codeloom.php.runtime.PhpFiber.contextHasFibers();
        }
        if (fibers.size() >= fibersPruneAt && !drainingFibers) {
            fibers.removeIf(f -> f.status() == pl.codeloom.php.runtime.PhpFiber.Status.DEAD);
            fibersPruneAt = Math.max(64, fibers.size() * 2);
        }
        fibers.add(fiber);
    }

    /**
     * zend_objects_store_call_destructors for fibers: each fiber still suspended at the end of a request that ends
     * cleanly unwinds, its finally blocks running, in the order the fibers were started. After a fatal error, which
     * skips destructors, it unwinds without them. Either way its thread ends, as the context's close requires.
     */
    private void destroyFibers() {
        boolean clean = errors().exitStatus() == 0;
        drainingFibers = true;
        try {
            // by index: a finally block run here may start a fiber, which joins the end of the list
            for (int i = 0; i < fibers.size(); i++) {
                pl.codeloom.php.runtime.PhpFiber fiber = fibers.get(i);
                if (clean) {
                    guarded(fiber::destroy);
                } else {
                    fiber.abandon();
                }
            }
            fibers.clear();
            // every fiber has ended: the threads end too, and the request waits for them to leave the context
            retireIdleFiberThreads();
            for (int i = 0; i < fiberThreads.size(); i++) {
                fiberThreads.get(i).join();
            }
            fiberThreads.clear();
        } finally {
            drainingFibers = false;
        }
    }

    /**
     * The context closes: a fiber still suspended (the request ended by exit() in a fiber) ends without running.
     * Its thread isn't waited for here: the close holds the context's locks, which a thread leaving it needs, and
     * Truffle waits for the threads itself.
     */
    public void abandonFibers() {
        fibersBlocked = true;
        drainingFibers = true;
        // by index: what an abandoned fiber's end releases may run a destructor that starts one
        for (int i = 0; i < fibers.size(); i++) {
            fibers.get(i).abandon();
        }
        fibers.clear();
        retireIdleFiberThreads();
        fiberThreads.clear();
    }

    /** The error state: the handlers, the last error, the exit status. */
    public ErrorFunctions.State errors() {
        return builtinState.errors();
    }

    /** The ini settings. */
    public Ini ini() {
        return builtinState.ini();
    }

    /**
     * Writes to stderr (an error displayed there, the SAPI's log). Under the CLI the output written so far goes
     * first, as php-cli's unbuffered stdout keeps the order.
     */
    @TruffleBoundary
    public void writeError(byte[] bytes) {
        try {
            if (response == null) {
                out.flush();
            }
            env.err().write(bytes);
            env.err().flush();
        } catch (IOException e) {
            // php-src ignores a failed write to stderr
        }
    }

    /** get_defined_constants(): the defined constants by name. */
    @TruffleBoundary
    public PhpArray constantsArray() {
        PhpArray array = new PhpArray();
        for (Map.Entry<String, Object> constant : constants.entrySet()) {
            String name = constantNames.getOrDefault(constant.getKey(), constant.getKey());
            array.put(pl.codeloom.php.runtime.ArrayKey.of(Str.of(name)), constant.getValue());
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

    // ---- output ----

    public void write(TruffleString s) {
        write(s, null);
    }

    /** Output; site: the echo that writes it, where the output starts if it sends the headers (else the call's). */
    @TruffleBoundary
    public void write(TruffleString s, com.oracle.truffle.api.nodes.Node site) {
        if (s.isEmpty()) {
            // no bytes: no output, the headers stay unsent (ZEND_ECHO writes nothing for "")
            return;
        }
        try {
            OutputBuffer target = buffers.peek();
            if (target != null && target.state == HandlerState.DISABLED) {
                target = writableBelow();
            }
            if (target == null) {
                if (response != null && !response.isSent()) {
                    out.write(response.block(site != null ? pl.codeloom.php.runtime.SourceLocation.of(site)
                            : outputStartLocation()));
                }
                out.write(Str.readBytes(s));
            } else {
                if (running != null) {
                    target.producedOutput = true;
                }
                target.bytes.write(Str.readBytes(s));
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

    /**
     * A buffer's handler (PHP_OUTPUT_HANDLER_STARTED, _DISABLED): not called yet, called, or failed (it returned
     * false or threw), after which it isn't called again and what is written passes on to the level below.
     */
    private enum HandlerState { NEW, STARTED, DISABLED }

    /** An ob_start() level: what was written to it, and the handler its contents pass through (or null). */
    private static final class OutputBuffer {
        final ByteBuilder bytes = new ByteBuilder();
        final Object handler;
        /** The handler's name (ob_list_handlers, the notices): its callable name, or "default output handler". */
        final String name;
        final long flags;
        HandlerState state = HandlerState.NEW;
        /** PHP_OUTPUT_HANDLER_PRODUCED_OUTPUT: written to while a handler ran, which a handler may not do. */
        boolean producedOutput;

        OutputBuffer(Object handler, String name, long flags) {
            this.handler = handler;
            this.name = name;
            this.flags = flags;
        }
    }

    /**
     * What a handler's run passes on (null for nothing), and the exception it threw, which the operation raises once
     * it has finished (the buffer popped, the output written), as php-src leaves it pending.
     */
    private record Processed(TruffleString output, PhpThrowable thrown) {
        static final Processed NONE = new Processed(null, null);

        void rethrow() {
            if (thrown != null) {
                throw thrown;
            }
        }
    }

    /**
     * ob_start($handler, 0, $flags); handler is null for none, name its callable name. While a handler runs, a fatal
     * error (php_output_handler_start's lock check).
     */
    @TruffleBoundary
    public void startBuffer(Object handler, String name, long flags) {
        if (running != null) {
            throw outputLocked("ob_start");
        }
        pl.codeloom.php.runtime.Refcount.hold(handler);
        buffers.push(new OutputBuffer(handler, handler == null ? "default output handler" : name, flags));
    }

    /**
     * Where a write goes when the current buffer's handler is disabled: the first buffer below whose handler isn't
     * (php_output_stack_apply_op passes it on), or null for the output itself.
     */
    private OutputBuffer writableBelow() {
        for (OutputBuffer buffer : buffers) {
            if (buffer.state != HandlerState.DISABLED) {
                return buffer;
            }
        }
        return null;
    }

    /** ob_start() with no handler, everything allowed. */
    public void startBuffer() {
        startBuffer(null, null, CLEANABLE | FLUSHABLE | REMOVABLE);
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
            names.add(it.next().name);
        }
        return names;
    }

    /** The current buffer as the ob_* notices name it, "NAME (LEVEL)"; there is one. */
    @TruffleBoundary
    public String bufferDescription() {
        return buffers.peek().name + " (" + (buffers.size() - 1) + ")";
    }

    /** Whether the current buffer allows an operation (one of the flags); false if there is no buffer. */
    @TruffleBoundary
    public boolean bufferAllows(long flag) {
        return !buffers.isEmpty() && (buffers.peek().flags & flag) != 0;
    }

    /**
     * php_output_handler_op: the buffer's contents through its handler in a phase, the buffer then empty. A disabled
     * handler isn't called and passes nothing. The handler gets START with its first phase; false or an exception
     * disables it and passes the contents on as they are; true, null or "" pass nothing. While another handler runs,
     * any of this is a fatal error (php_output_lock_error), named after function.
     */
    private Processed process(OutputBuffer buffer, int phase, String function) {
        if (buffer.state == HandlerState.DISABLED) {
            return Processed.NONE;
        }
        if (running != null) {
            throw outputLocked(function);
        }
        TruffleString contents = buffer.bytes.toStr();
        if (buffer.handler == null) {
            // the default handler passes its buffer on
            buffer.state = HandlerState.STARTED;
            buffer.bytes.reset();
            return new Processed(contents, null);
        }
        int mode = buffer.state == HandlerState.NEW ? phase | PHASE_START : phase;
        running = buffer;
        Object result;
        PhpThrowable thrown = null;
        try {
            result = PhpRef.deref(pl.codeloom.php.runtime.Refcount.done(Callables.call(this, buffer.handler,
                    contents, (long) mode)));
        } catch (PhpThrowable e) {
            thrown = e;
            result = Boolean.FALSE;
        } catch (RuntimeException | Error e) {
            running = null;
            throw e;
        }
        if (thrown == null && buffer.producedOutput && !producedOutput(buffer, function)) {
            // the handler ended its own buffer meanwhile
            running = null;
            return Processed.NONE;
        }
        TruffleString output = null;
        if (result == Boolean.FALSE) {
            // failed: from now on its buffer is passed by
            buffer.state = HandlerState.DISABLED;
            output = buffer.bytes.toStr();
        } else {
            buffer.state = HandlerState.STARTED;
            if (result != Boolean.TRUE) {
                // converted while the handler counts as running: a warning goes into the buffer, which is emptied
                TruffleString text = pl.codeloom.php.runtime.Convert.toStr(result);
                output = text.isEmpty() ? null : text;
            }
        }
        running = null;
        buffer.bytes.reset();
        return new Processed(output, thrown);
    }

    /**
     * A handler wrote output, which is lost with its buffer: Deprecated, written past the handler's buffer (disabled
     * meanwhile). False if the handler's buffer isn't on the stack any more.
     */
    private boolean producedOutput(OutputBuffer buffer, String function) {
        buffer.producedOutput = false;
        HandlerState state = buffer.state;
        buffer.state = HandlerState.DISABLED;
        ErrorFunctions.raise(this, ErrorFunctions.E_DEPRECATED, Str.of(function
                + "(): Producing output from user output handler " + buffer.name + " is deprecated"),
                pl.codeloom.php.runtime.SourceLocation.current());
        buffer.state = state;
        return buffers.contains(buffer);
    }

    /**
     * php_output_lock_error: an output-buffer operation while a handler runs ends the script; every buffer goes,
     * unsent (php_output_deactivate), so the error is written straight out.
     */
    private PhpBailout outputLocked(String function) {
        for (OutputBuffer buffer : buffers) {
            pl.codeloom.php.runtime.Refcount.unhold(buffer.handler);
        }
        buffers.clear();
        running = null;
        return ErrorFunctions.fatal(this, ErrorFunctions.E_ERROR, Str.of(function
                + "(): Cannot use output buffering in output buffering display handlers"),
                pl.codeloom.php.runtime.SourceLocation.current(), 0);
    }

    /**
     * php_output_stack_pop: ends the current buffer, its handler run a last time unless disabled, and writes what it
     * passes on to the level below unless discard. A buffer that isn't removable stays unless force, with a notice
     * named after function; false then, or if there is no buffer.
     */
    @TruffleBoundary
    public boolean endBuffer(boolean discard, boolean force, String function) {
        OutputBuffer top = buffers.peek();
        if (top == null) {
            return false;
        }
        if (!force && (top.flags & REMOVABLE) == 0) {
            ErrorFunctions.raise(this, ErrorFunctions.E_NOTICE, Str.of(function + "(): Failed to "
                    + (discard ? "discard" : "send") + " buffer of " + bufferDescription()),
                    pl.codeloom.php.runtime.SourceLocation.current());
            return false;
        }
        Processed processed = process(top, discard ? PHASE_CLEAN | PHASE_FINAL : PHASE_FINAL, function);
        if (buffers.peek() == top) {
            buffers.poll();
            pl.codeloom.php.runtime.Refcount.unhold(top.handler);
        }
        if (processed.output() != null && !discard) {
            write(processed.output());
        }
        processed.rethrow();
        return true;
    }

    /** php_output_flush (ob_flush): the handler's output goes to the next level; false if not flushable. */
    @TruffleBoundary
    public boolean flushBufferContents(String function) {
        OutputBuffer top = buffers.peek();
        if (top == null || (top.flags & FLUSHABLE) == 0) {
            return false;
        }
        Processed processed = process(top, PHASE_FLUSH, function);
        if (processed.output() != null && buffers.peek() == top) {
            buffers.poll();
            write(processed.output());
            buffers.push(top);
        }
        processed.rethrow();
        return true;
    }

    /** php_output_clean (ob_clean): the contents are discarded, the handler told; false if not cleanable. */
    @TruffleBoundary
    public boolean cleanBuffer(String function) {
        OutputBuffer top = buffers.peek();
        if (top == null || (top.flags & CLEANABLE) == 0) {
            return false;
        }
        process(top, PHASE_CLEAN, function).rethrow();
        return true;
    }

    /** The end of the script or request: open output buffers are flushed, then the output (and headers). */
    @TruffleBoundary
    public void endRequest() {
        while (!buffers.isEmpty()) {
            // php_output_end_all: each level passes its contents to the one below, removable or not
            endBuffer(false, true, "ob_end_flush");
        }
        flush();
    }

    /**
     * fastcgi_finish_request(): the output buffers ended and the output (headers first) sent as at the request's
     * end, then the server ends the request; later output goes nowhere. False when it ran already.
     */
    @TruffleBoundary
    public boolean finishRequest() {
        if (requestEnded) {
            return false;
        }
        endRequest();
        requestEnded = true;
        if (requestEnd != null) {
            requestEnd.run();
        }
        return true;
    }

    /** php_output_header's OG(output_start_filename/lineno): the executing PHP code's place, if any runs. */
    private static pl.codeloom.php.runtime.SourceLocation outputStartLocation() {
        pl.codeloom.php.runtime.SourceLocation at = pl.codeloom.php.runtime.SourceLocation.current();
        return at == null || at == pl.codeloom.php.runtime.SourceLocation.UNKNOWN ? null : at;
    }

    @TruffleBoundary
    public void flush() {
        try {
            if (response != null && !response.isSent()) {
                out.write(response.block(outputStartLocation()));
            }
            out.flush();
        } catch (IOException e) {
            throw new PhpError("output failed: " + e.getMessage());
        }
    }
}
