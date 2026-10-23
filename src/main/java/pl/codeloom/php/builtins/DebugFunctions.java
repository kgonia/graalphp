package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.NodeUtil;
import com.oracle.truffle.api.nodes.RootNode;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.nodes.call.ClosureNode;
import pl.codeloom.php.nodes.stmt.DeclareNodes;
import pl.codeloom.php.runtime.ClassTemplate;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.Str;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/** Debugging aids for the runtime's own tools (tests/gate2): not PHP functions. */
final class DebugFunctions {
    private DebugFunctions() {
    }

    static void register(Registry r) {
        r.fn("truffle_translate", (context, a) -> translate(context, Str.java(Args.str(a, 0))));
    }

    /**
     * Translates a file and every function, method and closure body in it, without running anything (bodies
     * are otherwise translated on first call). Returns the translator's errors, one per body that failed.
     */
    @TruffleBoundary
    private static PhpArray translate(PhpContext context, String path) {
        PhpArray errors = new PhpArray();
        RootNode file;
        try {
            file = context.loadFile(path);
        } catch (PhpError e) {
            errors.append(Str.of(e.getMessage()));
            return errors;
        }
        Deque<Node> roots = new ArrayDeque<>();
        Set<PhpFunction> seen = new HashSet<>();
        roots.add(file);
        while (!roots.isEmpty()) {
            Node root = roots.pop();
            for (Node node : NodeUtil.findAllNodeInstances(root, Node.class)) {
                if (node instanceof DeclareNodes.DeclareFunctionNode declaration) {
                    body(declaration.getFunction(), seen, roots, errors);
                } else if (node instanceof ClosureNode closure) {
                    body(closure.getFunction(), seen, roots, errors);
                } else if (node instanceof DeclareNodes.DeclareClassNode
                        || node instanceof DeclareNodes.NewAnonymousClassNode) {
                    ClassTemplate template = node instanceof DeclareNodes.DeclareClassNode declaration
                            ? declaration.getTemplate() : ((DeclareNodes.NewAnonymousClassNode) node).getTemplate();
                    if (template.getUnsupported() != null) {
                        errors.append(Str.of(template.getUnsupported()));
                    }
                    for (ClassTemplate.Member member : template.getMembers()) {
                        if (member instanceof ClassTemplate.Method method) {
                            body(method.function(), seen, roots, errors);
                        }
                    }
                }
            }
        }
        return errors;
    }

    private static void body(PhpFunction function, Set<PhpFunction> seen, Deque<Node> roots, PhpArray errors) {
        if (function.isAbstract() || !seen.add(function)) {
            return;
        }
        try {
            roots.add(function.getCallTarget().getRootNode());
        } catch (PhpError e) {
            errors.append(Str.of(e.getMessage()));
        }
    }
}
