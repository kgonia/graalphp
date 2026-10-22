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
import pl.codeloom.php.nodes.ScriptRootNode;
import pl.codeloom.php.runtime.PhpObject;

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

    public Shape rootShape() {
        return rootShape;
    }

    @Override
    protected PhpContext createContext(Env env) {
        return new PhpContext(this, env, env.getOptions().get(AstRoot), env.getOptions().get(SourceRoot));
    }

    @Override
    protected OptionDescriptors getOptionDescriptors() {
        return new PhpLanguageOptionDescriptors();
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        return new ScriptRootNode(this, PhpContext.get(null).loadFile(request.getSource().getPath()).getCallTarget())
                .getCallTarget();
    }

    @Override
    protected void finalizeContext(PhpContext context) {
        context.flush();
    }
}
