package pl.codeloom.php;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Option;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.object.Shape;
import org.graalvm.options.OptionCategory;
import org.graalvm.options.OptionDescriptors;
import org.graalvm.options.OptionKey;
import org.graalvm.options.OptionStability;
import org.graalvm.options.OptionValues;
import pl.codeloom.php.builtins.Builtins;
import pl.codeloom.php.nodes.FileRootNode;
import pl.codeloom.php.nodes.ScriptRootNode;
import pl.codeloom.php.runtime.ClassKey;
import pl.codeloom.php.runtime.NameIds;
import pl.codeloom.php.runtime.PhpObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * PHP as a Truffle language. The policy is SHARED: contexts on one engine (a server's requests) share this
 * instance, so its translated files, built-in functions and compiled code are reused; per-request state lives in
 * {@link PhpContext}.
 */
@TruffleLanguage.Registration(id = PhpLanguage.ID, name = "PHP", version = "8.5", defaultMimeType = PhpLanguage.MIME,
        characterMimeTypes = PhpLanguage.MIME, contextPolicy = TruffleLanguage.ContextPolicy.SHARED)
public final class PhpLanguage extends TruffleLanguage<PhpContext> {
    public static final String ID = "php";
    public static final String MIME = "application/x-php";

    @Option(help = "Directory holding php-ast JSON exports, mirroring paths under SourceRoot.",
            category = OptionCategory.USER, stability = OptionStability.EXPERIMENTAL)
    static final OptionKey<String> AstRoot = new OptionKey<>("target/ast");

    @Option(help = "Root the PHP source paths are relative to when looking up their AST export.",
            category = OptionCategory.USER, stability = OptionStability.EXPERIMENTAL)
    static final OptionKey<String> SourceRoot = new OptionKey<>(".");

    private static final LanguageReference<PhpLanguage> LANGUAGE = LanguageReference.create(PhpLanguage.class);

    private final Shape rootShape = Shape.newBuilder().layout(PhpObject.class, java.lang.invoke.MethodHandles.lookup())
            .build();

    public static PhpLanguage get(Node node) {
        return LANGUAGE.get(node);
    }

    /**
     * Translated files by canonical path, shared by the contexts of this language, with the version of the AST
     * export they were translated from (as opcache with timestamp validation): a file rewritten while the
     * engine runs (a plugin or core update) is translated again on its next include.
     */
    private final Map<String, CachedFile> files = new ConcurrentHashMap<>();

    /** A translation and the AST export version (modification time and size) it was made from. */
    private record CachedFile(FileRootNode root, long modified, long size) {
        boolean isFor(long otherModified, long otherSize) {
            return modified == otherModified && size == otherSize;
        }
    }

    /**
     * Valid while this language instance serves one context. Until then a node may keep what it looked up in the
     * context (a function, a constant, a class) as a compile-time constant; Truffle calls
     * initializeMultipleContexts before a second context shares the instance, which invalidates it.
     */
    private final Assumption singleContext = Truffle.getRuntime().createAssumption("single PHP context");

    /** Ids of function names (lowercase) and constant names: a node resolves its name once, for every context. */
    private final NameIds functionIds = new NameIds();
    private final NameIds constantIds = new NameIds();
    private final NameIds classIds = new NameIds();
    /** Interned class identities (see ClassKey). */
    private final Map<ClassKey.Id, ClassKey> classKeys = new ConcurrentHashMap<>();

    /** Built-in functions are language-level: built on first use, then declared by every context. */
    private volatile Builtins.Table builtins;

    public Shape rootShape() {
        return rootShape;
    }

    public Builtins.Table builtins() {
        Builtins.Table table = builtins;
        if (table == null) {
            synchronized (this) {
                table = builtins;
                if (table == null) {
                    table = Builtins.build(this);
                    builtins = table;
                }
            }
        }
        return table;
    }

    @Override
    protected PhpContext createContext(Env env) {
        return new PhpContext(this, env, env.getOptions().get(AstRoot), env.getOptions().get(SourceRoot));
    }

    /** The id of a function name (lowercase); a context keeps its functions by id. */
    public int functionId(String lowerName) {
        return functionIds.id(lowerName);
    }

    /** The id of a class name (lowercase); a context keeps its classes by id. */
    public int classId(String lowerName) {
        return classIds.id(lowerName);
    }

    /** The one ClassKey for these declarations, in every context. */
    public ClassKey classKey(ClassKey.Id id) {
        return classKeys.computeIfAbsent(id, ClassKey::new);
    }

    /** The id of a constant name (case-sensitive); a context keeps its constants by id. */
    public int constantId(String name) {
        return constantIds.id(name);
    }

    public Assumption singleContext() {
        return singleContext;
    }

    @Override
    protected void initializeMultipleContexts() {
        singleContext.invalidate();
    }

    /**
     * A file's translation for the given version of its AST export, made by translate when there is none for
     * that version (once, also when contexts race).
     */
    public FileRootNode file(String canonical, long modified, long size, Supplier<FileRootNode> translate) {
        CachedFile cached = files.get(canonical);
        if (cached == null || !cached.isFor(modified, size)) {
            cached = files.compute(canonical, (path, current) -> current != null && current.isFor(modified, size)
                    ? current : new CachedFile(translate.get(), modified, size));
        }
        return cached.root();
    }

    /** Contexts share translated files only when they read the same AST exports. */
    @Override
    protected boolean areOptionsCompatible(OptionValues firstOptions, OptionValues newOptions) {
        return firstOptions.get(AstRoot).equals(newOptions.get(AstRoot))
                && firstOptions.get(SourceRoot).equals(newOptions.get(SourceRoot));
    }

    @Override
    protected OptionDescriptors getOptionDescriptors() {
        return new PhpLanguageOptionDescriptors();
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        return new ScriptRootNode(this, request.getSource().getPath()).getCallTarget();
    }

    @Override
    protected void finalizeContext(PhpContext context) {
        context.flush();
        context.builtinState().close();
    }
}
