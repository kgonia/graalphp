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

    private final Map<String, PhpFunction> functions = new HashMap<>();
    /** The declared functions by their name's language-level id (null: not declared here). */
    private PhpFunction[] functionSlots = new PhpFunction[256];
    private final Map<String, PhpClass> classes = new HashMap<>();
    private final Map<String, Object> constants = new HashMap<>();
    /** The defined constants by their name's language-level id (null: not defined here). */
    private Object[] constantSlots = new Object[256];
    private final Map<String, PhpRef> globals = new HashMap<>();
    private final Set<String> included = new HashSet<>();
    private final java.util.ArrayDeque<ByteBuilder> buffers = new java.util.ArrayDeque<>();
    private Object errorHandler = pl.codeloom.php.runtime.PhpNull.NULL;
    /** The next object id: ids start at 1 in each context, as in each php process. */
    private int nextObjectId = 1;
    private final BuiltinState builtinState = new BuiltinState();
    /** Static variables (`static $x`) by function, then name. */
    private final Map<PhpFunction, Map<String, PhpRef>> staticVariables = new HashMap<>();
    private final pl.codeloom.php.runtime.Callables.LastCall lastCall = new pl.codeloom.php.runtime.Callables.LastCall();

    public PhpContext(PhpLanguage language, TruffleLanguage.Env env, String astRoot, String sourceRoot) {
        this.language = language;
        this.env = env;
        this.astRoot = Path.of(astRoot).toAbsolutePath().normalize();
        this.sourceRoot = Path.of(sourceRoot).toAbsolutePath().normalize();
        this.out = new BufferedOutputStream(env.out(), 1 << 16);
        Builtins.install(this, language.builtins());
        pl.codeloom.php.runtime.PhpArray argv = new pl.codeloom.php.runtime.PhpArray();
        for (String argument : env.getApplicationArguments()) {
            argv.append(Str.of(argument));
        }
        globalRef("argv").set(argv);
        globalRef("argc").set((long) argv.size());
    }

    public static PhpContext get(Node node) {
        return CONTEXT.get(node);
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
     * file reads its AST export and translates it, and every context shares the result.
     */
    @TruffleBoundary
    public FileRootNode loadFile(String path) {
        String canonical = canonicalPath(path);
        return language.file(canonical, () -> translate(path, canonical));
    }

    private FileRootNode translate(String path, String canonical) {
        Path relative = sourceRoot.relativize(Path.of(canonical));
        Path json = astRoot.resolve(relative.toString() + ".json");
        TruffleFile file = env.getPublicTruffleFile(json.toString());
        Ast ast;
        try (InputStream in = file.newInputStream()) {
            ast = AstReader.read(in);
        } catch (IOException e) {
            throw new PhpError("Failed opening '" + path + "' for inclusion (no AST export at " + json + ")");
        }
        return new Translator(language, canonical, phpSource(canonical)).translateFile(ast);
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
        String key = function.getName().toLowerCase();
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
        return classes.get(lowerName);
    }

    @TruffleBoundary
    public PhpClass getClass(String name) {
        PhpClass phpClass = classes.get(name.toLowerCase());
        if (phpClass == null) {
            throw new PhpError("Class \"" + name + "\" not found");
        }
        return phpClass;
    }

    @TruffleBoundary
    public void declareClass(PhpClass phpClass) {
        if (classes.putIfAbsent(phpClass.getLowerName(), phpClass) != null) {
            throw new PhpError("Cannot declare class " + phpClass.getName() + ", because the name is already in use");
        }
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
        return globals.computeIfAbsent(name, n -> new PhpRef(pl.codeloom.php.runtime.PhpNull.NULL));
    }

    @TruffleBoundary
    public PhpRef findGlobal(String name) {
        return globals.get(name);
    }

    @TruffleBoundary
    public void bindGlobal(String name, PhpRef ref) {
        globals.put(name, ref);
        globalsVersion++;
    }

    @TruffleBoundary
    public void unsetGlobal(String name) {
        globals.remove(name);
        globalsVersion++;
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

    // ---- output ----

    @TruffleBoundary
    public void write(TruffleString s) {
        try {
            if (buffers.isEmpty()) {
                out.write(Str.readBytes(s));
            } else {
                buffers.peek().write(Str.readBytes(s));
            }
        } catch (IOException e) {
            throw new PhpError("output failed: " + e.getMessage());
        }
    }

    /** ob_start(). */
    @TruffleBoundary
    public void startBuffer() {
        buffers.push(new ByteBuilder());
    }

    /** ob_get_contents(): the current buffer, or null if none. */
    @TruffleBoundary
    public TruffleString bufferContents() {
        return buffers.isEmpty() ? null : Str.of(buffers.peek().toByteArray());
    }

    /** Discards the current buffer (ob_end_clean); false if there was none. */
    @TruffleBoundary
    public boolean endBuffer() {
        return buffers.poll() != null;
    }

    @TruffleBoundary
    public void flush() {
        try {
            out.flush();
        } catch (IOException e) {
            throw new PhpError("output failed: " + e.getMessage());
        }
    }
}
