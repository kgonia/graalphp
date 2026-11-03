package pl.codeloom.php.parser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The checks php-src makes on jumps when it compiles a file, before any of the file runs and whether or not a
 * function is ever called: zend_compile_break_continue, zend_compile_label and zend_compile_try while it compiles,
 * then, once a function (an op_array) is compiled, pass_two's zend_resolve_goto_label and
 * zend_check_finally_breakout for each break, continue and goto in order. Each function, method and closure is its
 * own op_array, checked where php-src compiles it: in place, before the code around it goes on. Argument lists are
 * checked in place too (zend_compile_args, zend_compile_const_expr_args, zend_compile_attributes): php-src rejects
 * the order of named, positional and unpacked arguments when it compiles them.
 */
public final class JumpChecks {
    /** php-src's E_COMPILE_ERROR: its message and the line it reports. */
    static final class Failure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int line;

        Failure(int line, String message) {
            super(message, null, false, false);
            this.line = line;
        }
    }

    /**
     * A loop or switch (php-src's brk_cont element) and the finally blocks around it, by number in the order of
     * their trys (the op_array's try_catch_array), outermost first.
     */
    private record Loop(Loop parent, List<Integer> finallies) {
    }

    private record Label(Loop loop, List<Integer> finallies) {
    }

    /** A break or continue (label null, to target) or a goto (to label), from the loop and finally blocks it is in. */
    private record Jump(int line, String label, Loop from, Loop target, List<Integer> finallies) {
    }

    private Loop loop;
    private List<Integer> finallies = List.of();
    private int trys;
    private final Map<String, Label> labels = new HashMap<>();
    private final List<Jump> jumps = new ArrayList<>();
    /** The line of the node met last: php-src's CG(zend_lineno) where an argument list's error is raised. */
    private int line;
    /** Inside a constant expression (a default, a constant's value, an attribute's argument). */
    private boolean constant;

    private JumpChecks() {
    }

    /**
     * Checks a file's code, a function body left unread by the Java parser being one it checked when it parsed the
     * file ({@link #passes}); throws the first error.
     */
    static void file(Ast file) {
        opArray(file);
    }

    /** Whether a function body, all of it read, passes (the Java parser keeps one that doesn't for {@link #file}). */
    public static boolean passes(Ast body) {
        try {
            opArray(body);
            return true;
        } catch (Failure failure) {
            return false;
        }
    }

    private static void opArray(Ast statements) {
        JumpChecks checks = new JumpChecks();
        checks.visit(statements);
        for (Jump jump : checks.jumps) {
            checks.resolve(jump);
        }
    }

    private void visit(Object value) {
        if (!(value instanceof Ast node)) {
            return;
        }
        line = node.line;
        if (node.get("attributes") instanceof Ast attributes) {
            // zend_compile_attributes, before the declaration's other parts, at its line
            attributes(attributes, node.line);
        }
        switch (node.kind) {
            case "AST_ARG_LIST" -> arguments(node);
            case "AST_ATTRIBUTE_LIST" -> {
                // checked with its declaration
            }
            case "AST_PARAM", "AST_CONST_ELEM", "AST_PROP_ELEM", "AST_ENUM_CASE" -> {
                // their defaults and values are constant expressions (zend_const_expr_to_zval)
                boolean outer = constant;
                for (Object child : node.children()) {
                    visit(child);
                }
                Map<String, Object> named = node.namedOrNull();
                if (named != null) {
                    for (Map.Entry<String, Object> child : named.entrySet()) {
                        constant = outer || child.getKey().equals("default") || child.getKey().equals("value")
                                || child.getKey().equals("expr");
                        visit(child.getValue());
                        constant = outer;
                    }
                }
            }
            case "AST_FUNC_DECL", "AST_METHOD", "AST_CLOSURE", "AST_ARROW_FUNC", "AST_PROPERTY_HOOK" -> {
                visitChildren(node, "stmts");
                Object stmts = node.get("stmts");
                if (stmts instanceof Ast || stmts instanceof ExportBody) {
                    opArray(node.body());
                }
            }
            case "AST_WHILE", "AST_DO_WHILE", "AST_FOR", "AST_FOREACH", "AST_SWITCH" -> {
                visitChildren(node, "stmts");
                Loop outer = loop;
                loop = new Loop(outer, finallies);
                visit(node.get("stmts"));
                loop = outer;
            }
            case "AST_TRY" -> {
                Ast catches = node.node("catches");
                if ((catches == null || catches.size() == 0) && node.get("finally") == null) {
                    throw new Failure(node.line, "Cannot use try without catch or finally");
                }
                int number = trys++;
                visit(node.get("try"));
                visit(catches);
                List<Integer> outer = finallies;
                List<Integer> inside = new ArrayList<>(outer);
                inside.add(number);
                finallies = inside;
                visit(node.get("finally"));
                finallies = outer;
            }
            case "AST_BREAK", "AST_CONTINUE" -> breakOrContinue(node);
            case "AST_GOTO" -> jumps.add(new Jump(node.line, (String) node.get("label"), loop, null, finallies));
            case "AST_LABEL" -> {
                String name = (String) node.get("name");
                if (labels.putIfAbsent(name, new Label(loop, finallies)) != null) {
                    throw new Failure(node.line, "Label '" + name + "' already defined");
                }
            }
            default -> visitChildren(node, null);
        }
    }

    private void visitChildren(Ast node, String except) {
        for (Object child : node.children()) {
            visit(child);
        }
        Map<String, Object> named = node.namedOrNull();
        if (named != null) {
            for (Map.Entry<String, Object> child : named.entrySet()) {
                if (!child.getKey().equals(except)) {
                    visit(child.getValue());
                }
            }
        }
    }

    /**
     * A call's arguments as zend_compile_args checks them, each before it is compiled: no positional argument after
     * an unpacked or a named one, no unpacking after a named one (unpacking first, then names, is allowed; a name
     * given twice is a run-time Error). In a constant expression's `new`, zend_compile_const_expr_args': no
     * unpacking at all.
     */
    private void arguments(Ast list) {
        boolean named = false;
        boolean unpacked = false;
        for (Object arg : list.children()) {
            if (arg instanceof Ast ast && ast.is("AST_UNPACK")) {
                if (constant) {
                    throw new Failure(line, "Argument unpacking in constant expressions is not supported");
                }
                if (named) {
                    throw new Failure(line, "Cannot use argument unpacking after named arguments");
                }
                unpacked = true;
            } else if (arg instanceof Ast ast && ast.is("AST_NAMED_ARG")) {
                named = true;
            } else if (unpacked) {
                throw new Failure(line, "Cannot use positional argument after argument unpacking");
            } else if (named) {
                throw new Failure(line, "Cannot use positional argument after named argument");
            }
            visit(arg);
        }
    }

    /**
     * A declaration's attributes as zend_compile_attributes checks their arguments, at the declaration's line: no
     * closure made by `(...)`, no unpacking, no positional argument after a named one, and no name twice; each
     * argument a constant expression.
     */
    private void attributes(Ast list, int declarationLine) {
        boolean outer = constant;
        constant = true;
        for (Object group : list.children()) {
            for (Object element : ((Ast) group).children()) {
                Object args = ((Ast) element).get("args");
                if (args instanceof Ast callable && callable.is("AST_CALLABLE_CONVERT")) {
                    throw new Failure(declarationLine, "Cannot create Closure as attribute argument");
                }
                if (!(args instanceof Ast argList)) {
                    continue;
                }
                boolean named = false;
                List<Object> names = new ArrayList<>();
                for (Object arg : argList.children()) {
                    Object value = arg;
                    if (arg instanceof Ast ast && ast.is("AST_UNPACK")) {
                        throw new Failure(declarationLine, "Cannot use unpacking in attribute argument list");
                    }
                    if (arg instanceof Ast ast && ast.is("AST_NAMED_ARG")) {
                        Object name = ast.get("name");
                        if (names.contains(name)) {
                            throw new Failure(declarationLine, "Duplicate named parameter $" + name);
                        }
                        names.add(name);
                        named = true;
                        value = ast.get("expr");
                    } else if (named) {
                        throw new Failure(declarationLine, "Cannot use positional argument after named argument");
                    }
                    visit(value);
                }
            }
        }
        constant = outer;
    }

    private void breakOrContinue(Ast node) {
        String keyword = node.is("AST_BREAK") ? "break" : "continue";
        Object depthValue = node.get("depth");
        long depth = 1;
        if (depthValue != null) {
            if (depthValue instanceof Ast) {
                throw new Failure(node.line, "'" + keyword + "' operator with non-integer operand is no longer supported");
            }
            if (!(depthValue instanceof Long given) || given < 1) {
                throw new Failure(node.line, "'" + keyword + "' operator accepts only positive integers");
            }
            depth = given;
        }
        if (loop == null) {
            throw new Failure(node.line, "'" + keyword + "' not in the 'loop' or 'switch' context");
        }
        Loop target = loop;
        for (long level = 1; level < depth; level++) {
            target = target.parent();
            if (target == null) {
                throw new Failure(node.line, "Cannot '" + keyword + "' " + depth + " levels");
            }
        }
        jumps.add(new Jump(node.line, null, loop, target, finallies));
    }

    /** pass_two for a jump: a goto's label (zend_resolve_goto_label), then the finally blocks it leaves or enters. */
    private void resolve(Jump jump) {
        List<Integer> to;
        if (jump.label() == null) {
            to = jump.target().finallies();
        } else {
            Label label = labels.get(jump.label());
            if (label == null) {
                throw new Failure(jump.line(), "'goto' to undefined label '" + jump.label() + "'");
            }
            for (Loop from = jump.from(); from != label.loop(); from = from.parent()) {
                if (from == null) {
                    throw new Failure(jump.line(), "'goto' into loop or switch statement is disallowed");
                }
            }
            to = label.finallies();
        }
        // zend_check_finally_breakout: the first finally block, in try order, that one end is in and the other isn't
        List<Integer> from = jump.finallies();
        int common = 0;
        while (common < from.size() && common < to.size() && from.get(common).equals(to.get(common))) {
            common++;
        }
        boolean leaves = common < from.size();
        boolean enters = common < to.size();
        if (enters && (!leaves || to.get(common) < from.get(common))) {
            throw new Failure(jump.line(), "jump into a finally block is disallowed");
        }
        if (leaves) {
            throw new Failure(jump.line(), "jump out of a finally block is disallowed");
        }
    }
}
