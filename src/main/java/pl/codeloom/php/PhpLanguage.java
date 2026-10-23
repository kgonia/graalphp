package pl.codeloom.php;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Option;
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
import pl.codeloom.php.runtime.PhpObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@TruffleLanguage.Registration(id = PhpLanguage.ID, name = "PHP", version = "8.5", defaultMimeType = PhpLanguage.MIME,
        characterMimeTypes = PhpLanguage.MIME)
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
     * Translated files by canonical path, shared by the contexts of this language. Files are taken not to change
     * while the engine runs (opcache with timestamp validation off).
     */
    private final Map<String, FileRootNode> files = new ConcurrentHashMap<>();

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

    /** A file's translation, made by translate on first use (once, also when contexts race). */
    public FileRootNode file(String canonical, Supplier<FileRootNode> translate) {
        FileRootNode root = files.get(canonical);
        if (root == null) {
            root = files.computeIfAbsent(canonical, path -> translate.get());
        }
        return root;
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
    }
}
