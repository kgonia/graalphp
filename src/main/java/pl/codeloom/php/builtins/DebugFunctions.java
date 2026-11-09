package pl.codeloom.php.builtins;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.bytecode.BytecodeRootNode;
import com.oracle.truffle.api.bytecode.Instruction;
import com.oracle.truffle.api.nodes.RootNode;
import pl.codeloom.php.PhpContext;
import pl.codeloom.php.runtime.ClassTemplate;
import pl.codeloom.php.runtime.PhpArray;
import pl.codeloom.php.runtime.PhpError;
import pl.codeloom.php.runtime.PhpFunction;
import pl.codeloom.php.runtime.PhpObject;
import pl.codeloom.php.runtime.PhpThrowable;
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
        // an object's reference count (Refcount), -1 for any other value; passing it to a built-in counts nothing
        r.fn("truffle_refcount", (context, a) -> Args.get(a, 0) instanceof PhpObject object
                ? (long) object.refcount() : Args.get(a, 0) instanceof pl.codeloom.php.runtime.PhpClosure closure
                ? (long) closure.refcount() : Args.get(a, 0) instanceof pl.codeloom.php.runtime.PhpArray array
                ? (long) array.holderCount() : -1L);
    }

    /**
     * Translates a file and every function, method and closure body in it, without running anything (bodies
     * are otherwise translated on first call): the declarations are the constant operands of each root's
     * instructions. Returns the translator's errors, one per body that failed.
     */
    @TruffleBoundary
    private static PhpArray translate(PhpContext context, String path) {
        PhpArray errors = new PhpArray();
        RootNode file;
        try {
            file = context.loadFile(path);
        } catch (PhpThrowable e) {
            errors.append(Str.of(e.getMessage()));
            return errors;
        }
        Deque<RootNode> roots = new ArrayDeque<>();
        Set<Object> seen = new HashSet<>();
        roots.add(file);
        while (!roots.isEmpty()) {
            if (!(roots.pop() instanceof BytecodeRootNode root)) {
                continue;
            }
            for (Instruction instruction : root.getBytecodeNode().getInstructions()) {
                for (Instruction.Argument argument : instruction.getArguments()) {
                    if (argument.getKind() != Instruction.Argument.Kind.CONSTANT) {
                        continue;
                    }
                    Object constant = argument.asConstant();
                    if (constant instanceof PhpFunction function) {
                        body(function, seen, roots, errors);
                    } else if (constant instanceof ClassTemplate template && seen.add(template)) {
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
        }
        return errors;
    }

    private static void body(PhpFunction function, Set<Object> seen, Deque<RootNode> roots, PhpArray errors) {
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
